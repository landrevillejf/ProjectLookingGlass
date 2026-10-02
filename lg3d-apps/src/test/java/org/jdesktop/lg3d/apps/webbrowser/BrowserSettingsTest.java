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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link BrowserSettings} bean and its defaults. */
class BrowserSettingsTest {

    @Test
    @DisplayName("the defaults are a usable, private-ish modern browser")
    void defaults() {
        BrowserSettings s = new BrowserSettings();
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE, s.getHomePage());
        assertSame(SearchEngine.DUCKDUCKGO, s.getSearchEngine());
        assertTrue(s.isJavaScriptEnabled());
        assertTrue(s.isCookiesEnabled());
        assertFalse(s.isPrivateBrowsing());
        assertTrue(s.isRestoreSession());
        assertEquals(BrowserSettings.DEFAULT_HISTORY_LIMIT, s.getHistoryLimit());
        assertEquals(BrowserSettings.DEFAULT_ZOOM, s.getZoom());
    }

    @Test
    @DisplayName("a blank home page falls back to the default")
    void homePageFallback() {
        BrowserSettings s = new BrowserSettings();
        s.setHomePage("   ");
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE, s.getHomePage());
        s.setHomePage(null);
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE, s.getHomePage());
        s.setHomePage("  https://example.com  ");
        assertEquals("https://example.com", s.getHomePage());
    }

    @Test
    @DisplayName("the search engine round-trips through its stored name")
    void searchEngineByName() {
        BrowserSettings s = new BrowserSettings();
        s.setSearchEngine(SearchEngine.BING);
        assertEquals("BING", s.getSearchEngineName());
        assertSame(SearchEngine.BING, s.getSearchEngine());
        s.setSearchEngine(null);
        assertSame(SearchEngine.DUCKDUCKGO, s.getSearchEngine());
        s.setSearchEngineName("not-a-real-engine");
        assertSame(SearchEngine.DUCKDUCKGO, s.getSearchEngine(),
                "an unknown persisted name degrades to the default");
        s.setSearchEngineName("");
        assertEquals(SearchEngine.DUCKDUCKGO.name(), s.getSearchEngineName());
    }

    @Test
    @DisplayName("non-positive history limit and zoom fall back to defaults")
    void guardedNumbers() {
        BrowserSettings s = new BrowserSettings();
        s.setHistoryLimit(0);
        assertEquals(BrowserSettings.DEFAULT_HISTORY_LIMIT, s.getHistoryLimit());
        s.setZoom(-1.0d);
        assertEquals(BrowserSettings.DEFAULT_ZOOM, s.getZoom());
        s.setZoom(1.5d);
        assertEquals(1.5d, s.getZoom());
    }

    @Test
    @DisplayName("copy produces an independent equal-valued instance")
    void copyIsIndependent() {
        BrowserSettings s = new BrowserSettings();
        s.setHomePage("https://example.com");
        s.setSearchEngine(SearchEngine.GOOGLE);
        s.setPrivateBrowsing(true);
        s.setZoom(1.25d);
        BrowserSettings copy = s.copy();
        assertNotSame(s, copy);
        assertEquals("https://example.com", copy.getHomePage());
        assertSame(SearchEngine.GOOGLE, copy.getSearchEngine());
        assertTrue(copy.isPrivateBrowsing());
        assertEquals(1.25d, copy.getZoom());
        copy.setHomePage("https://changed.com");
        assertEquals("https://example.com", s.getHomePage(), "the original is untouched");
    }
}
