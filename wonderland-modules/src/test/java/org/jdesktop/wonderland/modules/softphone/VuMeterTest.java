/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d: the peak-follower arithmetic, the latched gain
 * warning, and the 2006 listener string format.
 */
package org.jdesktop.wonderland.modules.softphone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VuMeterTest {

    private static short[] flat(short value) {
        short[] buffer = new short[160];
        java.util.Arrays.fill(buffer, value);
        return buffer;
    }

    @Test
    @DisplayName("full scale reads 1.0, silence reads 0.0")
    void extremes() {
        VuMeter meter = new VuMeter(VuMeter.MICROPHONE);
        assertThat(meter.device()).isEqualTo(VuMeter.MICROPHONE);
        assertThat(new VuMeter(VuMeter.SPEAKER).device()).isEqualTo(VuMeter.SPEAKER);
        assertThat(meter.update(flat(Short.MIN_VALUE))).isCloseTo(1.0, within(0.001));
        meter.reset();
        assertThat(meter.update(flat((short) 0))).isZero();
    }

    @Test
    @DisplayName("an idle needle decays toward silence, never below zero")
    void decay() {
        VuMeter meter = new VuMeter(VuMeter.SPEAKER);
        meter.update(flat((short) 16384));                 // half scale
        assertThat(meter.level()).isCloseTo(0.5, within(0.001));
        meter.update(flat((short) 0));                     // decay x0.75
        assertThat(meter.level()).isCloseTo(0.375, within(0.001));
        meter.update(flat((short) 0));
        assertThat(meter.level()).isCloseTo(0.28125, within(0.001));
    }

    @Test
    @DisplayName("a hot peak latches the gain warning; a quiet one never fires it")
    void gainWarningLatches() {
        VuMeter meter = new VuMeter(VuMeter.MICROPHONE);
        meter.update(flat((short) 29500));                 // just past the 0.90 ratio
        assertThat(meter.isGainTooHigh()).isTrue();
        meter.update(flat((short) 0));                     // decay does NOT un-latch
        assertThat(meter.isGainTooHigh()).isTrue();
        meter.reset();
        assertThat(meter.isGainTooHigh()).isFalse();

        VuMeter quiet = new VuMeter(VuMeter.MICROPHONE);
        quiet.update(flat((short) 1000));
        assertThat(quiet.isGainTooHigh()).isFalse();
    }

    @Test
    @DisplayName("the listener string is the 2006 vocabulary")
    void valueString() {
        VuMeter meter = new VuMeter(VuMeter.MICROPHONE);
        meter.update(flat((short) 0));
        assertThat(meter.valueString()).isEqualTo("Microphone level: 0%");
        meter.reset();
        VuMeter speaker = new VuMeter(VuMeter.SPEAKER);
        speaker.update(flat(Short.MAX_VALUE));
        assertThat(speaker.valueString()).isEqualTo("Speaker level: 100%");
    }

    @Test
    @DisplayName("a louder buffer raises the peak; a quieter one decays it")
    void peakIsMaxOfDecayedAndBuffer() {
        VuMeter meter = new VuMeter(VuMeter.MICROPHONE);
        meter.update(flat((short) 3200));
        double afterFirst = meter.level();
        meter.update(flat((short) 30000));                 // louder: jumps up
        assertThat(meter.level()).isGreaterThan(afterFirst);
        double afterLoud = meter.level();
        meter.update(flat((short) 100));                   // quieter: decays
        assertThat(meter.level()).isBetween(0.0, afterLoud);
    }
}
