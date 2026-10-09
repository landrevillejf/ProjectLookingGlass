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
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link EditorSettings}: defaults, clamping setters, the
 * pure map serialisation round-trip, theme-name fallbacks and the
 * most-recent-first recent-files list. The platform preference store is
 * never touched ({@code saveUser}/{@code loadUser} are exercised only
 * through their pure {@code toMap}/{@code fromMap} core).
 */
class EditorSettingsTest {

    @Test
    @DisplayName("defaults describe a usable editor")
    void defaults() {
        EditorSettings settings = EditorSettings.defaults();
        assertEquals("Monospaced", settings.getFontFamily());
        assertEquals(EditorSettings.DEFAULT_FONT_SIZE, settings.getFontSize());
        assertEquals(4, settings.getTabSize());
        assertFalse(settings.isHardTabs());
        assertTrue(settings.isWordWrap());
        assertTrue(settings.isLineNumbers());
        assertTrue(settings.isAutoIndent());
        assertTrue(settings.isHighlight());
        assertEquals("Light", settings.getThemeName());
        assertTrue(settings.getRecentFiles().isEmpty());
        assertSame(EditorTheme.LIGHT, settings.getTheme());
        assertEquals(14, settings.getFont().getSize());
    }

    @Test
    @DisplayName("setters clamp into the supported ranges")
    void clamping() {
        EditorSettings settings = EditorSettings.defaults();
        settings.setFontSize(1);
        assertEquals(EditorSettings.MIN_FONT_SIZE, settings.getFontSize());
        settings.setFontSize(999);
        assertEquals(EditorSettings.MAX_FONT_SIZE, settings.getFontSize());
        settings.setTabSize(0);
        assertEquals(1, settings.getTabSize());
        settings.setTabSize(64);
        assertEquals(16, settings.getTabSize());
        settings.setFontFamily(null);
        assertEquals("Monospaced", settings.getFontFamily());
        settings.setFontFamily("DejaVu Sans Mono");
        assertEquals("DejaVu Sans Mono", settings.getFontFamily());
    }

    @Test
    @DisplayName("unknown theme names fall back to Light")
    void themeFallback() {
        EditorSettings settings = EditorSettings.defaults();
        settings.setThemeName("Dark");
        assertSame(EditorTheme.DARK, settings.getTheme());
        settings.setThemeName("dark"); // case-insensitive
        assertSame(EditorTheme.DARK, settings.getTheme());
        settings.setThemeName("Neon Rainbow");
        assertEquals("Light", settings.getThemeName());
        settings.setThemeName(null);
        assertEquals("Light", settings.getThemeName());
        settings.setThemeName("   ");
        assertEquals("Light", settings.getThemeName());
        // The theme catalogue itself.
        assertEquals(2, EditorTheme.builtIn().size());
        assertSame(EditorTheme.LIGHT, EditorTheme.byName("light"));
        assertSame(EditorTheme.LIGHT, EditorTheme.byName(null));
        assertSame(EditorTheme.DARK, EditorTheme.byName("DARK"));
    }

    @Test
    @DisplayName("toMap/fromMap round-trips every setting")
    void mapRoundTrip() {
        EditorSettings settings = EditorSettings.defaults();
        settings.setFontFamily("Courier New");
        settings.setFontSize(18);
        settings.setTabSize(2);
        settings.setHardTabs(true);
        settings.setWordWrap(false);
        settings.setLineNumbers(false);
        settings.setAutoIndent(false);
        settings.setHighlight(false);
        settings.setThemeName("Dark");

        EditorSettings copy = EditorSettings.fromMap(settings.toMap());
        assertEquals("Courier New", copy.getFontFamily());
        assertEquals(18, copy.getFontSize());
        assertEquals(2, copy.getTabSize());
        assertTrue(copy.isHardTabs());
        assertFalse(copy.isWordWrap());
        assertFalse(copy.isLineNumbers());
        assertFalse(copy.isAutoIndent());
        assertFalse(copy.isHighlight());
        assertEquals("Dark", copy.getThemeName());
    }

