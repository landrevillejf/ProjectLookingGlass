/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.search;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The advanced-search engine: walks one or more directory roots in parallel,
 * applies the query's name / kind / size / recency / content filters, grades
 * each hit for relevance and <em>streams</em> matches to a consumer the moment
 * they are found.
 *
 * <p>Design goals, in order:</p>
 * <ul>
 *   <li><b>Robust</b> &mdash; unreadable directories, permission errors, dangling
 *       symlinks and vanished files are skipped, never thrown; symlinked folders
 *       are not descended into, so symlink loops cannot hang the walk; an invalid
 *       glob/regex matches nothing instead of crashing.</li>
 *   <li><b>Performant</b> &mdash; a fixed pool of daemon workers sized to the CPU
 *       count traverses breadth-first; patterns are compiled once; the walk stops
 *       early at the result cap; content grepping skips binaries and huge files.</li>
 *   <li><b>Live</b> &mdash; results are delivered incrementally (under a lock, so
 *       the consumer sees a serialised stream it can marshal to the EDT) and the
 *       returned {@link SearchHandle} cancels or awaits completion.</li>
 * </ul>
 *
 * <p>The engine holds no mutable state between searches, so a single instance may
 * run many concurrent searches.</p>
 */
public final class SearchEngine {

    /** The per-file content-hit cap used by {@link #search}. */
    static final int CONTENT_MAX_HITS = ContentScanner.DEFAULT_MAX_HITS;

    private final int parallelism;

    /** Builds an engine sized to the available processors (at least two). */
    public SearchEngine() {
        this(defaultParallelism());
    }

    /**
     * Builds an engine with a fixed worker count. Values below one are coerced to
     * one, so a test can force single-threaded, deterministic-ish traversal.
     */
    public SearchEngine(int parallelism) {
        this.parallelism = Math.max(1, parallelism);
    }

