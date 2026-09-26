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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the Metal-theme fields on {@link DesktopConfig}: the blank default
 * (no theme chosen, native look kept), the in-memory round-trip of the selected
 * theme name and the encoded custom-theme list, and the trimming both setters
 * apply. The setters only mutate in-memory state (no {@code save()}), and each
 * test restores the defaults afterwards, so the shared singleton is left clean.
 */
class DesktopConfigMetalThemeTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the Metal theme defaults to blank (no theme chosen)")
    void defaultIsBlank() {
        cfg.resetToDefaults();
        assertEquals("", cfg.getMetalTheme());
        assertEquals("", cfg.getMetalCustomThemes());
        assertEquals("", DesktopConfig.DEFAULT_METAL_THEME);
        assertEquals("", DesktopConfig.DEFAULT_METAL_CUSTOM_THEMES);
    }

    @Test
    @DisplayName("the selected theme name round-trips and is trimmed")
    void themeNameRoundTrips() {
        cfg.setMetalTheme("  Ocean  ");
        assertEquals("Ocean", cfg.getMetalTheme());
        cfg.setMetalTheme(null);
        assertEquals("", cfg.getMetalTheme(), "null falls back to the blank default");
    }

    @Test
    @DisplayName("the encoded custom-theme list round-trips and is trimmed")
    void customThemesRoundTrip() {
        String encoded = "Jade;#30a060;#30a060;#c0ffe0;#666666;#999999;#cccccc";
        cfg.setMetalCustomThemes(" " + encoded + " ");
        assertEquals(encoded, cfg.getMetalCustomThemes());
        cfg.setMetalCustomThemes(null);
        assertTrue(cfg.getMetalCustomThemes().isEmpty());
    }

    @Test
    @DisplayName("resetToDefaults clears the Metal theme and custom themes")
    void resetClearsThemes() {
        cfg.setMetalTheme("Jade");
        cfg.setMetalCustomThemes("Jade;#30a060;#30a060;#c0ffe0;#666666;#999999;#cccccc");
        cfg.resetToDefaults();
        assertEquals("", cfg.getMetalTheme());
        assertEquals("", cfg.getMetalCustomThemes());
    }
}
