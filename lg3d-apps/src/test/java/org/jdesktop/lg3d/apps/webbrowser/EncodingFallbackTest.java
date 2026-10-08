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
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link EncodingFallback} blank-page helpers. */
class EncodingFallbackTest {

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(buf)) {
            out.write(raw);
        }
        return buf.toByteArray();
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(buf)) {
            out.write(raw);
        }
        return buf.toByteArray();
    }

    // ------------------------------------------------------------------
    // parseProbe
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a well-formed probe result parses into its two counts")
    void parsesCounts() {
        assertArrayEquals(new int[] {3, 0}, EncodingFallback.parseProbe("3:0"));
        assertArrayEquals(new int[] {128, 4096}, EncodingFallback.parseProbe("128:4096"));
    }

    @Test
    @DisplayName("a null / malformed probe result yields the unknown sentinel")
    void rejectsGarbage() {
        assertArrayEquals(new int[] {-1, -1}, EncodingFallback.parseProbe(null));
        assertArrayEquals(new int[] {-1, -1}, EncodingFallback.parseProbe(""));
        assertArrayEquals(new int[] {-1, -1}, EncodingFallback.parseProbe("nocolon"));
        assertArrayEquals(new int[] {-1, -1}, EncodingFallback.parseProbe(":5"));
        assertArrayEquals(new int[] {-1, -1}, EncodingFallback.parseProbe("5:"));
        assertArrayEquals(new int[] {-1, -1}, EncodingFallback.parseProbe("a:b"));
    }

    // ------------------------------------------------------------------
    // isBlankDom
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty skeleton with no text is treated as a discarded body")
    void detectsBlankSkeleton() {
        assertTrue(EncodingFallback.isBlankDom(3, 0));
        assertTrue(EncodingFallback.isBlankDom(4, 0));
        assertTrue(EncodingFallback.isBlankDom(0, 0));
    }

    @Test
    @DisplayName("a populated page, visible text, or an unknown probe is not blank")
    void rejectsRealPages() {
        assertFalse(EncodingFallback.isBlankDom(5, 0));
        assertFalse(EncodingFallback.isBlankDom(3, 12));
        assertFalse(EncodingFallback.isBlankDom(-1, 0));
        assertFalse(EncodingFallback.isBlankDom(-1, -1));
    }

    // ------------------------------------------------------------------
    // decode
    // ------------------------------------------------------------------

    @Test
    @DisplayName("identity, none and a missing encoding pass the body through")
    void identityEncodings() throws IOException {
        byte[] raw = "<html>hi</html>".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(raw, EncodingFallback.decode(raw, null));
        assertArrayEquals(raw, EncodingFallback.decode(raw, ""));
        assertArrayEquals(raw, EncodingFallback.decode(raw, "identity"));
        assertArrayEquals(raw, EncodingFallback.decode(raw, "none"));
        assertArrayEquals(raw, EncodingFallback.decode(raw, "  NONE "));
    }

    @Test
    @DisplayName("gzip and deflate bodies are inflated back to the original")
    void compressedEncodings() throws IOException {
        byte[] raw = "<html>compressed body</html>".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(raw, EncodingFallback.decode(gzip(raw), "gzip"));
        assertArrayEquals(raw, EncodingFallback.decode(gzip(raw), "x-gzip"));
        assertArrayEquals(raw, EncodingFallback.decode(deflate(raw), "deflate"));
    }

    @Test
    @DisplayName("a null body decodes to empty, and an unsupported encoding throws")
    void nullAndUnsupported() throws IOException {
        assertArrayEquals(new byte[0], EncodingFallback.decode(null, "gzip"));
        IOException br = assertThrows(IOException.class,
                () -> EncodingFallback.decode(new byte[] {1, 2, 3}, "br"));
        assertTrue(br.getMessage().contains("br"));
        assertThrows(IOException.class,
                () -> EncodingFallback.decode(new byte[] {1, 2, 3}, "zstd"));
    }

    // ------------------------------------------------------------------
    // charsetFor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the Content-Type charset is honoured, case-insensitively")
    void honoursCharset() {
        assertEquals(StandardCharsets.ISO_8859_1,
                EncodingFallback.charsetFor("text/html; charset=ISO-8859-1"));
        assertEquals(StandardCharsets.UTF_8,
                EncodingFallback.charsetFor("text/html; CHARSET=utf-8"));
        assertEquals(StandardCharsets.UTF_16,
                EncodingFallback.charsetFor("text/plain;charset=\"UTF-16\""));
    }

    @Test
    @DisplayName("an absent, unknown or illegal charset falls back to UTF-8")
    void defaultsToUtf8() {
        assertEquals(StandardCharsets.UTF_8, EncodingFallback.charsetFor(null));
        assertEquals(StandardCharsets.UTF_8, EncodingFallback.charsetFor("text/html"));
        assertEquals(StandardCharsets.UTF_8,
                EncodingFallback.charsetFor("text/html; charset=not-a-real-charset"));
    }

    // ------------------------------------------------------------------
    // injectBaseTag
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the base tag is inserted right after an existing head open tag")
    void insertsAfterHead() {
        String out = EncodingFallback.injectBaseTag(
                "<html><head><title>t</title></head><body>x</body></html>",
                "https://a.com/p");
        assertEquals("<html><head><base href=\"https://a.com/p\"><title>t</title></head>"
                + "<body>x</body></html>", out);
    }

    @Test
    @DisplayName("a head tag with attributes is matched and preserved")
    void headWithAttributes() {
        String out = EncodingFallback.injectBaseTag(
                "<html><head lang=\"en\"><meta charset=\"utf-8\"></head></html>",
                "https://a.com/");
        assertTrue(out.startsWith("<html><head lang=\"en\"><base href=\"https://a.com/\">"));
    }

    @Test
    @DisplayName("with no head, one is synthesised right after the html tag")
    void synthesisesHead() {
        String out = EncodingFallback.injectBaseTag(
                "<html><body>x</body></html>", "https://a.com/");
        assertEquals("<html><head><base href=\"https://a.com/\"></head><body>x</body></html>", out);
    }

    @Test
    @DisplayName("a fragment with no html or head simply gets the base prepended")
    void prependsWhenNoStructure() {
        assertEquals("<base href=\"https://a.com/\"><p>hi</p>",
                EncodingFallback.injectBaseTag("<p>hi</p>", "https://a.com/"));
    }

    @Test
    @DisplayName("an existing base tag is left untouched")
    void keepsExistingBase() {
        String doc = "<html><head><base href=\"https://other.com/\"></head></html>";
        assertEquals(doc, EncodingFallback.injectBaseTag(doc, "https://a.com/"));
    }

    @Test
    @DisplayName("a null document, or a blank base URL, is a safe no-op")
    void noOpCases() {
        assertEquals("", EncodingFallback.injectBaseTag(null, "https://a.com/"));
        assertEquals("<p>x</p>", EncodingFallback.injectBaseTag("<p>x</p>", null));
        assertEquals("<p>x</p>", EncodingFallback.injectBaseTag("<p>x</p>", "  "));
    }

    @Test
    @DisplayName("the base URL is HTML-escaped so it cannot inject markup")
    void escapesBaseUrl() {
        String out = EncodingFallback.injectBaseTag(
                "<html><head></head></html>", "https://a.com/?x=1&y=\"2\"");
        assertTrue(out.contains("<base href=\"https://a.com/?x=1&amp;y=&quot;2&quot;\">"));
    }

    // ------------------------------------------------------------------
    // describe
    // ------------------------------------------------------------------

    @Test
    @DisplayName("describe falls back to the type name when there is no message")
    void describeFallback() {
        assertEquals("", EncodingFallback.describe(null));
        assertEquals("boom", EncodingFallback.describe(new IOException("boom")));
        assertEquals("IOException",
                EncodingFallback.describe(new IOException((String) null)));
    }

    @Test
    @DisplayName("the blank element-count ceiling is a small skeleton size")
    void ceilingIsSmall() {
        assertEquals(4, EncodingFallback.MAX_BLANK_ELEMENT_COUNT);
        assertTrue(Charset.forName("UTF-8").equals(StandardCharsets.UTF_8));
    }
}
