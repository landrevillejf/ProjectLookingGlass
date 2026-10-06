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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code theme.accent} field on {@link DesktopConfig}: the blank
 * default (no explicit accent, so it derives from the active theme), the
 * in-memory round-trip of a well-formed {@code #rrggbb} value, the normalisation
 * that lower-cases a valid colour and rejects null/blank/malformed input, and
 * that {@code resetToDefaults()} clears it. The setter only mutates in-memory
 * state (no {@code save()}), and each test restores the defaults afterwards, so
 * the shared singleton is left clean.
 */
class DesktopConfigAccentTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the accent colour defaults to blank (derive from the theme)")
    void defaultIsBlank() {
        cfg.resetToDefaults();
        assertEquals("", cfg.getAccentColor());
        assertEquals("", DesktopConfig.DEFAULT_THEME_ACCENT);
    }

    @Test
    @DisplayName("a well-formed accent round-trips and is lower-cased")
    void accentRoundTrips() {
        cfg.setAccentColor("#1A2B3C");
        assertEquals("#1a2b3c", cfg.getAccentColor());
        cfg.setAccentColor("  #FFCC00  ");
        assertEquals("#ffcc00", cfg.getAccentColor(), "surrounding whitespace is trimmed");
    }

    @Test
    @DisplayName("null, blank and malformed accents fall back to the blank default")
    void rejectsGarbage() {
        cfg.setAccentColor("#123456");
        cfg.setAccentColor(null);
        assertEquals("", cfg.getAccentColor(), "null clears the accent");
        cfg.setAccentColor("#123456");
        cfg.setAccentColor("   ");
        assertEquals("", cfg.getAccentColor(), "blank clears the accent");
        cfg.setAccentColor("#123456");
        cfg.setAccentColor("123456");
        assertEquals("", cfg.getAccentColor(), "a missing '#' is rejected");
        cfg.setAccentColor("#123456");
        cfg.setAccentColor("#zzzzzz");
        assertEquals("", cfg.getAccentColor(), "a non-hex colour is rejected");
        cfg.setAccentColor("#123456");
        cfg.setAccentColor("#1234");
        assertEquals("", cfg.getAccentColor(), "a short colour is rejected");
    }

    @Test
    @DisplayName("resetToDefaults clears the accent colour")
    void resetClearsAccent() {
        cfg.setAccentColor("#0a0b0c");
        cfg.resetToDefaults();
        assertEquals("", cfg.getAccentColor());
    }
}
