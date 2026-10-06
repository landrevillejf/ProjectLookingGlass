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

import org.jdesktop.lg3d.utils.prefs.DesktopConfig.Indicator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the system-tray indicator fields on {@link DesktopConfig}: which
 * indicators ({@link Indicator}) are mirrored and whether the host
 * {@code java.awt.SystemTray} mirror is enabled at all. Every indicator defaults
 * to shown and the mirror defaults to off. The setters only mutate in-memory
 * state (no {@code save()}), and each test restores the defaults afterwards, so
 * the shared singleton is left clean.
 */
class DesktopConfigIndicatorsTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("every indicator is shown and the system-tray mirror is off by default")
    void defaults() {
        cfg.resetToDefaults();
        for (Indicator ind : Indicator.values()) {
            assertTrue(cfg.isIndicatorShown(ind), ind + " should be shown by default");
        }
        assertFalse(cfg.isIndicatorsSystemTray(),
                "the host system-tray mirror is opt-in");
    }

    @Test
    @DisplayName("hiding and re-showing an indicator round-trips")
    void visibilityRoundTrips() {
        cfg.setIndicatorShown(Indicator.VOLUME, false);
        assertFalse(cfg.isIndicatorShown(Indicator.VOLUME));
        assertTrue(cfg.isIndicatorShown(Indicator.NETWORK),
                "hiding one indicator leaves the others shown");
        cfg.setIndicatorShown(Indicator.VOLUME, true);
        assertTrue(cfg.isIndicatorShown(Indicator.VOLUME));
    }

    @Test
    @DisplayName("each indicator hides independently")
    void indicatorsAreIndependent() {
        cfg.setIndicatorShown(Indicator.NETWORK, false);
        assertFalse(cfg.isIndicatorShown(Indicator.NETWORK));
        assertTrue(cfg.isIndicatorShown(Indicator.VOLUME));
    }

    @Test
    @DisplayName("a null indicator is tolerated and reports hidden-safe")
    void nullIndicatorIsSafe() {
        cfg.setIndicatorShown(null, false);
        assertFalse(cfg.isIndicatorShown(null));
    }

    @Test
    @DisplayName("the system-tray mirror flag round-trips")
    void systemTrayRoundTrips() {
        cfg.setIndicatorsSystemTray(true);
        assertTrue(cfg.isIndicatorsSystemTray());
        cfg.setIndicatorsSystemTray(false);
        assertFalse(cfg.isIndicatorsSystemTray());
    }

    @Test
    @DisplayName("resetToDefaults re-shows every indicator and clears the mirror")
    void resetRestores() {
        cfg.setIndicatorShown(Indicator.VOLUME, false);
        cfg.setIndicatorsSystemTray(true);
        cfg.resetToDefaults();
        assertTrue(cfg.isIndicatorShown(Indicator.VOLUME));
        assertFalse(cfg.isIndicatorsSystemTray());
    }

    @Test
    @DisplayName("save/load persists the indicator visibility and mirror flag")
    void persistsAcrossLoad() {
        cfg.setIndicatorShown(Indicator.NETWORK, false);
        cfg.setIndicatorsSystemTray(true);
        cfg.save();
        try {
            cfg.load();
            assertFalse(cfg.isIndicatorShown(Indicator.NETWORK));
            assertTrue(cfg.isIndicatorsSystemTray());
        } finally {
            cfg.resetToDefaults();
            cfg.save();
        }
    }
}
