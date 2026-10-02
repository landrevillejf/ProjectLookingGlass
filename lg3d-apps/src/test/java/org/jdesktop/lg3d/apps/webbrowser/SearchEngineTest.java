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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link SearchEngine} registry. */
class SearchEngineTest {

    @Test
    @DisplayName("searchUrl substitutes the encoded terms into the template")
    void searchUrlEncodes() {
        assertEquals("https://duckduckgo.com/?q=hello+world",
                SearchEngine.DUCKDUCKGO.searchUrl("hello world"));
        assertEquals("https://www.google.com/search?q=a%26b",
                SearchEngine.GOOGLE.searchUrl("a&b"));
    }

    @Test
    @DisplayName("a blank query falls back to the engine home (token removed)")
    void blankQuery() {
        String url = SearchEngine.DUCKDUCKGO.searchUrl("   ");
        assertTrue(url.startsWith("https://duckduckgo.com/"), url);
        assertTrue(!url.contains(SearchEngine.QUERY_TOKEN),
                "the placeholder must never survive into the URL");
    }

    @Test
    @DisplayName("fromName resolves by enum or display name, case-insensitively")
    void fromName() {
        assertSame(SearchEngine.GOOGLE, SearchEngine.fromName("google"));
        assertSame(SearchEngine.GOOGLE, SearchEngine.fromName("GOOGLE"));
        assertSame(SearchEngine.BING, SearchEngine.fromName("Bing"));
        assertSame(SearchEngine.DUCKDUCKGO, SearchEngine.fromName(null));
        assertSame(SearchEngine.DUCKDUCKGO, SearchEngine.fromName("not-an-engine"),
                "an unknown name degrades to the default engine");
    }

    @Test
    @DisplayName("every engine has a display name and a templated URL")
    void invariants() {
        for (SearchEngine engine : SearchEngine.values()) {
            assertTrue(!engine.getDisplayName().isBlank());
            assertTrue(engine.getTemplate().contains(SearchEngine.QUERY_TOKEN),
                    engine + " template must contain the query token");
            assertEquals(engine.getDisplayName(), engine.toString());
        }
        assertSame(SearchEngine.DUCKDUCKGO, SearchEngine.values()[0],
                "DuckDuckGo is the first/default engine");
    }
}
