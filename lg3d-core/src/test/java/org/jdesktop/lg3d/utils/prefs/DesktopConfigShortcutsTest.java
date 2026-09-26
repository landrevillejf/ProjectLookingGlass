/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code shortcuts.custom} field on {@link DesktopConfig} and its pure
 * {@link DesktopConfig#parseCustomShortcuts(String)} /
 * {@link DesktopConfig#serializeCustomShortcuts(Map)} codec: the empty default,
 * the trim/null normalisation, the round-trip and the skipping of blank or
 * malformed {@code action=keyspec} entries. The setters only mutate in-memory
 * state (no {@code save()}) and each test restores the defaults afterwards, so
 * the shared singleton - and the user's real preferences - are left untouched.
 */
class DesktopConfigShortcutsTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the custom-shortcut default is empty (all defaults)")
    void defaultEmpty() {
        cfg.resetToDefaults();
        assertEquals(DesktopConfig.DEFAULT_SHORTCUTS_CUSTOM, cfg.getCustomShortcuts());
        assertEquals("", cfg.getCustomShortcuts());
    }

    @Test
    @DisplayName("the encoded value is trimmed and null falls back to empty")
    void normalized() {
        cfg.setCustomShortcuts("  run-dialog=alt F3  ");
        assertEquals("run-dialog=alt F3", cfg.getCustomShortcuts());
        cfg.setCustomShortcuts(null);
        assertEquals("", cfg.getCustomShortcuts());
    }

    @Test
    @DisplayName("parseCustomShortcuts decodes action=keyspec pairs")
    void parse() {
        Map<String, String> parsed = DesktopConfig.parseCustomShortcuts(
                "run-dialog=alt F3;open-terminal=control alt X");
        assertEquals(2, parsed.size());
        assertEquals("alt F3", parsed.get("run-dialog"));
        assertEquals("control alt X", parsed.get("open-terminal"));
    }

    @Test
    @DisplayName("parseCustomShortcuts skips null, blank and malformed entries")
    void parseSkipsJunk() {
        assertTrue(DesktopConfig.parseCustomShortcuts(null).isEmpty());
        assertTrue(DesktopConfig.parseCustomShortcuts("").isEmpty());
        Map<String, String> parsed = DesktopConfig.parseCustomShortcuts(
                ";  ;noEquals;=alt F3;run-dialog=;  show-desktop = control alt D  ");
        assertEquals(1, parsed.size(), "only the well-formed pair survives");
        assertEquals("control alt D", parsed.get("show-desktop"));
    }

    @Test
    @DisplayName("serializeCustomShortcuts is the inverse of parseCustomShortcuts")
    void roundTrip() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("run-dialog", "alt F3");
        original.put("open-terminal", "control alt X");
        String encoded = DesktopConfig.serializeCustomShortcuts(original);
        assertEquals(original, DesktopConfig.parseCustomShortcuts(encoded));
    }

    @Test
    @DisplayName("serializeCustomShortcuts skips null/blank keys and values")
    void serializeSkipsBlanks() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(null, "alt F3");
        map.put("   ", "alt F4");
        map.put("run-dialog", "   ");
        map.put("open-terminal", "control alt X");
        assertEquals("open-terminal=control alt X",
                DesktopConfig.serializeCustomShortcuts(map));
        assertEquals(DesktopConfig.DEFAULT_SHORTCUTS_CUSTOM,
                DesktopConfig.serializeCustomShortcuts(null));
        assertEquals(DesktopConfig.DEFAULT_SHORTCUTS_CUSTOM,
                DesktopConfig.serializeCustomShortcuts(Map.of()));
    }

    @Test
    @DisplayName("resetToDefaults clears the custom shortcuts")
    void resetClears() {
        cfg.setCustomShortcuts("run-dialog=alt F3");
        cfg.resetToDefaults();
        assertEquals("", cfg.getCustomShortcuts());
    }
}
