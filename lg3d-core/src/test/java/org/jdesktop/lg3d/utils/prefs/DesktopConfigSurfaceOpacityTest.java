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

import org.jdesktop.lg3d.displayserver.desktop2d.TransparencySurface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the four 2D-desktop surface-opacity fields on {@link DesktopConfig}:
 * their shared default (fully solid, the pre-transparency look), the clamping
 * each setter applies, and that {@code resetToDefaults} returns all four.
 *
 * <p>Like the other {@code DesktopConfig} suites, the setters only mutate
 * in-memory state - nothing here calls {@code save()}, which would leak into the
 * developer's real preference store because the test JVM does not isolate
 * {@code java.util.prefs}. Each test restores the defaults afterwards so the
 * shared singleton is left clean for the suites that follow.</p>
 */
class DesktopConfigSurfaceOpacityTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("every surface defaults to fully solid")
    void defaultsAreSolid() {
        cfg.resetToDefaults();
        assertEquals(100, DesktopConfig.DEFAULT_SURFACE_OPACITY);
        assertEquals(100, cfg.getWindowOpacity());
        assertEquals(100, cfg.getTaskbarOpacity());
        assertEquals(100, cfg.getWidgetOpacity());
        assertEquals(100, cfg.getNotificationOpacity());
    }

    @Test
    @DisplayName("each surface round-trips its own value independently")
    void surfacesAreIndependent() {
        cfg.setWindowOpacity(60);
        cfg.setTaskbarOpacity(80);
        cfg.setWidgetOpacity(35);
        cfg.setNotificationOpacity(99);
        assertEquals(60, cfg.getWindowOpacity());
        assertEquals(80, cfg.getTaskbarOpacity());
        assertEquals(35, cfg.getWidgetOpacity());
        assertEquals(99, cfg.getNotificationOpacity());
    }

    @Test
    @DisplayName("an opacity below the floor is clamped, not accepted")
    void clampsToLowerBound() {
        cfg.setWindowOpacity(0);
        assertEquals(DesktopConfig.MIN_SURFACE_OPACITY, cfg.getWindowOpacity());
        cfg.setWidgetOpacity(-500);
        assertEquals(DesktopConfig.MIN_SURFACE_OPACITY, cfg.getWidgetOpacity());
    }

    @Test
    @DisplayName("an opacity above solid is clamped to solid")
    void clampsToUpperBound() {
        cfg.setTaskbarOpacity(150);
        assertEquals(DesktopConfig.MAX_SURFACE_OPACITY, cfg.getTaskbarOpacity());
        cfg.setNotificationOpacity(Integer.MAX_VALUE);
        assertEquals(DesktopConfig.MAX_SURFACE_OPACITY,
                cfg.getNotificationOpacity());
    }

    @Test
    @DisplayName("clampOpacity degrades a hand-edited value to the nearer bound")
    void clampIsPublicAndTotal() {
        assertEquals(DesktopConfig.MIN_SURFACE_OPACITY,
                DesktopConfig.clampOpacity(1));
        assertEquals(75, DesktopConfig.clampOpacity(75));
        assertEquals(DesktopConfig.MAX_SURFACE_OPACITY,
                DesktopConfig.clampOpacity(1000));
    }

    @Test
    @DisplayName("resetToDefaults returns all four surfaces to solid")
    void resetReturnsToSolid() {
        cfg.setWindowOpacity(40);
        cfg.setTaskbarOpacity(40);
        cfg.setWidgetOpacity(40);
        cfg.setNotificationOpacity(40);
        cfg.resetToDefaults();
        assertEquals(100, cfg.getWindowOpacity());
        assertEquals(100, cfg.getTaskbarOpacity());
        assertEquals(100, cfg.getWidgetOpacity());
        assertEquals(100, cfg.getNotificationOpacity());
    }

    @Test
    @DisplayName("each enum surface reads and writes its own preference")
    void enumAccessorsMapToTheFields() {
        for (TransparencySurface surface : TransparencySurface.values()) {
            assertEquals(100, surface.opacityOf(cfg), surface + " starts solid");
            surface.setOpacityOf(cfg, 55);
            assertEquals(55, surface.opacityOf(cfg), surface + " stores its own");
        }
        // Writing every surface must not have collapsed them onto one field.
        cfg.resetToDefaults();
        cfg.setWindowOpacity(30);
        assertEquals(30, TransparencySurface.WINDOW.opacityOf(cfg));
        assertEquals(100, TransparencySurface.TASKBAR.opacityOf(cfg));
    }
}
