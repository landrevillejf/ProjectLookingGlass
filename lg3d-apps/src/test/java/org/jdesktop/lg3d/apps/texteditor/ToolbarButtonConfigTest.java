/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.ToolbarButtonConfig.DisplayMode;
import org.jdesktop.lg3d.apps.texteditor.ToolbarButtonConfig.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure {@link ToolbarButtonConfig} model: parsing /
 * serialising the {@code '|'}-joined {@code id:mode} format, unique-by-id add,
 * remove, clamped move, mode update and the render-time stale-id resolution.
 * No panel, preference store or AWT surface is touched.
 */
class ToolbarButtonConfigTest {

    @Test
    @DisplayName("an empty string parses to the empty default configuration")
    void parseEmpty() {
        assertTrue(ToolbarButtonConfig.parse("").isEmpty());
        assertTrue(ToolbarButtonConfig.parse(null).isEmpty());
        assertTrue(ToolbarButtonConfig.parse("   ").isEmpty());
        assertEquals("", ToolbarButtonConfig.defaults().toConfigString());
    }

    @Test
    @DisplayName("id:mode entries round-trip through the config string")
    void roundTrip() {
        ToolbarButtonConfig cfg = ToolbarButtonConfig.defaults();
        cfg.add("lg3d.text-tools/sort-az", DisplayMode.ICON);
        cfg.add("lg3d.snippets/ins-uuid", DisplayMode.TEXT);
        cfg.add("lg3d.todo/scan-todo");  // default ICON_TEXT

        String text = cfg.toConfigString();
        ToolbarButtonConfig copy = ToolbarButtonConfig.parse(text);
        assertEquals(cfg, copy);
        assertEquals(3, copy.size());
        assertEquals("lg3d.text-tools/sort-az", copy.get(0).id());
        assertEquals(DisplayMode.ICON, copy.get(0).mode());
        assertEquals(DisplayMode.TEXT, copy.get(1).mode());
        assertEquals(DisplayMode.ICON_TEXT, copy.get(2).mode());
    }

    @Test
    @DisplayName("malformed entries and unknown modes degrade gracefully")
    void junkTolerated() {
        ToolbarButtonConfig cfg = ToolbarButtonConfig.parse("  |a:BOGUS|:only-mode|b");
        // a gets the fallback mode, the mode-only token is skipped, b keeps default
        assertEquals(2, cfg.size());
        assertEquals(DisplayMode.ICON_TEXT, cfg.get(0).mode());
        assertEquals("b", cfg.get(1).id());
    }

    @Test
    @DisplayName("adding an existing id updates its mode instead of duplicating")
    void uniqueById() {
        ToolbarButtonConfig cfg = ToolbarButtonConfig.defaults();
        cfg.add("x", DisplayMode.ICON);
        cfg.add("x", DisplayMode.TEXT);
        cfg.add("  ");           // blank ignored
        assertEquals(1, cfg.size());
        assertEquals(DisplayMode.TEXT, cfg.get(0).mode());
    }

    @Test
    @DisplayName("move reorders with clamping at both ends")
    void move() {
        ToolbarButtonConfig cfg = ToolbarButtonConfig.defaults();
        cfg.add("a");
        cfg.add("b");
        cfg.add("c");
        assertTrue(cfg.move(2, -1));
        assertEquals(List.of("a", "c", "b"), cfg.ids());
        assertFalse(cfg.move(0, -1), "up on the first is a no-op");
        assertFalse(cfg.move(2, 1), "down on the last is a no-op");
        assertFalse(cfg.move(5, 1), "out of range");
        assertEquals(List.of("a", "c", "b"), cfg.ids());
    }

    @Test
    @DisplayName("remove, get and mode update behave on the ordered list")
    void removeAndGet() {
        ToolbarButtonConfig cfg = ToolbarButtonConfig.defaults();
        cfg.add("a");
        cfg.add("b");
        cfg.remove("a");
        cfg.remove("missing");
        assertEquals(List.of("b"), cfg.ids());
        assertTrue(cfg.contains("b"));
        assertFalse(cfg.contains("a"));
        assertNull(cfg.get(5));
        cfg.setMode(0, DisplayMode.ICON);
        assertEquals(DisplayMode.ICON, cfg.get(0).mode());
    }

    @Test
    @DisplayName("replaceFrom de-duplicates and preserves first-seen order")
    void replaceFrom() {
        ToolbarButtonConfig cfg = ToolbarButtonConfig.defaults();
        cfg.add("keep");
        cfg.replaceFrom(List.of(
                new Entry("a", DisplayMode.TEXT),
                new Entry("a", DisplayMode.ICON),
                new Entry("b", DisplayMode.ICON_TEXT),
                new Entry("", DisplayMode.ICON)));
        assertEquals(List.of("a", "b"), cfg.ids());
        assertEquals(DisplayMode.TEXT, cfg.get(0).mode(), "first-seen entry wins");
    }

    @Test
    @DisplayName("resolve drops stale ids at render time but never at load")
    void resolveDropsStale() {
        ToolbarButtonConfig cfg = ToolbarButtonConfig.defaults();
        cfg.add("live");
        cfg.add("gone");
        List<Entry> rendered = cfg.resolve(List.of("live", "other"));
        assertEquals(1, rendered.size());
        assertEquals("live", rendered.get(0).id());
        // The stored layout is untouched: the stale id survives for a later re-enable.
        assertTrue(cfg.contains("gone"));
    }

    @Test
    @DisplayName("DisplayMode.parse falls back for null and junk")
    void parseMode() {
        assertEquals(DisplayMode.ICON, DisplayMode.parse("icon", DisplayMode.TEXT));
        assertEquals(DisplayMode.ICON_TEXT, DisplayMode.parse(" ICON_TEXT ", DisplayMode.ICON));
        assertEquals(DisplayMode.TEXT, DisplayMode.parse("nope", DisplayMode.TEXT));
        assertEquals(DisplayMode.TEXT, DisplayMode.parse(null, DisplayMode.TEXT));
    }
}
