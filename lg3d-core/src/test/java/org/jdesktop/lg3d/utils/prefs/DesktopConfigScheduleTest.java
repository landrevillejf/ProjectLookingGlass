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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.utils.schedule.ScheduleEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the restructured schedule on {@link DesktopConfig}: the independent
 * wallpaper/lighting enable flags, the variable-length wallpaper entry list
 * (sorted, defensive copies, clamped) and the lighting schedule's own dawn/dusk
 * times. The setters only mutate in-memory state (no {@code save()}), and each
 * test restores the defaults afterwards, so the shared singleton is left clean.
 */
class DesktopConfigScheduleTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("defaults: both schedules off, two wallpaper entries, lighting 07:00/20:00")
    void defaults() {
        cfg.resetToDefaults();
        assertFalse(cfg.isWallpaperScheduleEnabled());
        assertFalse(cfg.isLightingScheduleEnabled());
        assertFalse(cfg.isScheduleEnabled(), "off when both toggles are off");

        List<ScheduleEntry> entries = cfg.getWallpaperSchedule();
        assertEquals(2, entries.size(), "the default schedule mirrors the old day/night pair");
        assertEquals(7, entries.get(0).hour());
        assertEquals(0, entries.get(0).minute());
        assertEquals("", entries.get(0).wallpaper());
        assertEquals(20, entries.get(1).hour());

        assertEquals(DesktopConfig.DEFAULT_LIGHTING_DAWN_HOUR, cfg.getLightingDawnHour());
        assertEquals(DesktopConfig.DEFAULT_LIGHTING_DUSK_HOUR, cfg.getLightingDuskHour());
        assertEquals(7, DesktopConfig.DEFAULT_LIGHTING_DAWN_HOUR);
        assertEquals(20, DesktopConfig.DEFAULT_LIGHTING_DUSK_HOUR);
    }

    @Test
    @DisplayName("the wallpaper and lighting enable flags round-trip independently")
    void enableRoundTrips() {
        cfg.setWallpaperScheduleEnabled(true);
        assertTrue(cfg.isWallpaperScheduleEnabled());
        assertFalse(cfg.isLightingScheduleEnabled(), "lighting is independent of wallpaper");
        assertTrue(cfg.isScheduleEnabled(), "either toggle makes the schedule active");

        cfg.setLightingScheduleEnabled(true);
        cfg.setWallpaperScheduleEnabled(false);
        assertFalse(cfg.isWallpaperScheduleEnabled());
        assertTrue(cfg.isLightingScheduleEnabled());

        cfg.setLightingScheduleEnabled(false);
        assertFalse(cfg.isLightingScheduleEnabled());
        assertFalse(cfg.isScheduleEnabled(), "off only when both toggles are off");
    }

    @Test
    @DisplayName("the wallpaper entry list round-trips and is returned sorted by time")
    void entryListRoundTrip() {
        List<ScheduleEntry> unsorted = new ArrayList<>(List.of(
                new ScheduleEntry(20, 0, "night.jpg"),
                new ScheduleEntry(7, 0, "day.jpg"),
                new ScheduleEntry(13, 30, "noon.jpg")));
        cfg.setWallpaperSchedule(unsorted);

        List<ScheduleEntry> read = cfg.getWallpaperSchedule();
        assertEquals(3, read.size());
        assertEquals("day.jpg", read.get(0).wallpaper());
        assertEquals("noon.jpg", read.get(1).wallpaper());
        assertEquals("night.jpg", read.get(2).wallpaper());
    }

    @Test
    @DisplayName("getWallpaperSchedule returns a defensive copy and set copies its input")
    void entryListIsDefensive() {
        List<ScheduleEntry> source = new ArrayList<>(List.of(new ScheduleEntry(9, 0, "a.jpg")));
        cfg.setWallpaperSchedule(source);

        // Mutating the caller's list after set must not affect the config.
        source.add(new ScheduleEntry(10, 0, "b.jpg"));
        assertEquals(1, cfg.getWallpaperSchedule().size());

        // Mutating the returned list must not affect the config either.
        List<ScheduleEntry> read = cfg.getWallpaperSchedule();
        read.add(new ScheduleEntry(11, 0, "c.jpg"));
        assertEquals(1, cfg.getWallpaperSchedule().size());
        assertNotSame(read, cfg.getWallpaperSchedule());
    }

    @Test
    @DisplayName("a null or empty wallpaper schedule falls back to the defaults")
    void emptyScheduleFallsBackToDefaults() {
        cfg.setWallpaperSchedule(null);
        assertEquals(DesktopConfig.DEFAULT_WALLPAPER_SCHEDULE, cfg.getWallpaperSchedule());
        cfg.setWallpaperSchedule(List.of());
        assertEquals(DesktopConfig.DEFAULT_WALLPAPER_SCHEDULE, cfg.getWallpaperSchedule());
    }

    @Test
    @DisplayName("entry times and wallpapers are clamped/normalised by the value type")
    void entriesClamped() {
        cfg.setWallpaperSchedule(List.of(new ScheduleEntry(99, 120, "  x.jpg  ")));
        ScheduleEntry e = cfg.getWallpaperSchedule().get(0);
        assertEquals(23, e.hour(), "hour clamps to 0-23");
        assertEquals(59, e.minute(), "minute clamps to 0-59");
        assertEquals("x.jpg", e.wallpaper(), "wallpaper is trimmed");
    }

    @Test
    @DisplayName("the lighting dawn/dusk times are independent of the wallpaper entries")
    void lightingTimesIndependent() {
        cfg.setWallpaperSchedule(List.of(new ScheduleEntry(5, 0, "a.jpg")));
        cfg.setLightingDawnHour(6);
        cfg.setLightingDawnMinute(15);
        cfg.setLightingDuskHour(21);
        cfg.setLightingDuskMinute(45);

        assertEquals(6, cfg.getLightingDawnHour());
        assertEquals(15, cfg.getLightingDawnMinute());
        assertEquals(21, cfg.getLightingDuskHour());
        assertEquals(45, cfg.getLightingDuskMinute());
        // The wallpaper entries keep their own, unrelated time.
        assertEquals(5, cfg.getWallpaperSchedule().get(0).hour());
    }

    @Test
    @DisplayName("the lighting dawn/dusk hours and minutes are clamped")
    void lightingTimesClamped() {
        cfg.setLightingDawnHour(-5);
        assertEquals(0, cfg.getLightingDawnHour());
        cfg.setLightingDawnHour(99);
        assertEquals(23, cfg.getLightingDawnHour());
        cfg.setLightingDuskMinute(-1);
        assertEquals(0, cfg.getLightingDuskMinute());
        cfg.setLightingDuskMinute(60);
        assertEquals(59, cfg.getLightingDuskMinute());
    }

    @Test
    @DisplayName("the transition ramp defaults to 30 and clamps to 0-180 minutes")
    void rampMinutesClamped() {
        cfg.resetToDefaults();
        assertEquals(DesktopConfig.DEFAULT_RAMP_MINUTES, cfg.getRampMinutes());
        assertEquals(30, DesktopConfig.DEFAULT_RAMP_MINUTES);
        cfg.setRampMinutes(-10);
        assertEquals(DesktopConfig.MIN_RAMP_MINUTES, cfg.getRampMinutes());
        cfg.setRampMinutes(999);
        assertEquals(DesktopConfig.MAX_RAMP_MINUTES, cfg.getRampMinutes());
        cfg.setRampMinutes(45);
        assertEquals(45, cfg.getRampMinutes(), "an in-range value is kept");
    }

    @Test
    @DisplayName("resetToDefaults restores the schedule fields")
    void resetRestoresSchedule() {
        cfg.setWallpaperScheduleEnabled(true);
        cfg.setLightingScheduleEnabled(true);
        cfg.setWallpaperSchedule(List.of(new ScheduleEntry(3, 0, "x.jpg")));
        cfg.setLightingDawnHour(1);
        cfg.setLightingDuskMinute(15);
        cfg.setRampMinutes(120);
        cfg.resetToDefaults();
        assertFalse(cfg.isWallpaperScheduleEnabled());
        assertFalse(cfg.isLightingScheduleEnabled());
        assertEquals(DesktopConfig.DEFAULT_WALLPAPER_SCHEDULE, cfg.getWallpaperSchedule());
        assertEquals(DesktopConfig.DEFAULT_LIGHTING_DAWN_HOUR, cfg.getLightingDawnHour());
        assertEquals(DesktopConfig.DEFAULT_LIGHTING_DUSK_MINUTE, cfg.getLightingDuskMinute());
        assertEquals(DesktopConfig.DEFAULT_RAMP_MINUTES, cfg.getRampMinutes());
    }
}
