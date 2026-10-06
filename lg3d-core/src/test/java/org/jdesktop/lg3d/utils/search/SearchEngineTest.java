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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end, headless tests for {@link SearchEngine} over a temp file tree. */
class SearchEngineTest {

    /** Builds a small, predictable tree under {@code root} and returns it. */
    private static Path buildTree(Path root) throws IOException {
        Files.writeString(root.resolve("alpha.txt"), "hello world\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("beta.java"), "a needle in java\n", StandardCharsets.UTF_8);
        Files.write(root.resolve("bin.dat"), new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, 9});
        Files.writeString(root.resolve(".hidden.txt"), "secret needle\n", StandardCharsets.UTF_8);
        Path sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("gamma.txt"), "g\n", StandardCharsets.UTF_8);
        Files.writeString(sub.resolve("delta.log"), "d\n", StandardCharsets.UTF_8);
        return root;
    }

    private static List<String> names(List<SearchMatch> matches) {
        List<String> out = new ArrayList<>();
        for (SearchMatch m : matches) {
            out.add(m.name());
        }
        Collections.sort(out);
        return out;
    }

    @Test
    @DisplayName("a name substring search finds the file and reports it as a file")
    void nameSubstring(@TempDir Path root) throws Exception {
        buildTree(root);
        List<SearchMatch> hits = new SearchEngine(2).searchAll(
                SearchQuery.builder().addRoot(root).namePattern("alpha").build());
        assertEquals(1, hits.size());
        assertEquals("alpha.txt", hits.get(0).name());
        assertFalse(hits.get(0).directory());
        assertTrue(hits.get(0).path().isAbsolute());
        assertTrue(hits.get(0).score() > 0);
    }

    @Test
    @DisplayName("kind FILES reports only files, kind DIRECTORIES only folders")
    void kindFilter(@TempDir Path root) throws Exception {
        buildTree(root);
        SearchEngine engine = new SearchEngine(2);
        List<String> files = names(engine.searchAll(SearchQuery.builder()
                .addRoot(root).kind(SearchQuery.Kind.FILES).build()));
        // alpha, beta, bin, gamma, delta (hidden excluded by default)
        assertEquals(List.of("alpha.txt", "beta.java", "bin.dat", "delta.log", "gamma.txt"), files);

        List<String> dirs = names(engine.searchAll(SearchQuery.builder()
                .addRoot(root).kind(SearchQuery.Kind.DIRECTORIES).build()));
        assertEquals(List.of("sub"), dirs);
    }

    @Test
    @DisplayName("hidden files are excluded by default and included on request")
    void hiddenFilter(@TempDir Path root) throws Exception {
        buildTree(root);
        SearchEngine engine = new SearchEngine(2);
        assertFalse(names(engine.searchAll(SearchQuery.builder()
                .addRoot(root).namePattern("hidden").build())).contains(".hidden.txt"));
        assertTrue(names(engine.searchAll(SearchQuery.builder()
                .addRoot(root).namePattern("hidden").includeHidden(true).build()))
                .contains(".hidden.txt"));
    }

    @Test
    @DisplayName("a glob name search matches by extension")
    void globSearch(@TempDir Path root) throws Exception {
        buildTree(root);
        List<String> txt = names(new SearchEngine(2).searchAll(SearchQuery.builder()
                .addRoot(root).nameMode(SearchQuery.NameMode.GLOB).namePattern("*.txt").build()));
        assertEquals(List.of("alpha.txt", "gamma.txt"), txt);
    }

    @Test
    @DisplayName("content search greps text, skips binaries and carries hits")
    void contentSearch(@TempDir Path root) throws Exception {
        buildTree(root);
        List<SearchMatch> hits = new SearchEngine(2).searchAll(SearchQuery.builder()
                .addRoot(root).namePattern("").contentSearch(true).contentPattern("needle")
                .includeHidden(true).build());
        List<String> found = names(hits);
        // beta.java and .hidden.txt contain "needle"; bin.dat has it but is binary
        assertTrue(found.contains("beta.java"));
        assertTrue(found.contains(".hidden.txt"));
        assertFalse(found.contains("bin.dat"), "a binary file is never content-matched");
        SearchMatch beta = hits.stream()
                .filter(m -> m.name().equals("beta.java")).findFirst().orElseThrow();
        assertTrue(beta.hasContentHits());
        assertEquals(1, beta.hits().get(0).line());
        assertTrue(beta.hits().get(0).snippet().contains("needle"));
    }

    @Test
    @DisplayName("size bounds filter files but never folders")
    void sizeFilter(@TempDir Path root) throws Exception {
        buildTree(root);
        Files.writeString(root.resolve("big.txt"), "x".repeat(5000), StandardCharsets.UTF_8);
        List<String> big = names(new SearchEngine(2).searchAll(SearchQuery.builder()
                .addRoot(root).minSize(OptionalLong.of(1000)).build()));
        assertTrue(big.contains("big.txt"));
        assertFalse(big.contains("gamma.txt"));
        // Folders survive a min-size filter (their size is not meaningful).
        assertTrue(big.contains("sub"));
    }

    @Test
    @DisplayName("a recency filter keeps freshly created files")
    void dateFilter(@TempDir Path root) throws Exception {
        buildTree(root);
        List<String> recent = names(new SearchEngine(2).searchAll(SearchQuery.builder()
                .addRoot(root).modifiedWithinDays(OptionalInt.of(1)).build()));
        assertTrue(recent.contains("alpha.txt"));
    }

    @Test
    @DisplayName("the result cap stops the walk and never overshoots")
    void maxResultsCap(@TempDir Path root) throws Exception {
        buildTree(root);
        List<SearchMatch> out = Collections.synchronizedList(new ArrayList<>());
        SearchHandle handle = new SearchEngine(4).search(
                SearchQuery.builder().addRoot(root).namePattern("").maxResults(2).build(),
                out::add);
        assertTrue(handle.await(10, TimeUnit.SECONDS), "the capped walk finishes");
        assertTrue(handle.isDone());
        assertTrue(out.size() <= 2, "never emits more than the cap, got " + out.size());
    }

    @Test
    @DisplayName("cancelling a running search finishes the handle without error")
    void cancel(@TempDir Path root) throws Exception {
        buildTree(root);
        List<SearchMatch> out = Collections.synchronizedList(new ArrayList<>());
        SearchHandle handle = new SearchEngine(2).search(
                SearchQuery.builder().addRoot(root).namePattern("").build(), out::add);
        handle.cancel();
        assertTrue(handle.isCancelled());
        assertTrue(handle.await(10, TimeUnit.SECONDS));
        assertTrue(handle.isDone());
    }

    @Test
    @DisplayName("a null query or sink yields an already-finished, silent handle")
    void degenerateInputs() throws Exception {
        SearchEngine engine = new SearchEngine(1);
        SearchHandle h1 = engine.search(null, m -> { });
        assertTrue(h1.isDone());
        assertEquals(0, h1.getMatchedCount());

        SearchHandle h2 = engine.search(SearchQuery.builder().build(), null);
        assertTrue(h2.isDone());
    }

    @Test
    @DisplayName("the onDone callback fires exactly once on completion")
    void onDoneFires(@TempDir Path root) throws Exception {
        buildTree(root);
        List<Integer> fired = Collections.synchronizedList(new ArrayList<>());
        SearchHandle handle = new SearchEngine(2).search(
                SearchQuery.builder().addRoot(root).namePattern("alpha").build(),
                m -> { }, () -> fired.add(1));
        assertTrue(handle.await(10, TimeUnit.SECONDS));
        assertEquals(1, fired.size());
    }

    @Test
    @DisplayName("symlinked folders are not descended into, so loops cannot hang")
    void symlinkLoopIsSafe(@TempDir Path root) throws Exception {
        buildTree(root);
        Path real = Files.createDirectory(root.resolve("real"));
        Files.writeString(real.resolve("inside.txt"), "x\n", StandardCharsets.UTF_8);
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException ex) {
            assumeTrue(false, "symlinks unsupported on this filesystem");
        }
        List<SearchMatch> hits = new SearchEngine(2).searchAll(
                SearchQuery.builder().addRoot(root).namePattern("inside").build());
        assertEquals(1, hits.size(), "the file is found once, via the real folder only");
        assertEquals(real.resolve("inside.txt").toAbsolutePath(), hits.get(0).path());
    }

    @Test
    @DisplayName("searchAll returns matches ranked by descending relevance")
    void ranking(@TempDir Path root) throws Exception {
        buildTree(root);
        Files.writeString(root.resolve("alpha.txt"), "hello\n", StandardCharsets.UTF_8);
        Path deep = Files.createDirectories(root.resolve("a/b/c/d"));
        Files.writeString(deep.resolve("alpha.txt"), "hello\n", StandardCharsets.UTF_8);
        List<SearchMatch> hits = new SearchEngine(2).searchAll(
                SearchQuery.builder().addRoot(root).namePattern("alpha.txt").build());
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).score() >= hits.get(1).score(),
                "results come back best-first");
    }

    @Test
    @DisplayName("the engine reports its worker count and coerces a bad value to one")
    void parallelism() {
        assertEquals(1, new SearchEngine(0).getParallelism());
        assertEquals(3, new SearchEngine(3).getParallelism());
        assertTrue(new SearchEngine().getParallelism() >= 1);
    }
}
