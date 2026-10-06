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
package org.jdesktop.lg3d.apps.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import org.jdesktop.lg3d.utils.search.SearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link SearchPanel}'s pure query-building. Swing components
 * construct fine with {@code java.awt.headless=true} (only top-level windows need
 * a display), so the panel's filter-to-{@link SearchQuery} translation and its
 * list-index mappers are exercised without a desktop.
 */
class SearchPanelTest {

    @Test
    @DisplayName("the panel builds headless and advertises its preferred size")
    void constructs() {
        SearchPanel panel = new SearchPanel();
        assertNotNull(panel);
        assertEquals(new Dimension(SearchPanel.WIDTH_PX, SearchPanel.HEIGHT_PX),
                panel.getPreferredSize());
    }

    @Test
    @DisplayName("a freshly built panel yields a valid default query")
    void defaultQuery() {
        SearchQuery q = new SearchPanel().buildQuery();
        assertFalse(q.getRoots().isEmpty(), "Home is selected by default");
        assertEquals(SearchQuery.NameMode.SUBSTRING, q.getNameMode());
        assertEquals(SearchQuery.Kind.ANY, q.getKind());
        assertEquals("", q.getNamePattern());
        assertFalse(q.isCaseSensitive());
        assertFalse(q.isContentSearch());
    }

    @Test
    @DisplayName("the query text and size bound flow into the built query")
    void queryReflectsFields() {
        SearchPanel panel = new SearchPanel();
        panel.queryField().setText("report");
        panel.minSizeField().setText("1K");
        SearchQuery q = panel.buildQuery();
        assertEquals("report", q.getNamePattern());
        assertTrue(q.getMinSize().isPresent());
        assertEquals(1024L, q.getMinSize().getAsLong());
    }

    @Test
    @DisplayName("a blank size field means no bound")
    void blankSizeIsNoBound() {
        SearchPanel panel = new SearchPanel();
        panel.minSizeField().setText("");
        assertFalse(panel.buildQuery().getMinSize().isPresent());
    }

    @Test
    @DisplayName("the name-mode list index maps to the query enum")
    void nameModeMapping() {
        assertEquals(SearchQuery.NameMode.SUBSTRING, SearchPanel.nameModeFor(0));
        assertEquals(SearchQuery.NameMode.GLOB, SearchPanel.nameModeFor(1));
        assertEquals(SearchQuery.NameMode.REGEX, SearchPanel.nameModeFor(2));
        assertEquals(SearchQuery.NameMode.SUBSTRING, SearchPanel.nameModeFor(99));
        assertEquals(SearchQuery.NameMode.SUBSTRING, SearchPanel.nameModeFor(-1));
    }

    @Test
    @DisplayName("the type list index maps to the query kind")
    void kindMapping() {
        assertEquals(SearchQuery.Kind.ANY, SearchPanel.kindFor(0));
        assertEquals(SearchQuery.Kind.FILES, SearchPanel.kindFor(1));
        assertEquals(SearchQuery.Kind.DIRECTORIES, SearchPanel.kindFor(2));
        assertEquals(SearchQuery.Kind.ANY, SearchPanel.kindFor(99));
        assertEquals(SearchQuery.Kind.ANY, SearchPanel.kindFor(-1));
    }
}
