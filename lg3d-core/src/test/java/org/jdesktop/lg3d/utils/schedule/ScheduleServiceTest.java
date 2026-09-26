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
package org.jdesktop.lg3d.utils.schedule;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link ScheduleService}'s pure decision logic.
 *
 * <p>The clock-driven choice ({@code isTimeBetween} / {@code resolveWallpaper})
 * and the wallpaper guard branches of {@code applyWallpaper} are exercised
 * directly. The timer, the singleton lifecycle and the actual wallpaper
 * application (a jogamp {@code BackgroundChangeRequestEvent} on the 3D desktop,
 * {@code Desktop2D.setWallpaper} on the 2D desktop) need a live desktop and are
 * verified at runtime, not here: constructing jogamp scene-graph objects in the
 * headless test JVM throws. The schedule is kept disabled throughout so the
 * daemon timer's immediate tick is a no-op.</p>
 */
class ScheduleServiceTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @BeforeEach
    void disable() {
        cfg.resetToDefaults();
        cfg.setWallpaperScheduleEnabled(false);
        cfg.setLightingScheduleEnabled(false);
    }

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    // ------------------------------------------------------------------
    // isTimeBetween
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a same-day interval includes its bounds and excludes the outside")
    void sameDayInterval() {
        LocalTime start = LocalTime.of(7, 0);
        LocalTime end = LocalTime.of(20, 0);
        assertFalse(ScheduleService.isTimeBetween(LocalTime.of(6, 59), start, end));
        assertTrue(ScheduleService.isTimeBetween(LocalTime.of(7, 0), start, end), "start is inclusive");
        assertTrue(ScheduleService.isTimeBetween(LocalTime.of(12, 0), start, end));
        assertTrue(ScheduleService.isTimeBetween(LocalTime.of(20, 0), start, end), "end is inclusive");
        assertFalse(ScheduleService.isTimeBetween(LocalTime.of(20, 1), start, end));
    }

    @Test
    @DisplayName("an interval crossing midnight wraps around")
    void crossingMidnightInterval() {
        LocalTime start = LocalTime.of(20, 0);
        LocalTime end = LocalTime.of(7, 0);
        assertTrue(ScheduleService.isTimeBetween(LocalTime.of(23, 0), start, end));
        assertTrue(ScheduleService.isTimeBetween(LocalTime.of(3, 0), start, end));
        assertFalse(ScheduleService.isTimeBetween(LocalTime.of(12, 0), start, end));
    }

    // ------------------------------------------------------------------
    // resolveWallpaper
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveWallpaper picks the daylight wallpaper inside the daylight window")
    void resolvesDaylight() {
        cfg.setDaylightHour(7);
        cfg.setDaylightMinute(0);
        cfg.setNightlightHour(20);
        cfg.setNightlightMinute(0);
        cfg.setDaylightWallpaper("day.jpg");
        cfg.setNightlightWallpaper("night.jpg");
        assertEquals("day.jpg", ScheduleService.resolveWallpaper(LocalTime.of(12, 0), cfg));
    }

    @Test
    @DisplayName("resolveWallpaper picks the nightlight wallpaper outside the daylight window")
    void resolvesNightlight() {
        cfg.setDaylightHour(7);
        cfg.setDaylightMinute(0);
        cfg.setNightlightHour(20);
        cfg.setNightlightMinute(0);
        cfg.setDaylightWallpaper("day.jpg");
        cfg.setNightlightWallpaper("night.jpg");
        assertEquals("night.jpg", ScheduleService.resolveWallpaper(LocalTime.of(23, 30), cfg));
    }

    @Test
    @DisplayName("resolveWallpaper honours a daylight window that crosses midnight")
    void resolvesAcrossMidnight() {
        cfg.setDaylightHour(22);
        cfg.setDaylightMinute(0);
        cfg.setNightlightHour(6);
        cfg.setNightlightMinute(0);
        cfg.setDaylightWallpaper("day.jpg");
        cfg.setNightlightWallpaper("night.jpg");
        assertEquals("day.jpg", ScheduleService.resolveWallpaper(LocalTime.of(23, 0), cfg));
        assertEquals("night.jpg", ScheduleService.resolveWallpaper(LocalTime.of(12, 0), cfg));
    }

    // ------------------------------------------------------------------
    // applyWallpaper guard branches (no jogamp / no live desktop touched)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("applyWallpaper ignores null, empty and missing wallpapers without throwing")
    void applyWallpaperGuards() {
        ScheduleService svc = ScheduleService.get();
        assertDoesNotThrow(() -> svc.applyWallpaper(null));
        assertDoesNotThrow(() -> svc.applyWallpaper(""));
        // A filename that resolves to no classpath resource is logged and skipped
        // before any jogamp / Desktop2D call, so it is safe headlessly.
        assertDoesNotThrow(() -> svc.applyWallpaper("no-such-wallpaper-xyz-123.jpg"));
    }

    // ------------------------------------------------------------------
    // singleton lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("get() returns the shared singleton and checkNow is a no-op when disabled")
    void singletonAndDisabledCheckNow() {
        ScheduleService a = ScheduleService.get();
        ScheduleService b = ScheduleService.get();
        assertSame(a, b);
        cfg.setWallpaperScheduleEnabled(false);
        cfg.setLightingScheduleEnabled(false);
        assertDoesNotThrow(a::checkNow);
    }

    @Test
    @DisplayName("stop() cancels the daemon timer without throwing")
    void stopCancels() {
        assertDoesNotThrow(() -> ScheduleService.get().stop());
    }

    @Test
    @DisplayName("applyDayNight is a safe no-op when no desktop is running (3D and 2D)")
    void applyDayNightNoOpWithoutDesktop() {
        ScheduleService svc = ScheduleService.get();
        // 3D path: StandardGlobalLights.live() is null in the headless test JVM,
        // so nothing is constructed and no jogamp object is touched.
        assertDoesNotThrow(() -> svc.applyDayNight(0.5f));

        // 2D path: Desktop2D.setNightTint no-ops when no shell is running.
        String prev = System.getProperty(Desktop2D.MODE_PROPERTY);
        System.setProperty(Desktop2D.MODE_PROPERTY, "true");
        try {
            assertDoesNotThrow(() -> svc.applyDayNight(1.0f));
        } finally {
            if (prev == null) {
                System.clearProperty(Desktop2D.MODE_PROPERTY);
            } else {
                System.setProperty(Desktop2D.MODE_PROPERTY, prev);
            }
        }
    }

    @Test
    @DisplayName("checkNow honours each toggle independently without throwing")
    void checkNowHonoursIndependentToggles() {
        ScheduleService svc = ScheduleService.get();

        // Lighting only: the wallpaper branch is skipped and applyDayNight no-ops
        // headlessly (StandardGlobalLights.live() is null, no 2D shell running).
        cfg.setWallpaperScheduleEnabled(false);
        cfg.setLightingScheduleEnabled(true);
        assertDoesNotThrow(svc::checkNow);

        // Wallpaper only: resolveWallpaper/applyWallpaper run while lighting is
        // left alone; a filename with no classpath resource is logged and skipped.
        cfg.setWallpaperScheduleEnabled(true);
        cfg.setLightingScheduleEnabled(false);
        cfg.setDaylightWallpaper("no-such-wallpaper-xyz-123.jpg");
        cfg.setNightlightWallpaper("no-such-wallpaper-xyz-123.jpg");
        assertDoesNotThrow(svc::checkNow);
    }
}