    @Test
    @DisplayName("fromMap tolerates junk and unknown keys")
    void fromMapJunk() {
        Map<String, String> junk = new HashMap<>();
        junk.put("fontSize", "not-a-number");
        junk.put("tabSize", "999");
        junk.put("hardTabs", "maybe");
        junk.put("theme", "Unknown Theme");
        junk.put("somethingElse", "ignored");
        EditorSettings settings = EditorSettings.fromMap(junk);
        assertEquals(EditorSettings.DEFAULT_FONT_SIZE, settings.getFontSize());
        assertEquals(16, settings.getTabSize()); // clamped
        assertFalse(settings.isHardTabs());
        assertEquals("Light", settings.getThemeName());
        // A null map yields defaults.
        assertEquals("Monospaced",
                EditorSettings.fromMap(null).getFontFamily());
    }

    @Test
    @DisplayName("pushRecent prepends, dedupes and caps the list")
    void recentFiles() {
        EditorSettings settings = EditorSettings.defaults();
        settings.pushRecent("/tmp/a.txt");
        settings.pushRecent("/tmp/b.txt");
        settings.pushRecent("/tmp/a.txt"); // dedupe: moves to the front
        assertEquals(List.of("/tmp/a.txt", "/tmp/b.txt"),
                settings.getRecentFiles());

        for (int i = 0; i < EditorSettings.MAX_RECENT + 5; i++) {
            settings.pushRecent("/tmp/file" + i + ".txt");
        }
        assertEquals(EditorSettings.MAX_RECENT,
                settings.getRecentFiles().size());
        assertEquals("/tmp/file" + (EditorSettings.MAX_RECENT + 4) + ".txt",
                settings.getRecentFiles().get(0));

        // Blank and null paths are ignored.
        settings.pushRecent(null);
        settings.pushRecent("  ");
        assertEquals(EditorSettings.MAX_RECENT,
                settings.getRecentFiles().size());

        settings.clearRecent();
        assertTrue(settings.getRecentFiles().isEmpty());
    }

    @Test
    @DisplayName("the recent list survives the serialisation round-trip")
    void recentRoundTrip() {
        EditorSettings settings = EditorSettings.defaults();
        settings.pushRecent("/tmp/one.txt");
        settings.pushRecent("/tmp/two.txt");
        // Mirror saveUser: the list is stored as one '|'-joined entry.
        Map<String, String> map = settings.toMap();
        map.put("recentFiles", String.join("|", settings.getRecentFiles()));
        // Mirror loadUser: fromMap rebuilds the scalars, then the list is
        // re-pushed oldest-first so pushRecent's prepend restores the order.
        EditorSettings copy = EditorSettings.fromMap(map);
        List<String> joined = List.of(map.get("recentFiles").split("\\|"));
        for (int i = joined.size() - 1; i >= 0; i--) {
            if (!joined.get(i).isBlank()) {
                copy.pushRecent(joined.get(i));
            }
        }
        assertEquals(List.of("/tmp/two.txt", "/tmp/one.txt"),
                copy.getRecentFiles());
    }

    @Test
    @DisplayName("accelerator overrides survive the serialisation round-trip")
    void acceleratorOverrideRoundTrip() {
        EditorSettings settings = EditorSettings.defaults();
        assertTrue(settings.getAcceleratorOverrides().isEmpty());
        settings.setAcceleratorOverride("sort-az", "control alt 8");
        settings.setAcceleratorOverride("md-bold", ""); // deliberate unbind

        EditorSettings copy = EditorSettings.fromMap(settings.toMap());
        assertEquals("control alt 8", copy.getAcceleratorOverrides().get("sort-az"));
        assertEquals("", copy.getAcceleratorOverrides().get("md-bold"));

        copy.clearAcceleratorOverride("sort-az");
        assertFalse(copy.getAcceleratorOverrides().containsKey("sort-az"));
        // A blank id is ignored.
        settings.setAcceleratorOverride("  ", "control alt A");
    }
}
