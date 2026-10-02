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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the smart address bar's resolution logic. {@link
 * UrlNormalizer} is a pure static utility, so these run with no display and no
 * JavaFX.
 */
class UrlNormalizerTest {

    @Test
    @DisplayName("blank or null input yields no URL")
    void blankInput() {
        assertNull(UrlNormalizer.normalize(null, SearchEngine.DUCKDUCKGO));
        assertNull(UrlNormalizer.normalize("", SearchEngine.DUCKDUCKGO));
        assertNull(UrlNormalizer.normalize("   ", SearchEngine.DUCKDUCKGO));
    }

    @Test
    @DisplayName("text with a known scheme is passed through unchanged")
    void schemePassthrough() {
        assertEquals("https://example.com/path?q=1",
                UrlNormalizer.normalize("https://example.com/path?q=1", SearchEngine.DUCKDUCKGO));
        assertEquals("http://example.com",
                UrlNormalizer.normalize("http://example.com", SearchEngine.DUCKDUCKGO));
        assertEquals("about:blank",
                UrlNormalizer.normalize("about:blank", SearchEngine.DUCKDUCKGO));
        assertEquals("file:///tmp/x.html",
                UrlNormalizer.normalize("file:///tmp/x.html", SearchEngine.DUCKDUCKGO));
    }

    @Test
    @DisplayName("a host-like token becomes an https URL")
    void hostLikeBecomesHttps() {
        assertEquals("https://example.com",
                UrlNormalizer.normalize("example.com", SearchEngine.DUCKDUCKGO));
        assertEquals("https://localhost:8080",
                UrlNormalizer.normalize("localhost:8080", SearchEngine.DUCKDUCKGO));
        assertEquals("https://sub.domain.org/a/b",
                UrlNormalizer.normalize("sub.domain.org/a/b", SearchEngine.DUCKDUCKGO));
    }

    @Test
    @DisplayName("a search phrase routes to the configured engine")
    void searchPhrase() {
        assertEquals("https://duckduckgo.com/?q=hello+world",
                UrlNormalizer.normalize("hello world", SearchEngine.DUCKDUCKGO));
        assertEquals("https://www.google.com/search?q=kittens",
                UrlNormalizer.normalize("kittens", SearchEngine.GOOGLE));
    }

    @Test
    @DisplayName("a null engine defaults to DuckDuckGo")
    void nullEngineDefaults() {
        assertEquals("https://duckduckgo.com/?q=terms",
                UrlNormalizer.normalize("terms", null));
    }

    @Test
    @DisplayName("isProbablyUrl recognises hosts and schemes, not phrases")
    void isProbablyUrl() {
        assertTrue(UrlNormalizer.isProbablyUrl("example.com"));
        assertTrue(UrlNormalizer.isProbablyUrl("https://example.com"));
        assertTrue(UrlNormalizer.isProbablyUrl("localhost"));
        assertFalse(UrlNormalizer.isProbablyUrl("a search phrase"));
        assertFalse(UrlNormalizer.isProbablyUrl("kittens"));
        assertFalse(UrlNormalizer.isProbablyUrl(null));
        assertFalse(UrlNormalizer.isProbablyUrl("  "));
    }

    @Test
    @DisplayName("isSecure is true only for https")
    void isSecure() {
        assertTrue(UrlNormalizer.isSecure("https://example.com"));
        assertFalse(UrlNormalizer.isSecure("http://example.com"));
        assertFalse(UrlNormalizer.isSecure(null));
    }

    @Test
    @DisplayName("hostOf extracts a lower-cased host or empty")
    void hostOf() {
        assertEquals("example.com", UrlNormalizer.hostOf("https://Example.COM/path"));
        assertEquals("", UrlNormalizer.hostOf("not a url"));
        assertEquals("", UrlNormalizer.hostOf(null));
    }

    @Test
    @DisplayName("viewSourceOf prefixes view-source: idempotently")
    void viewSourceOf() {
        assertEquals("view-source:https://example.com",
                UrlNormalizer.viewSourceOf("https://example.com"));
        assertEquals("view-source:https://example.com",
                UrlNormalizer.viewSourceOf("view-source:https://example.com"));
        assertNull(UrlNormalizer.viewSourceOf(""));
        assertNull(UrlNormalizer.viewSourceOf(null));
    }
}
