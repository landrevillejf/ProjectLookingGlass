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
package org.jdesktop.lg3d.apps.tuner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link TuningMeter}: the pure needle/in-tune geometry helpers, the
 * eased reading state machine, the range/tolerance clamping, and that the custom
 * Java&nbsp;2D painting runs without throwing when rendered into an offscreen
 * {@link BufferedImage} (no display, no peer).
 */
class TuningMeterTest {

    private static final float EPS = 0.01f;

    @Test
    @DisplayName("the needle fraction maps -range..+range onto 0..1, centred at 0.5")
    void needleFractionSpansTheScale() {
        assertEquals(0.5f, TuningMeter.needleFraction(0f, 50f), EPS);
        assertEquals(0f, TuningMeter.needleFraction(-50f, 50f), EPS);
        assertEquals(1f, TuningMeter.needleFraction(50f, 50f), EPS);
        assertEquals(0.75f, TuningMeter.needleFraction(25f, 50f), EPS);
    }

    @Test
    @DisplayName("the needle fraction clamps beyond full scale and survives bad input")
    void needleFractionClampsAndGuards() {
        assertEquals(0f, TuningMeter.needleFraction(-1000f, 50f), EPS);
        assertEquals(1f, TuningMeter.needleFraction(1000f, 50f), EPS);
        assertEquals(0.5f, TuningMeter.needleFraction(Float.NaN, 50f), EPS);
        assertEquals(0.5f, TuningMeter.needleFraction(Float.POSITIVE_INFINITY, 50f), EPS);
        assertEquals(0.5f, TuningMeter.needleFraction(10f, 0f), EPS,
                "a non-positive range centres the needle");
        assertEquals(0.5f, TuningMeter.needleFraction(10f, -20f), EPS);
    }

    @Test
    @DisplayName("the in-tune helper is symmetric and rejects NaN")
    void inTuneHelper() {
        assertTrue(TuningMeter.isInTune(0f, 5f));
        assertTrue(TuningMeter.isInTune(5f, 5f));
        assertTrue(TuningMeter.isInTune(-5f, 5f));
        assertFalse(TuningMeter.isInTune(6f, 5f));
        assertFalse(TuningMeter.isInTune(-6f, 5f));
        assertFalse(TuningMeter.isInTune(Float.NaN, 5f));
        assertTrue(TuningMeter.isInTune(10f, -50f), "the tolerance magnitude is what matters");
    }

    @Test
    @DisplayName("a fresh meter idles at the centre, unvoiced and out of tune")
    void defaultsAreIdle() {
        TuningMeter meter = new TuningMeter();
        assertEquals(0f, meter.getDisplayCents(), EPS);
        assertFalse(meter.isVoiced());
        assertFalse(meter.isInTune());
        assertEquals(TuningMeter.DEFAULT_RANGE_CENTS, meter.getRangeCents(), EPS);
        assertEquals(TuningMeter.DEFAULT_TOLERANCE_CENTS, meter.getToleranceCents(), EPS);
    }

    @Test
    @DisplayName("a voiced reading eases the needle toward the deviation and flags in-tune")
    void setReadingEasesTowardTarget() {
        TuningMeter meter = new TuningMeter();
        meter.setReading(50f, true);
        assertTrue(meter.isVoiced());
        // One ease step from 0 toward 50 at EASE=0.45 lands at 22.5, not 50.
        assertEquals(22.5f, meter.getDisplayCents(), 0.5f);
        // Feeding the same target repeatedly converges toward it.
        for (int i = 0; i < 40; i++) {
            meter.setReading(50f, true);
        }
        assertEquals(50f, meter.getDisplayCents(), 0.5f);
        assertFalse(meter.isInTune(), "50 cents is well outside the default 5-cent window");
    }

    @Test
    @DisplayName("a small deviation flags the reading as in tune")
    void smallDeviationIsInTune() {
        TuningMeter meter = new TuningMeter();
        meter.setToleranceCents(5f);
        meter.setReading(3f, true);
        assertTrue(meter.isInTune());
        meter.setReading(30f, true);
        assertFalse(meter.isInTune());
    }

    @Test
    @DisplayName("an unvoiced reading idles the needle back toward the centre")
    void unvoicedReadingIdles() {
        TuningMeter meter = new TuningMeter();
        meter.setReading(40f, true);
        meter.setReading(0f, false);
        assertFalse(meter.isVoiced());
        assertFalse(meter.isInTune());
        for (int i = 0; i < 40; i++) {
            meter.setReading(0f, false);
        }
        assertEquals(0f, meter.getDisplayCents(), EPS);
    }

    @Test
    @DisplayName("reset returns the needle to the idle centre")
    void resetIdles() {
        TuningMeter meter = new TuningMeter();
        meter.setReading(45f, true);
        meter.reset();
        assertEquals(0f, meter.getDisplayCents(), EPS);
        assertFalse(meter.isVoiced());
        assertFalse(meter.isInTune());
    }

    @Test
    @DisplayName("a NaN reading never poisons the needle")
    void nanReadingIsIgnored() {
        TuningMeter meter = new TuningMeter();
        meter.setReading(Float.NaN, true);
        assertFalse(meter.isInTune());
        assertTrue(Float.isFinite(meter.getDisplayCents()));
    }

    @Test
    @DisplayName("the range clamps to the default when non-positive; tolerance clamps at zero")
    void rangeAndToleranceClamp() {
        TuningMeter meter = new TuningMeter();
        meter.setRangeCents(100f);
        assertEquals(100f, meter.getRangeCents(), EPS);
        meter.setRangeCents(-10f);
        assertEquals(TuningMeter.DEFAULT_RANGE_CENTS, meter.getRangeCents(), EPS);
        meter.setRangeCents(0f);
        assertEquals(TuningMeter.DEFAULT_RANGE_CENTS, meter.getRangeCents(), EPS);

        meter.setToleranceCents(7f);
        assertEquals(7f, meter.getToleranceCents(), EPS);
        meter.setToleranceCents(-5f);
        assertEquals(0f, meter.getToleranceCents(), EPS);
    }

    @Test
    @DisplayName("the custom Java 2D painting renders offscreen without throwing")
    void paintsHeadless() {
        TuningMeter meter = new TuningMeter();
        meter.setSize(420, 120);
        meter.setReading(-12f, true);
        BufferedImage image = new BufferedImage(420, 120, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            assertDoesNotThrow(() -> meter.paintComponent(g));
        } finally {
            g.dispose();
        }
        // Something was actually drawn (the backdrop is not fully transparent).
        assertTrue((image.getRGB(210, 60) & 0xFF000000) != 0,
                "the meter paints an opaque backdrop");
    }
}
