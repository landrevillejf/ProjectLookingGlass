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

import java.time.LocalTime;

/**
 * Pure, Java 3D-free math for the daylight/nightlight schedule: the day-night
 * blend factor for a given clock time, the per-channel colour interpolation,
 * and the day/night light and tint palettes.
 *
 * <p>Everything here is deterministic (the clock is injected) and free of any
 * jogamp type, so the whole curve is unit-testable in the headless test JVM.
 * The 3D light rig ({@code StandardGlobalLights}) and the 2D night veil
 * ({@code NightTintOverlay}) consume these values but hold no logic of their
 * own.</p>
 *
 * <p>The blend factor is {@code 0.0} in full daylight and {@code 1.0} in full
 * night, ramping linearly across a configurable window centred on each of the
 * two transitions (dawn at the daylight time, dusk at the nightlight time). A
 * window of zero minutes is a hard step.</p>
 */
public final class DayNightCurve {

    // ------------------------------------------------------------------
    // Day palette - identical to the historical StandardGlobalLights rig, so
    // the daytime 3D render is pixel-for-pixel unchanged by this feature.
    // ------------------------------------------------------------------
    /** Daylight ambient light colour (RGB, 0..1). */
    public static final float[] DAY_AMBIENT = {0.40f, 0.40f, 0.40f};
    /** Daylight key directional light colour (RGB, 0..1). */
    public static final float[] DAY_KEY = {0.70f, 0.70f, 0.60f};
    /** Daylight fill directional light colour (RGB, 0..1). */
    public static final float[] DAY_FILL = {0.20f, 0.20f, 0.30f};

    // ------------------------------------------------------------------
    // Night palette - dimmer and shifted toward blue for a "night light" feel.
    // ------------------------------------------------------------------
    /** Nightlight ambient light colour (RGB, 0..1). */
    public static final float[] NIGHT_AMBIENT = {0.12f, 0.14f, 0.22f};
    /** Nightlight key directional light colour (RGB, 0..1). */
    public static final float[] NIGHT_KEY = {0.22f, 0.26f, 0.42f};
    /** Nightlight fill directional light colour (RGB, 0..1). */
    public static final float[] NIGHT_FILL = {0.08f, 0.10f, 0.18f};

    // ------------------------------------------------------------------
    // 2D night veil: a cool, translucent wash painted over the whole desktop.
    // ------------------------------------------------------------------
    /** Night veil colour (RGB, 0..255). */
    public static final int[] NIGHT_TINT_RGB = {10, 14, 40};
    /** Night veil opacity at full night (factor 1.0). */
    public static final int NIGHT_TINT_MAX_ALPHA = 110;

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int HALF_DAY = MINUTES_PER_DAY / 2;

    private DayNightCurve() {
    }

    /**
     * Returns the day-night blend factor at {@code now}: {@code 0.0} in full
     * daylight, {@code 1.0} in full night, ramping linearly across a
     * {@code rampMinutes}-wide window centred on each transition. Handles a
     * daylight window that crosses midnight; a non-positive ramp is a hard step.
     *
     * @param now          the current clock time
     * @param day          the daylight (dawn) transition time
     * @param night        the nightlight (dusk) transition time
     * @param rampMinutes  total width, in minutes, of each transition window
     */
    public static float dayFactor(LocalTime now, LocalTime day, LocalTime night, int rampMinutes) {
        boolean isDay = ScheduleService.isTimeBetween(now, day, night);
        if (rampMinutes <= 0) {
            return isDay ? 0.0f : 1.0f;
        }
        double half = rampMinutes / 2.0;

        // Dusk ramp centred on the nightlight time: day (0) -> night (1).
        double duskOffset = circularOffset(minuteOfDay(now), minuteOfDay(night));
        if (Math.abs(duskOffset) <= half) {
            return (float) clamp01((duskOffset + half) / (2.0 * half));
        }

        // Dawn ramp centred on the daylight time: night (1) -> day (0).
        double dawnOffset = circularOffset(minuteOfDay(now), minuteOfDay(day));
        if (Math.abs(dawnOffset) <= half) {
            return (float) clamp01(1.0 - (dawnOffset + half) / (2.0 * half));
        }

        return isDay ? 0.0f : 1.0f;
    }

    /**
     * Interpolates each channel from {@code day} to {@code night} by {@code t}
     * (clamped to 0..1) and returns a new array; neither input is mutated.
     */
    public static float[] lerp(float[] day, float[] night, float t) {
        float c = clamp01(t);
        float[] out = new float[day.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = day[i] + (night[i] - day[i]) * c;
        }
        return out;
    }

    /** The 2D night-veil opacity (0..{@link #NIGHT_TINT_MAX_ALPHA}) for a factor. */
    public static int tintAlpha(float factor) {
        return Math.round(NIGHT_TINT_MAX_ALPHA * clamp01(factor));
    }

    private static int minuteOfDay(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    /**
     * Signed circular offset, in minutes, from {@code center} to {@code now},
     * normalised to {@code [-720, 720)} so the shortest way around the clock is
     * used regardless of a midnight crossing.
     */
    private static double circularOffset(int nowMinute, int centerMinute) {
        int d = Math.floorMod(nowMinute - centerMinute, MINUTES_PER_DAY);
        if (d >= HALF_DAY) {
            d -= MINUTES_PER_DAY;
        }
        return d;
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
