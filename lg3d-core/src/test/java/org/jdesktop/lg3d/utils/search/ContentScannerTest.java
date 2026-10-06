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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Headless tests for {@link ContentScanner} line matching and file grepping. */
class ContentScannerTest {

    @Test
    @DisplayName("a blank content pattern matches every line")
    void blankLineMatcher() {
        assertTrue(ContentScanner.lineMatcher("", false, false).test("anything"));
        assertTrue(ContentScanner.lineMatcher(null, false, false).test(""));
    }

    @Test
    @DisplayName("substring line matching honours case")
    void substringLineMatcher() {
        Predicate<String> ci = ContentScanner.lineMatcher("needle", false, false);
        Predicate<String> cs = ContentScanner.lineMatcher("needle", false, true);
        assertTrue(ci.test("a NEEDLE here"));
        assertFalse(cs.test("a NEEDLE here"));
        assertTrue(cs.test("a needle here"));
        assertFalse(ci.test(null));
    }

    @Test
    @DisplayName("regex line matching works and an invalid regex matches nothing")
    void regexLineMatcher() {
        assertTrue(ContentScanner.lineMatcher("err(or|ors)", true, false).test("some errors"));
        assertFalse(ContentScanner.lineMatcher("^xyz$", true, false).test("abc"));
        assertFalse(ContentScanner.lineMatcher("(", true, false).test("anything"));
    }

    @Test
    @DisplayName("scanning a text file returns 1-based hits with snippets")
    void scanText(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("notes.txt");
        Files.writeString(f, "alpha\nbeta needle gamma\nno match\nanother needle\n",
                StandardCharsets.UTF_8);
        List<ContentHit> hits = ContentScanner.scan(f,
                ContentScanner.lineMatcher("needle", false, false),
                SearchQuery.MAX_CONTENT_BYTES, 50, 160);
        assertEquals(2, hits.size());
        assertEquals(2, hits.get(0).line());
        assertEquals("beta needle gamma", hits.get(0).snippet());
        assertEquals(4, hits.get(1).line());
    }

    @Test
    @DisplayName("the per-file hit cap is respected")
    void scanCapsHits(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("many.txt");
        Files.writeString(f, "x\n".repeat(100), StandardCharsets.UTF_8);
        List<ContentHit> hits = ContentScanner.scan(f,
                ContentScanner.lineMatcher("x", false, false), 1 << 20, 3, 160);
        assertEquals(3, hits.size());
    }

    @Test
    @DisplayName("a binary file (NUL byte) yields no hits")
    void scanBinary(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("blob.bin");
        Files.write(f, new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, 1, 2});
        assertFalse(ContentScanner.looksTextual(f));
        assertTrue(ContentScanner.scan(f,
                ContentScanner.lineMatcher("needle", false, false),
                SearchQuery.MAX_CONTENT_BYTES, 50, 160).isEmpty());
    }

    @Test
    @DisplayName("a file larger than maxBytes is skipped")
    void scanTooLarge(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("big.txt");
        Files.writeString(f, "needle\n".repeat(1000), StandardCharsets.UTF_8);
        assertTrue(ContentScanner.scan(f,
                ContentScanner.lineMatcher("needle", false, false), 10, 50, 160).isEmpty());
    }

    @Test
    @DisplayName("directories, missing files and null args yield no hits")
    void scanDegenerate(@TempDir Path dir) {
        Predicate<String> any = ContentScanner.lineMatcher("", false, false);
        assertTrue(ContentScanner.scan(dir, any, SearchQuery.MAX_CONTENT_BYTES, 50, 160).isEmpty());
        assertTrue(ContentScanner.scan(dir.resolve("nope.txt"), any,
                SearchQuery.MAX_CONTENT_BYTES, 50, 160).isEmpty());
        assertTrue(ContentScanner.scan(null, any, 1 << 20, 50, 160).isEmpty());
        assertTrue(ContentScanner.scan(dir.resolve("x"), null, 1 << 20, 50, 160).isEmpty());
    }

    @Test
    @DisplayName("non-positive maxBytes/hits/snippet fall back to defaults")
    void scanDefaults(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("d.txt");
        Files.writeString(f, "needle\n", StandardCharsets.UTF_8);
        List<ContentHit> hits = ContentScanner.scan(f,
                ContentScanner.lineMatcher("needle", false, false), 0, 0, 0);
        assertEquals(1, hits.size());
    }

    @Test
    @DisplayName("looksTextual is true for plain text and false for a missing file")
    void looksTextual(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("t.txt");
        Files.writeString(f, "hello", StandardCharsets.UTF_8);
        assertTrue(ContentScanner.looksTextual(f));
        assertFalse(ContentScanner.looksTextual(dir.resolve("missing")));
    }

    @Test
    @DisplayName("snippet trims, caps length and adds an ellipsis")
    void snippetFormatting() {
        assertEquals("a b c", ContentScanner.snippet("  a b c  ", 160));
        assertEquals("", ContentScanner.snippet(null, 160));
        String capped = ContentScanner.snippet("0123456789", 4);
        assertEquals("0123\u2026", capped);
    }
}
