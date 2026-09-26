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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link DayNightCurve}: the day-night blend factor across
 * both transition ramps (including a daylight window that crosses midnight), the
 * per-channel colour interpolation and the 2D veil opacity. All methods are pure
 * and clock-injected, so nothing here touches jogamp.
 */
class DayNightCurveTest {

    private static final float D = 1e-6f;
    private static final LocalTime DAY = LocalTime.of(7, 0);
    private static final LocalTime NIGHT = LocalTime.of(20, 0);

    @Test
    @DisplayName("full daylight is 0 and full night is 1, away from the ramps")
    void flatRegions() {
        assertEquals(0.0f, DayNightCurve.dayFactor(LocalTime.of(12, 0), DAY, NIGHT, 60), D);
        assertEquals(1.0f, DayNightCurve.dayFactor(LocalTime.of(23, 0), DAY, NIGHT, 60), D);
        assertEquals(1.0f, DayNightCurve.dayFactor(LocalTime.of(2, 0), DAY, NIGHT, 60), D);
    }

    @Test
    @DisplayName("the dusk ramp goes 0 -> 1 across the nightlight time")
    void duskRamp() {
        // ramp 60 => half-window 30 min either side of 20:00
        assertEquals(0.0f, DayNightCurve.dayFactor(LocalTime.of(19, 30), DAY, NIGHT, 60), D);
        assertEquals(0.5f, DayNightCurve.dayFactor(LocalTime.of(20, 0), DAY, NIGHT, 60), D);
        assertEquals(1.0f, DayNightCurve.dayFactor(LocalTime.of(20, 30), DAY, NIGHT, 60), D);
        assertEquals(0.25f, DayNightCurve.dayFactor(LocalTime.of(19, 45), DAY, NIGHT, 60), D);
    }

    @Test
    @DisplayName("the dawn ramp goes 1 -> 0 across the daylight time")
    void dawnRamp() {
        assertEquals(1.0f, DayNightCurve.dayFactor(LocalTime.of(6, 30), DAY, NIGHT, 60), D);
        assertEquals(0.5f, DayNightCurve.dayFactor(LocalTime.of(7, 0), DAY, NIGHT, 60), D);
        assertEquals(0.0f, DayNightCurve.dayFactor(LocalTime.of(7, 30), DAY, NIGHT, 60), D);
        assertEquals(0.75f, DayNightCurve.dayFactor(LocalTime.of(6, 45), DAY, NIGHT, 60), D);
    }

    @Test
    @DisplayName("a zero ramp is a hard step at the transition times")
    void hardStep() {
        assertEquals(0.0f, DayNightCurve.dayFactor(LocalTime.of(12, 0), DAY, NIGHT, 0), D);
        assertEquals(1.0f, DayNightCurve.dayFactor(LocalTime.of(23, 0), DAY, NIGHT, 0), D);
        // At the exact boundary the base state decides: 20:00 is inside [07:00,20:00].
        assertEquals(0.0f, DayNightCurve.dayFactor(LocalTime.of(20, 0), DAY, NIGHT, 0), D);
        assertEquals(1.0f, DayNightCurve.dayFactor(LocalTime.of(20, 1), DAY, NIGHT, 0), D);
    }

    @Test
    @DisplayName("the curve is correct when the daylight window crosses midnight")
    void crossingMidnight() {
        // Daylight 22:00 -> 06:00 (an inverted "day" that wraps midnight).
        LocalTime day = LocalTime.of(22, 0);
        LocalTime night = LocalTime.of(6, 0);
        assertEquals(0.0f, DayNightCurve.dayFactor(LocalTime.of(23, 0), day, night, 60), D);
        assertEquals(1.0f, DayNightCurve.dayFactor(LocalTime.of(12, 0), day, night, 60), D);
        assertEquals(0.5f, DayNightCurve.dayFactor(LocalTime.of(22, 0), day, night, 60), D);
        assertEquals(0.5f, DayNightCurve.dayFactor(LocalTime.of(6, 0), day, night, 60), D);
    }

    @Test
    @DisplayName("lerp interpolates each channel and clamps t to 0..1")
    void lerpInterpolates() {
        float[] day = {0.0f, 0.4f, 1.0f};
        float[] night = {1.0f, 0.0f, 0.0f};
        assertArrayEquals(day, DayNightCurve.lerp(day, night, 0.0f), D);
        assertArrayEquals(night, DayNightCurve.lerp(day, night, 1.0f), D);
        assertArrayEquals(new float[] {0.5f, 0.2f, 0.5f},
                DayNightCurve.lerp(day, night, 0.5f), D);
        // Out-of-range t is clamped, and the inputs are never mutated.
        assertArrayEquals(day, DayNightCurve.lerp(day, night, -3.0f), D);
        assertArrayEquals(night, DayNightCurve.lerp(day, night, 9.0f), D);
        assertArrayEquals(new float[] {0.0f, 0.4f, 1.0f}, day, D);
    }

    @Test
    @DisplayName("the real day/night palettes lerp to their endpoints")
    void paletteEndpoints() {
        assertArrayEquals(DayNightCurve.DAY_AMBIENT,
                DayNightCurve.lerp(DayNightCurve.DAY_AMBIENT, DayNightCurve.NIGHT_AMBIENT, 0f), D);
        assertArrayEquals(DayNightCurve.NIGHT_AMBIENT,
                DayNightCurve.lerp(DayNightCurve.DAY_AMBIENT, DayNightCurve.NIGHT_AMBIENT, 1f), D);
    }

    @Test
    @DisplayName("tintAlpha scales the veil opacity and clamps the factor")
    void tintAlphaScales() {
        assertEquals(0, DayNightCurve.tintAlpha(0.0f));
        assertEquals(DayNightCurve.NIGHT_TINT_MAX_ALPHA, DayNightCurve.tintAlpha(1.0f));
        assertEquals(DayNightCurve.NIGHT_TINT_MAX_ALPHA / 2, DayNightCurve.tintAlpha(0.5f));
        assertEquals(0, DayNightCurve.tintAlpha(-2.0f));
        assertEquals(DayNightCurve.NIGHT_TINT_MAX_ALPHA, DayNightCurve.tintAlpha(5.0f));
    }
}
