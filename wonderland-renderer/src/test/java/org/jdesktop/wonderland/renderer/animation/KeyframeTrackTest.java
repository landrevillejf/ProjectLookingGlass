/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/* wonderland-renderer M7c: keyframe sampling math, asserted exhaustively. */
package org.jdesktop.wonderland.renderer.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class KeyframeTrackTest {

    private static KeyframeTrack linear() {
        return new KeyframeTrack(new double[] {0, 1, 3}, new double[] {0, 10, 30},
                KeyframeTrack.Interpolation.LINEAR);
    }

    @Test
    void interpolatesBetweenKeys() {
        assertThat(linear().sampleAt(0.5)).isCloseTo(5.0, within(1e-9));
        assertThat(linear().sampleAt(2.0)).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void hitsKeysExactly() {
        assertThat(linear().sampleAt(1.0)).isEqualTo(10.0);
        assertThat(linear().sampleAt(3.0)).isEqualTo(30.0);
    }

    @Test
    void clampsOutsideTheKeySpanInsteadOfWrapping() {
        // looping is the director's policy; a wrapping track would double-loop
        assertThat(linear().sampleAt(-2.0)).isZero();
        assertThat(linear().sampleAt(99.0)).isEqualTo(30.0);
    }

    @Test
    void stepHoldsThePreviousKey() {
        KeyframeTrack step = new KeyframeTrack(new double[] {0, 2}, new double[] {5, 9},
                KeyframeTrack.Interpolation.STEP);

        assertThat(step.sampleAt(0.0)).isEqualTo(5.0);
        assertThat(step.sampleAt(1.9)).isEqualTo(5.0);
        assertThat(step.sampleAt(2.0)).isEqualTo(9.0);
        assertThat(step.sampleAt(3.0)).isEqualTo(9.0);
    }

    @Test
    void nullInterpolationDefaultsToLinear() {
        KeyframeTrack t = new KeyframeTrack(new double[] {0, 2}, new double[] {0, 4}, null);

        assertThat(t.interpolation()).isEqualTo(KeyframeTrack.Interpolation.LINEAR);
        assertThat(t.sampleAt(1.0)).isEqualTo(2.0);
    }

    @Test
    void mismatchedArraysAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new KeyframeTrack(
                new double[] {0, 1}, new double[] {1}, KeyframeTrack.Interpolation.LINEAR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyTrackSamplesZeroAndSpansNothing() {
        KeyframeTrack empty = new KeyframeTrack(new double[0], new double[0],
                KeyframeTrack.Interpolation.LINEAR);

        assertThat(empty.keyCount()).isZero();
        assertThat(empty.sampleAt(1.5)).isZero();
        assertThat(empty.startTime()).isZero();
        assertThat(empty.endTime()).isZero();
    }

    @Test
    void aSingleKeyTrackIsAConstant() {
        KeyframeTrack one = new KeyframeTrack(new double[] {2}, new double[] {7},
                KeyframeTrack.Interpolation.LINEAR);

        assertThat(one.sampleAt(0.0)).isEqualTo(7.0);
        assertThat(one.sampleAt(10.0)).isEqualTo(7.0);
        assertThat(one.constantValue()).isEqualTo(7.0);
        assertThat(one.startTime()).isEqualTo(2.0);
        assertThat(one.endTime()).isEqualTo(2.0);
    }

    @Test
    void duplicateTimestampsFormAJumpDiscontinuity() {
        KeyframeTrack dup = new KeyframeTrack(new double[] {0, 1, 1, 2}, new double[] {0, 4, 6, 8},
                KeyframeTrack.Interpolation.LINEAR);

        // sampling exactly AT the duplicated time already returns the later
        // key's value: the jump fires at the key, the same right-continuous
        // convention STEP follows, so the interpolations cannot disagree
        assertThat(dup.sampleAt(1.0)).isEqualTo(6.0);
        assertThat(dup.sampleAt(1.001)).isCloseTo(6.0, within(0.01));
        assertThat(dup.sampleAt(0.5)).isBetween(0.0, 8.0);
    }

    @Test
    void defensiveCopiesKeepCallersFromCorruptingTracks() {
        double[] times = {0, 1};
        double[] values = {0, 10};
        KeyframeTrack t = new KeyframeTrack(times, values, KeyframeTrack.Interpolation.LINEAR);
        times[1] = 99;
        values[1] = 99;

        assertThat(t.sampleAt(1.0)).isEqualTo(10.0);
    }
}