    private static int defaultParallelism() {
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    /** The worker count this engine uses. */
    public int getParallelism() {
        return parallelism;
    }

    /**
     * Runs {@code query} asynchronously, streaming each match to {@code sink}.
     * The sink is invoked from worker threads under a lock (serialised, never
     * concurrently); a UI consumer should marshal to its own thread. The returned
     * {@link SearchHandle} cancels the walk and reports completion.
     *
     * <p>A null query, a null sink, or a query with no roots yields an
     * already-finished handle and emits nothing.</p>
     */
    public SearchHandle search(SearchQuery query, Consumer<SearchMatch> sink) {
        return search(query, sink, null);
    }

    /**
     * As {@link #search(SearchQuery, Consumer)} but additionally runs
     * {@code onDone} exactly once when the walk finishes (completed, capped or
     * cancelled), on whichever worker observes completion.
     */
    public SearchHandle search(SearchQuery query, Consumer<SearchMatch> sink, Runnable onDone) {
        if (query == null || sink == null || query.getRoots().isEmpty()) {
            SearchHandle done = new SearchHandle(onDone);
            done.finish();
            return done;
        }
        State st = new State(query, sink, onDone, parallelism);
        for (Path root : query.getRoots()) {
            if (root != null) {
                st.submit(root);
            }
        }
        // Every root was rejected (e.g. an all-null root list): finish now.
        if (st.active.get() == 0) {
            st.complete();
        }
        return st.handle;
    }

    /**
     * Convenience: runs {@code query} to completion on background workers,
     * blocking the caller, and returns the matches sorted by descending relevance
     * (ties broken by path). Intended for tests and simple one-shot callers; a UI
     * should prefer the streaming {@link #search} so results appear live.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public List<SearchMatch> searchAll(SearchQuery query) throws InterruptedException {
        List<SearchMatch> out = Collections.synchronizedList(new ArrayList<>());
        SearchHandle handle = search(query, out::add);
        handle.await(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        List<SearchMatch> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparingInt(SearchMatch::score).reversed()
                .thenComparing((SearchMatch m) -> m.path().toString()));
        return sorted;
    }

    // ------------------------------------------------------------------
    // Per-search mutable state and the walk itself
    // ------------------------------------------------------------------

    /** Everything one running search needs; confined to that search's workers. */
    private static final class State {
        private final SearchQuery query;
        private final Consumer<SearchMatch> sink;
        private final SearchHandle handle;
        private final ExecutorService pool;
        private final NameMatcher nameMatcher;
        private final Predicate<String> contentMatcher;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Object emitLock = new Object();
        private final long now = System.currentTimeMillis();

        State(SearchQuery query, Consumer<SearchMatch> sink, Runnable onDone, int parallelism) {
            this.query = query;
            this.sink = sink;
            this.handle = new SearchHandle(onDone);
            this.nameMatcher = NameMatcher.of(
                    query.getNameMode(), query.getNamePattern(), query.isCaseSensitive());
            this.contentMatcher = query.isContentSearch()
                    ? ContentScanner.lineMatcher(query.getContentPattern(),
                            query.isContentRegex(), query.isCaseSensitive())
                    : null;
            this.pool = Executors.newFixedThreadPool(parallelism, daemonFactory());
        }

        private static ThreadFactory daemonFactory() {
            AtomicInteger n = new AtomicInteger();
            return r -> {
                Thread t = new Thread(r, "lg3d-search-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
        }

        /** Queues {@code dir} for traversal, tracking it as an in-flight task. */
        void submit(Path dir) {
            active.incrementAndGet();
            try {
                pool.execute(() -> {
                    try {
                        walk(dir);
                    } finally {
                        if (active.decrementAndGet() == 0) {
                            complete();
                        }
                    }
                });
            } catch (RejectedExecutionException rex) {
                // The pool was shut down (cancelled) between the guard and submit.
                if (active.decrementAndGet() == 0) {
                    complete();
                }
            }
        }

        private void walk(Path dir) {
            if (stopRequested()) {
                return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (stopRequested()) {
                        return;
                    }
                    process(entry);
                }
            } catch (IOException | RuntimeException ex) {
                // Unreadable / permission-denied directory: skip it silently.
            }
        }

        private void process(Path entry) {
            Path fileName = entry.getFileName();
            String name = (fileName == null) ? entry.toString() : fileName.toString();
            boolean hidden = name.startsWith(".");
            if (hidden && !query.isIncludeHidden()) {
                return;
            }
            boolean symlink = Files.isSymbolicLink(entry);
            // Never follow symlinks: this both avoids loops and matches "as listed".
            boolean isDir = !symlink && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);

            if (kindAllows(isDir) && nameMatcher.matches(name)) {
                consider(entry, name, isDir);
            }
            if (isDir) {
                submit(entry);
            }
        }

        private boolean kindAllows(boolean isDir) {
            switch (query.getKind()) {
                case FILES:
                    return !isDir;
                case DIRECTORIES:
                    return isDir;
                case ANY:
                default:
                    return true;
            }
        }

        private void consider(Path entry, String name, boolean isDir) {
            BasicFileAttributes attrs = attrs(entry);
            if (attrs == null) {
                return;
            }
            long size = isDir ? 0L : attrs.size();
            long mtime = attrs.lastModifiedTime() == null
                    ? 0L : attrs.lastModifiedTime().toMillis();
            if (!sizeAllows(isDir, size) || !dateAllows(mtime)) {
                return;
            }
            List<ContentHit> hits = Collections.emptyList();
            if (query.isContentSearch() && !isDir) {
                hits = ContentScanner.scan(entry, contentMatcher,
                        SearchQuery.MAX_CONTENT_BYTES, CONTENT_MAX_HITS,
                        ContentScanner.DEFAULT_SNIPPET_LEN);
                if (hits.isEmpty()) {
                    return; // content search is an AND filter for files
                }
            }
            int score = score(name, entry, isDir, mtime, hits);
            emit(new SearchMatch(entry.toAbsolutePath(), name, isDir, size, mtime, score, hits));
        }

        private BasicFileAttributes attrs(Path entry) {
            try {
                return Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException ex) {
                return null;
            }
        }

        private boolean sizeAllows(boolean isDir, long size) {
            if (isDir) {
                return true; // size filters target files, not folders
            }
            if (query.getMinSize().isPresent() && size < query.getMinSize().getAsLong()) {
                return false;
            }
            return !query.getMaxSize().isPresent() || size <= query.getMaxSize().getAsLong();
        }

        private boolean dateAllows(long mtime) {
            if (!query.getModifiedWithinDays().isPresent() || mtime <= 0L) {
                return !query.getModifiedWithinDays().isPresent();
            }
            long ageDays = (now - mtime) / TimeUnit.DAYS.toMillis(1);
            return ageDays <= query.getModifiedWithinDays().getAsInt();
        }

        private int score(String name, Path entry, boolean isDir, long mtime,
                          List<ContentHit> hits) {
            int s = nameMatcher.score(name);
            int depth = entry.getNameCount();
            s += Math.max(0, 20 - depth);                       // shallower wins
            long ageDays = (mtime <= 0L) ? Long.MAX_VALUE
                    : (now - mtime) / TimeUnit.DAYS.toMillis(1);
            if (ageDays <= 1) {
                s += 15;
            } else if (ageDays <= 7) {
                s += 10;
            } else if (ageDays <= 30) {
                s += 5;
            }
            s += Math.min(hits.size(), 10) * 3;                 // content evidence
            if (isDir) {
                s -= 5;                                         // prefer concrete files
            }
            return Math.max(0, s);
        }

        private void emit(SearchMatch match) {
            synchronized (emitLock) {
                if (stopRequested()) {
                    return;
                }
                sink.accept(match);
                handle.countMatch();
                if (handle.getMatchedCount() >= query.getMaxResults()) {
                    handle.cancel(); // reached the cap: stop the whole walk
                }
            }
        }

        private boolean stopRequested() {
            return handle.isCancelled() || finished.get();
        }

        /** Winds the search down exactly once: stops the pool and fires completion. */
        void complete() {
            if (finished.compareAndSet(false, true)) {
                pool.shutdownNow();
                handle.finish();
            }
        }
    }
}
