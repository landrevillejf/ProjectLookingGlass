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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the daylight/nightlight schedule fields on {@link DesktopConfig}:
 * their defaults, the hour/minute clamping and the wallpaper normalisation.
 * The setters only mutate in-memory state (no {@code save()}), and each test
 * restores the defaults afterwards, so the shared singleton is left clean.
 */
class DesktopConfigScheduleTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the schedule defaults are off, 07:00 daylight, 20:00 night, no wallpapers")
    void defaults() {
        cfg.resetToDefaults();
        assertFalse(cfg.isScheduleEnabled());
        assertEquals(DesktopConfig.DEFAULT_DAYLIGHT_HOUR, cfg.getDaylightHour());
        assertEquals(DesktopConfig.DEFAULT_DAYLIGHT_MINUTE, cfg.getDaylightMinute());
        assertEquals(DesktopConfig.DEFAULT_NIGHTLIGHT_HOUR, cfg.getNightlightHour());
        assertEquals(DesktopConfig.DEFAULT_NIGHTLIGHT_MINUTE, cfg.getNightlightMinute());
        assertEquals(DesktopConfig.DEFAULT_DAYLIGHT_WALLPAPER, cfg.getDaylightWallpaper());
        assertEquals(DesktopConfig.DEFAULT_NIGHTLIGHT_WALLPAPER, cfg.getNightlightWallpaper());
        assertEquals(7, DesktopConfig.DEFAULT_DAYLIGHT_HOUR);
        assertEquals(20, DesktopConfig.DEFAULT_NIGHTLIGHT_HOUR);
        assertEquals("", DesktopConfig.DEFAULT_DAYLIGHT_WALLPAPER);
    }

    @Test
    @DisplayName("the enable flag round-trips")
    void enableRoundTrips() {
        cfg.setScheduleEnabled(true);
        assertTrue(cfg.isScheduleEnabled());
        cfg.setScheduleEnabled(false);
        assertFalse(cfg.isScheduleEnabled());
    }

    @Test
    @DisplayName("the daylight hour is clamped to 0-23")
    void daylightHourClamped() {
        cfg.setDaylightHour(-5);
        assertEquals(0, cfg.getDaylightHour());
        cfg.setDaylightHour(99);
        assertEquals(23, cfg.getDaylightHour());
        cfg.setDaylightHour(6);
        assertEquals(6, cfg.getDaylightHour(), "an in-range value is kept");
    }

    @Test
    @DisplayName("the nightlight hour is clamped to 0-23")
    void nightlightHourClamped() {
        cfg.setNightlightHour(-1);
        assertEquals(0, cfg.getNightlightHour());
        cfg.setNightlightHour(24);
        assertEquals(23, cfg.getNightlightHour());
        cfg.setNightlightHour(22);
        assertEquals(22, cfg.getNightlightHour());
    }

    @Test
    @DisplayName("the daylight minute is clamped to 0-59")
    void daylightMinuteClamped() {
        cfg.setDaylightMinute(-10);
        assertEquals(0, cfg.getDaylightMinute());
        cfg.setDaylightMinute(120);
        assertEquals(59, cfg.getDaylightMinute());
        cfg.setDaylightMinute(30);
        assertEquals(30, cfg.getDaylightMinute());
    }

    @Test
    @DisplayName("the nightlight minute is clamped to 0-59")
    void nightlightMinuteClamped() {
        cfg.setNightlightMinute(-1);
        assertEquals(0, cfg.getNightlightMinute());
        cfg.setNightlightMinute(60);
        assertEquals(59, cfg.getNightlightMinute());
        cfg.setNightlightMinute(45);
        assertEquals(45, cfg.getNightlightMinute());
    }

    @Test
    @DisplayName("the wallpaper filenames are trimmed and null falls back to empty")
    void wallpapersNormalized() {
        cfg.setDaylightWallpaper("  GrandCanyon-0.jpg  ");
        assertEquals("GrandCanyon-0.jpg", cfg.getDaylightWallpaper());
        cfg.setDaylightWallpaper(null);
        assertEquals("", cfg.getDaylightWallpaper());

        cfg.setNightlightWallpaper("Stanford-0.jpg");
        assertEquals("Stanford-0.jpg", cfg.getNightlightWallpaper());
        cfg.setNightlightWallpaper(null);
        assertEquals("", cfg.getNightlightWallpaper());
    }

    @Test
    @DisplayName("resetToDefaults restores the schedule fields")
    void resetRestoresSchedule() {
        cfg.setScheduleEnabled(true);
        cfg.setDaylightHour(3);
        cfg.setNightlightMinute(15);
        cfg.setDaylightWallpaper("x.jpg");
        cfg.resetToDefaults();
        assertFalse(cfg.isScheduleEnabled());
        assertEquals(DesktopConfig.DEFAULT_DAYLIGHT_HOUR, cfg.getDaylightHour());
        assertEquals(DesktopConfig.DEFAULT_NIGHTLIGHT_MINUTE, cfg.getNightlightMinute());
        assertEquals("", cfg.getDaylightWallpaper());
    }
}
