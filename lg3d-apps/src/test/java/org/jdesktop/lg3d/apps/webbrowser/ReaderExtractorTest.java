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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link ReaderArticle} and {@link ReaderExtractor}. */
class ReaderExtractorTest {

    @Test
    @DisplayName("ReaderArticle trims, drops blank paragraphs and is immutable")
    void articleValue() {
        ReaderArticle a = new ReaderArticle("  Title  ", " Byline ",
                Arrays.asList("  one  ", "", null, "two"));
        assertEquals("Title", a.getTitle());
        assertEquals("Byline", a.getByline());
        assertEquals(Arrays.asList("one", "two"), a.getParagraphs());
        assertFalse(a.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> a.getParagraphs().add("x"));
        assertTrue(new ReaderArticle(null, null, null).isEmpty());
    }

    @Test
    @DisplayName("parse reads a well-formed extractor JSON blob")
    void parseValid() {
        String json = "{\"title\":\"T\",\"byline\":\"B\","
                + "\"paragraphs\":[\"first para\",\"second para\"]}";
        ReaderArticle a = ReaderExtractor.parse(json);
        assertEquals("T", a.getTitle());
        assertEquals("B", a.getByline());
        assertEquals(2, a.getParagraphs().size());
        assertEquals("first para", a.getParagraphs().get(0));
    }

    @Test
    @DisplayName("parse tolerates null, blank and malformed JSON")
    void parseRobust() {
        assertTrue(ReaderExtractor.parse(null).isEmpty());
        assertTrue(ReaderExtractor.parse("   ").isEmpty());
        assertTrue(ReaderExtractor.parse("not json at all").isEmpty());
        assertTrue(ReaderExtractor.parse("[1,2,3]").isEmpty(),
                "a non-object root yields an empty article");
    }

    @Test
    @DisplayName("parse ignores a missing/blank paragraphs array")
    void parseNoParagraphs() {
        assertTrue(ReaderExtractor.parse("{\"title\":\"T\"}").isEmpty());
        assertEquals("T", ReaderExtractor.parse("{\"title\":\"T\"}").getTitle());
    }

    @Test
    @DisplayName("renderHtml lays out the article and links the source")
    void renderArticle() {
        ReaderArticle a = new ReaderArticle("Headline", "Jane Doe",
                Arrays.asList("Body one", "Body two"));
        String html = ReaderExtractor.renderHtml(a, "https://example.com/post");
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<h1>Headline</h1>"));
        assertTrue(html.contains("Jane Doe"));
        assertTrue(html.contains("<p>Body one</p>"));
        assertTrue(html.contains("href=\"https://example.com/post\""));
        assertEquals("text/html", ReaderExtractor.CONTENT_TYPE);
    }

    @Test
    @DisplayName("renderHtml of an empty article shows the friendly fallback")
    void renderEmpty() {
        String html = ReaderExtractor.renderHtml(new ReaderArticle("", "", null),
                "https://example.com");
        assertTrue(html.contains("could not find the main article"));
        assertTrue(html.contains("https://example.com"));
    }

    @Test
    @DisplayName("renderHtml escapes article text and the source URL (XSS-safe)")
    void renderEscapes() {
        ReaderArticle a = new ReaderArticle("<script>x</script>", null,
                Arrays.asList("<img src=q onerror=alert(1)>"));
        String html = ReaderExtractor.renderHtml(a, "https://x/\"><script>alert(1)</script>");
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;img src=q"));
    }

    @Test
    @DisplayName("renderHtml falls back to the host when there is no title")
    void renderFallbackTitle() {
        ReaderArticle a = new ReaderArticle("", "", Arrays.asList("some body text"));
        String html = ReaderExtractor.renderHtml(a, "https://example.com/article");
        assertTrue(html.contains("example.com"), "the host stands in for a missing title");
    }

    @Test
    @DisplayName("the extraction JS is a self-contained IIFE returning JSON")
    void extractScriptShape() {
        assertTrue(ReaderExtractor.EXTRACT_JS.startsWith("(function(){"));
        assertTrue(ReaderExtractor.EXTRACT_JS.contains("JSON.stringify"));
        assertTrue(ReaderExtractor.EXTRACT_JS.trim().endsWith("})()"));
    }
}
