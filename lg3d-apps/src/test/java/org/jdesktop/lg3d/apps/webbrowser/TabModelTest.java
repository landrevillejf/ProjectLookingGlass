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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link TabModel}. */
class TabModelTest {

    @Test
    @DisplayName("a new model has no tabs")
    void emptyByDefault() {
        TabModel model = new TabModel();
        assertTrue(model.isEmpty());
        assertEquals(-1, model.getActiveIndex());
        assertNull(model.getActiveTab());
    }

    @Test
    @DisplayName("addTab appends and activates the new tab")
    void addActivates() {
        TabModel model = new TabModel();
        assertEquals(0, model.addTab("A", "https://a.com"));
        assertEquals(1, model.addTab("B", "https://b.com"));
        assertEquals(1, model.getActiveIndex());
        assertEquals("B", model.getActiveTab().getTitle());
        assertEquals(2, model.size());
    }

    @Test
    @DisplayName("selectTab activates an existing tab")
    void select() {
        TabModel model = new TabModel();
        model.addTab("A", "https://a.com");
        model.addTab("B", "https://b.com");
        assertTrue(model.selectTab(0));
        assertEquals(0, model.getActiveIndex());
        assertFalse(model.selectTab(0), "selecting the active tab is a no-op");
        assertFalse(model.selectTab(9), "out-of-range selection is rejected");
    }

    @Test
    @DisplayName("closeTab keeps the active index on a neighbour")
    void closeKeepsNeighbour() {
        TabModel model = new TabModel();
        model.addTab("A", "https://a.com");
        model.addTab("B", "https://b.com");
        model.addTab("C", "https://c.com");
        model.selectTab(1);
        assertTrue(model.closeTab(1));
        assertEquals(1, model.getActiveIndex(), "the following tab slides into place");
        assertEquals("C", model.getActiveTab().getTitle());
        assertFalse(model.closeTab(9));
    }

    @Test
    @DisplayName("closing the last tab leaves the model empty")
    void closeLast() {
        TabModel model = new TabModel();
        model.addTab("A", "https://a.com");
        model.closeTab(0);
        assertTrue(model.isEmpty());
        assertEquals(-1, model.getActiveIndex());
        assertNull(model.getActiveTab());
    }

    @Test
    @DisplayName("moveTab reorders and tracks the active tab")
    void move() {
        TabModel model = new TabModel();
        model.addTab("A", "https://a.com");
        model.addTab("B", "https://b.com");
        model.addTab("C", "https://c.com");
        model.selectTab(0);
        assertTrue(model.moveTab(0, 2));
        assertEquals("A", model.getTab(2).getTitle());
        assertEquals(2, model.getActiveIndex(), "the active tab follows its move");
        assertFalse(model.moveTab(0, 0));
        assertFalse(model.moveTab(5, 1));
    }

    @Test
    @DisplayName("updateActive edits the active tab's title and URL")
    void updateActive() {
        TabModel model = new TabModel();
        model.addTab("A", "https://a.com");
        model.updateActive("A2", "https://a2.com");
        assertEquals("A2", model.getActiveTab().getTitle());
        assertEquals("https://a2.com", model.getActiveTab().getUrl());
        model.updateActive(null, null);
        assertEquals("A2", model.getActiveTab().getTitle(), "null leaves values unchanged");
    }

    @Test
    @DisplayName("a tab's label prefers title, then URL, then a placeholder")
    void tabLabel() {
        TabModel model = new TabModel();
        model.addTab("Title", "https://a.com");
        assertEquals("Title", model.getActiveTab().label());
        model.addTab(null, "https://b.com");
        assertEquals("https://b.com", model.getActiveTab().label());
        model.addTab(null, null);
        assertEquals("New Tab", model.getActiveTab().label());
    }

    @Test
    @DisplayName("list is unmodifiable and getTab bounds-checks")
    void listUnmodifiable() {
        TabModel model = new TabModel();
        model.addTab("A", "https://a.com");
        assertThrows(UnsupportedOperationException.class,
                () -> model.list().add(new TabModel.Tab(99, "x", "y")));
        assertNotNull(model.getTab(0));
        assertNull(model.getTab(5));
        assertNull(model.getTab(-1));
        assertFalse(model.list().isEmpty());
    }
}
