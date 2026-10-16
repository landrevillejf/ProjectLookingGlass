/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.4: the FXAA subpixel stage, headless.
 *
 * FxaaKernel is the pure half of the anti-alias -- the reference GpuFxaaPass's GLSL is
 * checked against -- so what this suite pins is the behaviour a host depends on before
 * a display is ever in the room: a flat frame comes back byte-for-byte unchanged (the
 * claim that matters most, because an anti-alias that smears a uniform wall is worse
 * than none), the local-contrast gate fires only above BOTH the absolute floor and the
 * fraction of the brightest luma, the subpixel blend weight is bounded by the quality
 * ceiling and vanishes when quality is 0, an isolated impulse is pulled toward its
 * neighbourhood average by exactly the published weight, alpha is carried through, and
 * the parameter guards hold. The on-screen pass is probe-verified like the shipped
 * bloom; the maths it runs is proven here.
 */
package org.jdesktop.wonderland.renderer.post;

import org.jdesktop.wonderland.renderer.post.FxaaKernel.Settings;
import org.jdesktop.wonderland.renderer.post.HdrKernel.Frame;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FxaaKernelTest {

    @Test
    void theDefaultSettingsAreTheFxaa311QualityPreset() {
        Settings d = Settings.DEFAULT;
        assertThat(d.edgeThreshold()).isEqualTo(0.125f);
        assertThat(d.edgeThresholdMin()).isCloseTo(0.0312f, within(1e-4f));
        assertThat(d.subpixQuality()).isEqualTo(0.75f);
    }

    @Test
    void settingsRefuseAnOutOfRangeThresholdOrQualityAndANegativeFloor() {
        assertThatThrownBy(() -> new Settings(0f, 0f, 0.75f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1.5f, 0f, 0.75f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0.125f, -0.1f, 0.75f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0.125f, 0f, -0.1f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0.125f, 0f, 1.5f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFlatFrameIsReturnedByteForByteUnchanged() {
        // The hardest claim: a uniform wall must not shimmer or smear. Every pixel is
        // its own neighbours' average, so the gate spares the whole frame.
        int[] flat = new int[16];
        java.util.Arrays.fill(flat, 0xFF404040);
        Frame out = FxaaKernel.antialias(new Frame(4, 4, flat), Settings.DEFAULT);
        assertThat(out.argb()).containsExactly(flat);
    }

    @Test
    void theGateFiresOnlyAboveTheAbsoluteFloorAndTheLumaFraction() {
        // A strong black/white step is an edge; a whisper of contrast on a dark patch
        // is not, because the floor exists so a dark room does not crawl.
        assertThat(FxaaKernel.isEdge(0f, 1f, 1f, 1f, 1f, Settings.DEFAULT)).isTrue();
        assertThat(FxaaKernel.isEdge(0.10f, 0.11f, 0.11f, 0.11f, 0.11f, Settings.DEFAULT))
                .as("below both the floor and the max-luma fraction is not an edge")
                .isFalse();
        assertThat(FxaaKernel.contrastRange(0.2f, 0.2f, 0.2f, 0.2f, 0.2f))
                .isEqualTo(0f);
    }

    @Test
    void aNonEdgePixelCarriesNoBlendWeightAndQualityZeroDisablesTheStage() {
        // Flat: no weight whatever the settings.
        assertThat(FxaaKernel.blendWeight(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, Settings.DEFAULT))
                .isEqualTo(0f);
        // A real edge but with subpixQuality 0: the ceiling clamps the blend to nothing,
        // which is the exact "anti-alias off, keep the edge detection" toggle.
        Settings noSubpix = new Settings(0.125f, 0.0312f, 0f);
        assertThat(FxaaKernel.blendWeight(0f, 1f, 1f, 1f, 1f, noSubpix)).isEqualTo(0f);
    }

    @Test
    void aZeroContrastRangeBlendsNothingAndIsNeverADivisor() {
        // With the absolute floor at 0 an all-black patch clears the gate (range 0 is
        // >= max(0, 0 * threshold)), but there is no contrast to blend over: the
        // range <= 0 guard returns 0 instead of dividing rangeL by a zero range at the
        // subpixel step. A dark room must not shimmer, and must not yield a NaN weight.
        Settings zeroFloor = new Settings(0.125f, 0f, 0.75f);
        assertThat(FxaaKernel.isEdge(0f, 0f, 0f, 0f, 0f, zeroFloor))
                .as("a zero floor lets a flat black patch through the gate")
                .isTrue();
        assertThat(FxaaKernel.blendWeight(0f, 0f, 0f, 0f, 0f, zeroFloor))
                .as("but a zero contrast range has nothing to blend, and is never a divisor")
                .isEqualTo(0f);
    }

    @Test
    void theBlendWeightIsBoundedByTheQualityCeiling() {
        for (float q : new float[] {0.25f, 0.5f, 0.75f, 1.0f}) {
            Settings s = new Settings(0.125f, 0.0312f, q);
            // An isolated dark impulse on a bright field is the maximum-contrast case.
            float w = FxaaKernel.blendWeight(0f, 1f, 1f, 1f, 1f, s);
            assertThat(w).as("quality %s", q).isBetween(0f, q);
        }
    }

    @Test
    void anIsolatedImpulseIsPulledTowardItsAverageByThePublishedWeight() {
        // Centre black, all four neighbours white: lumaL = 1, rangeL = 1, range = 1,
        // so blend = max(0, 1 - 0.25) * 1/(1-0.25) = 1.0, clamped to quality 0.75.
        // mix(0, 255, 0.75) rounds to 191 (0xBF).
        int out = FxaaKernel.antialiasArgb(0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, Settings.DEFAULT);
        assertThat(out & 0x00FFFFFF).isEqualTo(0xBFBFBF);
        assertThat((out >>> 24) & 0xFF).as("alpha carried through").isEqualTo(0xFF);
    }

    @Test
    void antiAliasingAnImpulseTouchesOnlyTheImpulse() {
        // A single dark pixel in a bright 3x3 field: the impulse is lifted, and every
        // surrounding pixel -- whose centre already equals its neighbours' average to
        // within the trim -- is left exactly as it was.
        int[] px = new int[9];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        px[4] = 0xFF000000;
        Frame out = FxaaKernel.antialias(new Frame(3, 3, px), Settings.DEFAULT);
        assertThat(out.argb()[4]).isNotEqualTo(0xFF000000);
        for (int i = 0; i < 9; i++) {
            if (i != 4) {
                assertThat(out.argb()[i]).as("pixel %d is not the impulse", i)
                        .isEqualTo(0xFFFFFFFF);
            }
        }
    }

    @Test
    void antiAliasingCarriesAlphaThroughAndPreservesTheFrameShape() {
        int[] px = {0x00000000, 0x33FFFFFF, 0xFFFFFFFF, 0x80000000,
                0xFFFFFFFF, 0x00000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
        Frame src = new Frame(3, 3, px);
        Frame out = FxaaKernel.antialias(src, Settings.DEFAULT);
        assertThat(out.width()).isEqualTo(3);
        assertThat(out.height()).isEqualTo(3);
        assertThat(out.argb()).hasSize(9);
        // The centre impulse's alpha is whatever the centre carried, never a neighbour's.
        assertThat((out.argb()[4] >>> 24) & 0xFF)
                .isEqualTo((px[4] >>> 24) & 0xFF);
    }
}
