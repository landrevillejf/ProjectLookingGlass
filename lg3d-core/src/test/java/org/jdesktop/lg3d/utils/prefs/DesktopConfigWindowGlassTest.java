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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the window-glass style field on {@link DesktopConfig}: its default
 * (the fixed-function GlassyPanel) and the in-memory round-trip of the frosted
 * flag. The setter only mutates in-memory state (no {@code save()}), and each
 * test restores the defaults afterwards, so the shared singleton is left clean.
 */
class DesktopConfigWindowGlassTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the window-glass default is the classic GlassyPanel, not frosted")
    void defaultIsGlassy() {
        cfg.resetToDefaults();
        assertFalse(cfg.isFrostedGlass());
        assertFalse(DesktopConfig.DEFAULT_FROSTED_GLASS);
    }

    @Test
    @DisplayName("the frosted flag round-trips")
    void frostedRoundTrips() {
        cfg.setFrostedGlass(true);
        assertTrue(cfg.isFrostedGlass());
        cfg.setFrostedGlass(false);
        assertFalse(cfg.isFrostedGlass());
    }

    @Test
    @DisplayName("resetToDefaults returns the window glass to Glassy")
    void resetReturnsToGlassy() {
        cfg.setFrostedGlass(true);
        cfg.resetToDefaults();
        assertFalse(cfg.isFrostedGlass());
    }
}
