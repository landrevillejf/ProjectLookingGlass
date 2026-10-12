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
package org.jdesktop.lg3d.displayserver.crash;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one entry point the desktop uses to file a bug: signature, redaction,
 * spool and prefilled link, wired together.
 *
 * <p>Everything the pipeline needs is reached through this class, and the classes
 * underneath it stay pure ({@link CrashSignature}, {@link Redactor},
 * {@link IssuePrefill}) or single-purpose ({@link CrashStore},
 * {@link ReportEnvironment}) so each can be tested on its own. The facade exists
 * so no caller has to assemble five objects correctly under the worst conditions
 * in this codebase - a thread that is already dying.</p>
 *
 * <h2>Four rules that are the whole design</h2>
 *
 * <ul>
 *   <li><b>It never throws.</b> Every public method catches {@code Throwable}.
 *   A reporter that fails is a second failure layered on the user's first, and on
 *   the fatal path that second failure is what stops the crash dialog from ever
 *   appearing. A defect in bug reporting must degrade to a missing bug report,
 *   nothing else.</li>
 *   <li><b>No credential, ever.</b> The only network-facing artefact produced is
 *   a {@code github.com/.../issues/new} link handed to the browser. There is no
 *   HTTP call here, so there is no token to hide, no proxy authentication to
 *   store and nothing to leak from a kiosk image.</li>
 *   <li><b>One browser tab per bug per session.</b> The same signature seen forty
 *   times is spooled and counted forty times but opened once. A crash loop that
 *   launches a browser tab per exception is a desktop the user cannot close.</li>
 *   <li><b>Bounded work.</b> A per-session budget caps how many <em>distinct</em>
 *   bugs will interrupt the user; past it, reports are still captured to the spool
 *   silently. Capture is cheap and local, so reliability does not depend on the
 *   budget, only the noise does.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 *
 * <p>{@link #report} is synchronous and does disk I/O - it is the path a
 * {@code fatal} failure must take, because the JVM may exit before a queued task
 * ever runs and a lost report is the entire point of the tool lost.
 * {@link #reportAsync} queues the same work on one daemon thread and returns
 * immediately, and is what a logger or the EDT must use. Both paths funnel into
 * the same {@code doReport}, so the signature accounting cannot diverge between
 * them.</p>
 *
 * <p>The class logs at {@code WARNING} and below only: {@link LogHandler} raises
 * the crash dialog on a {@code SEVERE} record, and a severe record from the
 * reporter would re-enter the reporter through that handler.</p>
 */
public final class CrashReporter {

    /** {@code false} turns the whole pipeline off; the desktop then behaves as before. */
    public static final String ENABLED_PROPERTY = "lg.crashreport.enabled";

    /** Overrides the tracker, for a fork or a downstream build. */
    public static final String REPO_PROPERTY = "lg.crashreport.repo";

    /** Comma-separated labels applied to the filed issue. */
    public static final String LABELS_PROPERTY = "lg.crashreport.labels";

    /** The login notified by GitHub when the issue is created - the "mail the admin" answer. */
    public static final String ASSIGNEE_PROPERTY = "lg.crashreport.assignee";

    /** How many distinct bugs may interrupt a user in one session. */
    public static final String MAX_PER_SESSION_PROPERTY = "lg.crashreport.maxPerSession";

    /** The default interruption budget. */
    public static final int DEFAULT_MAX_PER_SESSION = 8;

    /** The label every automatic report carries, so the queue can filter on origin. */
    public static final String DEFAULT_LABEL = "crash";

    private static final Logger LOG = Logger.getLogger("lg.crashreport");

    /** What a report does once it exists: hand the link to a delivery channel. */
    @FunctionalInterface
    public interface Sink {
        /**
         * Delivers one report.
         *
         * @param report  the finished, redacted report
         * @param prefill the link that files it, and what the budget dropped
         * @return a one-line note on what happened, for the log and the tests
         */
        String deliver(CrashReport report, IssuePrefill.PrefilledUrl prefill);
    }

    /**
     * The result of one capture, always returned, even when everything failed.
     *
     * @param report      the report that was built
     * @param prefill     the link that would file it, {@code null} when the report
     *                    could not be built at all
     * @param spoolFile   where the full text was kept, {@code null} when the spool
     *                    could not be written
     * @param occurrences sightings of this signature in this session
     * @param duplicate   true when this signature was already seen - nothing is
     *                    opened for it, by design
     * @param notified    true when the sink was asked to deliver
     * @param note        what the delivery did, or why it did not
     */
    public record Outcome(CrashReport report, IssuePrefill.PrefilledUrl prefill,
                          Path spoolFile, int occurrences, boolean duplicate,
                          boolean notified, String note) {

        /** @return a capture that failed before it could build anything */
        static Outcome failed(String why) {
            return new Outcome(null, null, null, 0, false, false, why);
        }
    }

    /**
     * The default sink: hands the prefilled link to the desktop's URL opener, which
     * is what makes a crash "open a ticket automatically". Nothing is POSTed; the
     * browser shows a half-written issue that the reporter can read, edit and
     * either submit or abandon - the review step an unattended upload would skip.
     */
    public static final Sink BROWSER_SINK = (report, prefill) -> {
        try {
            boolean opened = org.jdesktop.lg3d.utils.system.Opener.openUri(prefill.url());
            return opened ? "the issue form was opened in the browser"
                    : "no browser could be opened; the link is in the log and the spool";
        } catch (Throwable t) {
            // Headless, a security manager, a missing xdg-open: the report still
            // exists on disk, so say what failed instead of propagating.
            return "the browser could not be opened (" + t + "); the link is in the spool";
        }
    };

    /** A sink that delivers nothing - the report is captured and spooled only. */
    public static final Sink SILENT_SINK = (report, prefill) -> "delivery suppressed";

    private final CrashStore store;
    private final IssuePrefill.Target target;
    private final Sink sink;
    private final int maxPerSession;

    /** Sightings per signature, this session. {@code Integer}, not int, for merge. */
    private final Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();

    /** Distinct bugs already allowed to interrupt the user. */
    private final AtomicInteger notifiedSignatures = new AtomicInteger();

    /** Created on first use, so a reporter that is never triggered costs no thread. */
    private volatile ExecutorService asyncQueue;

    private static volatile CrashReporter shared;

    /**
     * Builds a reporter.
     *
     * @param store         where reports are kept
     * @param target        which tracker, with which labels and assignee
     * @param sink          how a link is delivered; {@link #SILENT_SINK} captures only
     * @param maxPerSession distinct bugs allowed to interrupt a session, at least 1
     */
    public CrashReporter(CrashStore store, IssuePrefill.Target target,
                         Sink sink, int maxPerSession) {
        this.store = (store != null) ? store : CrashStore.defaultStore();
        this.target = (target != null) ? target : IssuePrefill.Target.theDefault();
        this.sink = (sink != null) ? sink : BROWSER_SINK;
        this.maxPerSession = Math.max(1, maxPerSession);
    }

    /**
     * The desktop-wide reporter, configured from the {@code lg.crashreport.*}
     * properties on first use.
     *
     * @return the shared instance
     */
    public static CrashReporter shared() {
        CrashReporter current = shared;
        if (current == null) {
            synchronized (CrashReporter.class) {
                current = shared;
                if (current == null) {
                    current = fromProperties();
                    shared = current;
                }
            }
        }
        return current;
    }

    /**
     * Replaces or clears the shared instance. Hosts - a test, or the 2D desktop's
     * Bug Reporter panel wanting a silent archive - use this instead of a property,
     * because a test cannot set a system property before a static initialiser that
     * already ran.
     *
     * @param reporter the new instance, {@code null} to fall back to properties
     */
    public static void setShared(CrashReporter reporter) {
        synchronized (CrashReporter.class) {
            shared = reporter;
        }
    }

    /** @return a reporter reading every {@code lg.crashreport.*} property now */
    public static CrashReporter fromProperties() {
        return new CrashReporter(CrashStore.defaultStore(), targetFromProperties(),
                BROWSER_SINK, intProperty(MAX_PER_SESSION_PROPERTY, DEFAULT_MAX_PER_SESSION));
    }

    /**
     * The tracker target from the properties, with the crash label always present:
     * a report that arrives unlabelled cannot be separated from a hand-written
     * issue, and the queue's first question is which ones a machine captured.
     *
     * @return the configured target
     */
    public static IssuePrefill.Target targetFromProperties() {
        String repo = property(REPO_PROPERTY, IssuePrefill.DEFAULT_REPO);
        List<String> labels = new ArrayList<>();
        String configured = System.getProperty(LABELS_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            for (String label : configured.split(",")) {
                if (!label.isBlank()) {
                    labels.add(label.trim());
                }
            }
        }
        if (!labels.contains(DEFAULT_LABEL)) {
            labels.add(DEFAULT_LABEL);
        }
        return new IssuePrefill.Target(repo, labels,
                property(ASSIGNEE_PROPERTY, ""));
    }

    /**
     * @return true when the pipeline is switched on; anything but an explicit
     *         {@code false} means on, because a broken property must not silently
     *         stop bug reports - the desktop has been without this tool for twenty
     *         years, and a typo is not a reason to go back to that.
     */
    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "true").trim());
    }

    /**
     * Captures a failure and returns once everything is on disk. Use this when the
     * process may not outlive a queued task.
     *
     * @param thrown the failure, may be {@code null}; when it is and there is a
     *               text to report, use the overload that takes one
     * @param kind   how it was caught
     * @param fatal  whether the desktop could not continue
     * @param source the component that caught it, for the report footer
     * @return the outcome; never {@code null}, never throws
     */
    public Outcome report(Throwable thrown, CrashReport.Kind kind, boolean fatal, String source) {
        return report(thrown, null, kind, fatal, source);
    }

    /**
     * Captures a failure that has no throwable, only a text - the {@code SEVERE}
     * log record that says "cannot open {0}" and throws nothing. Without this the
     * desktop's most common complaint would be filed as an empty report.
     *
     * @param thrown  the failure, may be {@code null}
     * @param message the text to report when there is no throwable; ignored when
     *                there is one, since the throwable already carries its own
     * @param kind    how it was caught
     * @param fatal   whether the desktop could not continue
     * @param source  the component that caught it
     * @return the outcome; never {@code null}, never throws
     */
    public Outcome report(Throwable thrown, String message, CrashReport.Kind kind,
                          boolean fatal, String source) {
        if (!isEnabled()) {
            // Stated in the return value rather than logged as a warning: a
            // deliberate kill switch is not a malfunction, and this method can be
            // on a dying thread where a second log line is the last thing it does.
            return new Outcome(null, null, null, 0, false, false,
                    ENABLED_PROPERTY + "=false, nothing captured");
        }
        try {
            return doReport(thrown, message, kind, fatal, source);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "the crash report could not be captured", t);
            return Outcome.failed("capture failed: " + t);
        }
    }

    /**
     * Captures off the calling thread. This is the path an {@code SEVERE} log
     * record and the EDT take: file I/O and URL building never happen there, and
     * the caller is not waiting on a browser.
     *
     * @param thrown the failure, may be {@code null}
     * @param kind   how it was caught
     * @param fatal  whether the desktop could not continue
     * @param source the component that caught it
     * @return a handle on the queued capture, {@code null} when the thread pool
     *         could not be created - the report is then simply lost, which is the
     *         only loss this method accepts
     */
    public Future<Outcome> reportAsync(Throwable thrown, CrashReport.Kind kind,
                                       boolean fatal, String source) {
        if (!isEnabled()) {
            return null;
        }
        try {
            return queue().submit(() -> report(thrown, kind, fatal, source));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "the crash report could not be queued", t);
            return null;
        }
    }

    /**
     * Captures off the calling thread and hands the outcome back when it is ready.
     * This is what the error dialog uses: it must appear the instant a severe
     * record is logged, while the prefilled link it offers arrives a few milliseconds
     * later from the reporter thread - and a dialog that waited on disk I/O would
     * stall the EDT on a machine that is already failing.
     *
     * @param thrown  the failure, may be {@code null}
     * @param kind    how it was caught
     * @param fatal   whether the desktop could not continue
     * @param source  the component that caught it
     * @param onReady receives the outcome on the reporter thread; exceptions inside
     *                it are swallowed, since a broken listener must not lose the
     *                report it was listening for
     * @return a handle on the queued capture, {@code null} when it could not be
     *         queued
     */
    public Future<Outcome> capture(Throwable thrown, CrashReport.Kind kind, boolean fatal,
                                  String source, Consumer<Outcome> onReady) {
        return capture(thrown, null, kind, fatal, source, onReady);
    }

    /**
     * Queues a capture that may carry only a text, and calls the listener back with
     * the outcome.
     *
     * @param thrown  the failure, may be {@code null}
     * @param message the text to report when there is no throwable
     * @param kind    how it was caught
     * @param fatal   whether the desktop could not continue
     * @param source  the component that caught it
     * @param onReady receives the outcome on the reporter thread; exceptions inside
     *                it are swallowed
     * @return a handle on the queued capture, {@code null} when it could not be
     *         queued
     */
    public Future<Outcome> capture(Throwable thrown, String message, CrashReport.Kind kind,
                                  boolean fatal, String source, Consumer<Outcome> onReady) {
        if (!isEnabled()) {
            return null;
        }
        try {
            return queue().submit(() -> {
                Outcome outcome = report(thrown, message, kind, fatal, source);
                if (onReady != null) {
                    try {
                        onReady.accept(outcome);
                    } catch (Throwable listenerFailed) {
                        LOG.log(Level.WARNING,
                                "the crash report listener failed; the report is spooled",
                                listenerFailed);
                    }
                }
                return outcome;
            });
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "the crash report could not be queued", t);
            return null;
        }
    }

    /**
     * Captures a report assembled by hand - the Bug Reporter panel's path, where a
     * person wrote the steps and chose the severity, so this method only spools,
     * links and delivers. It is deliberately <em>not</em> gated by
     * {@value #ENABLED_PROPERTY}: that switch exists to stop the desktop nagging a
     * user, and a person who pressed "File this issue" has already answered that.
     *
     * @param report the finished report
     * @return the outcome
     */
    public Outcome report(CrashReport report) {
        try {
            return finish(report, true);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "the hand-written report could not be captured", t);
            return Outcome.failed("capture failed: " + t);
        }
    }

    /** @return the spool, so an archive view can list and re-file stored reports */
    public CrashStore store() {
        return store;
    }

    /** @return where reports go */
    public IssuePrefill.Target target() {
        return target;
    }

    /**
     * Builds the prefilled link for a report without capturing or delivering it -
     * what an "Open on GitHub" button needs when the report already exists.
     *
     * @param report the report
     * @return the link
     */
    public IssuePrefill.PrefilledUrl prefill(CrashReport report) {
        return IssuePrefill.url(report, target);
    }

    /**
     * Releases the daemon thread. Tests call it so a suite does not leak a thread
     * per reporter; the desktop never does, since the queue dies with the JVM.
     */
    public void shutdown() {
        ExecutorService queue = asyncQueue;
        if (queue != null) {
            queue.shutdownNow();
            asyncQueue = null;
        }
    }

    // ---------------------------------------------------------------- implementation

    private Outcome doReport(Throwable thrown, String message, CrashReport.Kind kind,
                             boolean fatal, String source) {
        // Counted before the report exists: a signature is an identity, and the
        // identity must be settled even if building the document fails half way.
        CrashReport report = (thrown != null)
                ? CrashReport.of(thrown, kind, fatal, source)
                : CrashReport.builder().kind(kind)
                        .severity(CrashReport.Severity.derive(fatal))
                        .message(message)
                        .reporter(source)
                        .build();
        // Not explicit: nobody asked for this report, the desktop caught it. Passing
        // true here would hand the noise rules to the automatic path as well, and a
        // crash loop - the exact case the signature exists for - would then open one
        // browser tab per exception and a budget of eight would never be reached.
        return finish(report, false);
    }

    /**
     * The shared tail of every path: spool, then decide whether to deliver.
     *
     * @param report   the finished report
     * @param explicit true when a person asked for this - which overrides every
     *                 noise rule below, since suppressing a report someone clicked
     *                 is the one outcome that would make the tool untrustworthy
     */
    private Outcome finish(CrashReport report, boolean explicit) {
        CrashStore.Stored stored = store.store(report);
        String signature = report.signature();
        int inSession = seen.computeIfAbsent(signature, k -> new AtomicInteger())
                .incrementAndGet();
        boolean firstSighting = inSession == 1;

        IssuePrefill.PrefilledUrl prefill = IssuePrefill.url(report, target);
        boolean delivered = false;
        String note;
        if (explicit) {
            delivered = true;
            note = sink.deliver(report, prefill);
        } else if (!firstSighting) {
            note = "already reported this session; counted " + inSession + " times";
        } else if (stored.duplicate()) {
            // A spool hit means this bug was reported in an earlier session and is
            // still on the archive. Opening a browser again for it would produce the
            // duplicate issue that a signature exists to prevent; the user who wants
            // to re-file does that from the Bug Reporter's archive, deliberately.
            note = "already in the report archive; nothing opened";
        } else if (notifiedSignatures.get() >= maxPerSession) {
            note = "the per-session limit of " + maxPerSession
                    + " reports was reached; the report is spooled only";
        } else if (notifiedSignatures.incrementAndGet() > maxPerSession) {
            // Two threads can pass the check above at once; the counter decides, so
            // exactly one of them delivers and the loser re-reads as over budget.
            notifiedSignatures.decrementAndGet();
            note = "the per-session limit of " + maxPerSession
                    + " reports was reached; the report is spooled only";
        } else {
            delivered = true;
            note = sink.deliver(report, prefill);
        }
        LOG.log(Level.INFO, "crash report {0} signature {1} seen {2}x - {3}",
                new Object[] {report.id(), signature, String.valueOf(inSession), note});
        return new Outcome(report, prefill, stored.markdown(), inSession,
                !firstSighting || stored.duplicate(), delivered, note);
    }

    private ExecutorService queue() {
        ExecutorService queue = asyncQueue;
        if (queue == null || queue.isShutdown()) {
            synchronized (this) {
                queue = asyncQueue;
                if (queue == null || queue.isShutdown()) {
                    // One daemon thread: reports are small and serialising them keeps
                    // a crash loop from spawning a thread per exception, and daemon
                    // because a reporter must never be what holds the JVM open.
                    queue = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "lg-crash-reporter");
                        thread.setDaemon(true);
                        return thread;
                    });
                    asyncQueue = queue;
                }
            }
        }
        return queue;
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty(name);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            LOG.log(Level.WARNING,
                    name + "='" + value + "' is not a count; using " + fallback);
            return fallback;
        }
    }
}
