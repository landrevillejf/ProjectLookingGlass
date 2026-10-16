/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.3: the HDR tone-mapping curve, headless.
 *
 * HdrKernel is the pure half of the tone map -- the reference GpuHdrPass's GLSL is
 * checked against -- so what this suite pins is the maths a host depends on before a
 * display is ever in the room: the identity (an "HDR off" curve is exactly the pixels
 * it was handed), the two invariants that define extended Reinhard (0 maps to 0, the
 * white point maps to 1) and ACES (monotone, bounded, saturating), the Rec.709
 * luminance the whole post chain agrees on, alpha carried through untouched, and the
 * parameter guards. The on-screen pass is probe-verified like the shipped bloom; the
 * curve it runs is proven here.
 */
package org.jdesktop.wonderland.renderer.post;

import org.jdesktop.wonderland.renderer.post.HdrKernel.Frame;
import org.jdesktop.wonderland.renderer.post.HdrKernel.Operator;
import org.jdesktop.wonderland.renderer.post.HdrKernel.Settings;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HdrKernelTest {

    @Test
    void theDefaultSettingsAreTheNeutralStartingPoint() {
        Settings d = Settings.DEFAULT;
        assertThat(d.exposure()).isEqualTo(1.0f);
        assertThat(d.operator()).isEqualTo(Operator.REINHARD);
        assertThat(d.gamma()).isEqualTo(2.2f);
        assertThat(d.whitePoint()).isEqualTo(4.0f);
    }

    @Test
    void settingsRefuseEveryNonPositiveMagnitudeAndANullCurve() {
        assertThatThrownBy(() -> new Settings(0f, Operator.LINEAR, 2.2f, 4f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(-1f, Operator.LINEAR, 2.2f, 4f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1f, null, 2.2f, 4f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1f, Operator.LINEAR, 0f, 4f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1f, Operator.LINEAR, 2.2f, 0f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLinearCurveAtUnityExposureAndGammaIsTheExactIdentity() {
        // Every "HDR off" toggle will one day mean precisely this settings instance,
        // so it must return the pixel it was handed, byte for byte.
        Settings identity = new Settings(1f, Operator.LINEAR, 1f, 4f);
        for (int argb : new int[] {0xFF000000, 0xFFFFFFFF, 0xFF804020, 0x00123456, 0xFF00FF00}) {
            assertThat(HdrKernel.tonemapArgb(argb, identity))
                    .as("identity maps %08X to itself", argb)
                    .isEqualTo(argb);
        }
    }

    @Test
    void reinhardMapsZeroToZeroAndItsWhitePointToOne() {
        assertThat(HdrKernel.reinhard(0f, 4f)).isEqualTo(0f);
        // x(1 + x/w^2)/(1 + x) at x == w is (w + 1)/(w + 1) == 1 exactly.
        assertThat(HdrKernel.reinhard(4f, 4f)).isCloseTo(1.0f, within(1e-6f));
        // x(1 + x/16)/(1 + x) at x == 2 is 2*1.125/3 == 0.75.
        assertThat(HdrKernel.reinhard(2f, 4f)).isCloseTo(0.75f, within(1e-4f));
    }

    @Test
    void reinhardIsStrictlyIncreasing() {
        float prev = -1f;
        for (float x = 0f; x <= 8f; x += 0.25f) {
            float v = HdrKernel.reinhard(x, 4f);
            assertThat(v).as("reinhard monotone at x=%s", x).isGreaterThan(prev);
            prev = v;
        }
    }

    @Test
    void acesIsBoundedMonotoneAndSaturatesTowardWhite() {
        assertThat(HdrKernel.aces(0f)).isEqualTo(0f);
        float prev = -1f;
        for (float x = 0f; x <= 16f; x += 0.5f) {
            float v = HdrKernel.aces(x);
            assertThat(v).isBetween(0f, 1f);
            assertThat(v).as("aces monotone at x=%s", x).isGreaterThanOrEqualTo(prev);
            prev = v;
        }
        // Far above the knee the filmic curve saturates to (clipped) white.
        assertThat(HdrKernel.aces(64f)).isCloseTo(1.0f, within(1e-3f));
    }

    @Test
    void theCurveIsAlwaysInDisplayRangeForEveryOperator() {
        for (Operator op : Operator.values()) {
            Settings s = new Settings(3.0f, op, 2.2f, 4f);
            for (float radiance : new float[] {0f, 0.18f, 0.5f, 1f, 4f, 20f}) {
                assertThat(HdrKernel.curve(radiance, s))
                        .as("%s curve at radiance %s", op, radiance)
                        .isBetween(0f, 1f);
            }
        }
    }

    @Test
    void luminanceUsesRec709Weights() {
        assertThat(HdrKernel.luminance(1f, 1f, 1f)).isCloseTo(1.0f, within(1e-5f));
        assertThat(HdrKernel.luminance(1f, 0f, 0f)).isCloseTo(0.2126f, within(1e-5f));
        assertThat(HdrKernel.luminance(0f, 1f, 0f)).isCloseTo(0.7152f, within(1e-5f));
        assertThat(HdrKernel.luminance(0f, 0f, 1f)).isCloseTo(0.0722f, within(1e-5f));
    }

    @Test
    void toneMappingCarriesAlphaThroughUntouched() {
        Settings s = Settings.DEFAULT;
        int out = HdrKernel.tonemapArgb(0x33CC8844, s);
        assertThat((out >>> 24) & 0xFF)
                .as("tone mapping changes colour, never coverage")
                .isEqualTo(0x33);
    }

    @Test
    void aHigherExposureBrightensAMidTone() {
        int mid = 0xFF404040;
        int dim = HdrKernel.tonemapArgb(mid, new Settings(0.5f, Operator.REINHARD, 1f, 4f));
        int bright = HdrKernel.tonemapArgb(mid, new Settings(2.0f, Operator.REINHARD, 1f, 4f));
        assertThat((bright >> 16) & 0xFF)
                .as("more exposure means a brighter mid tone")
                .isGreaterThan((dim >> 16) & 0xFF);
    }

    @Test
    void tonemappingAFramePreservesItsShapeAndMapsEveryPixel() {
        int[] px = {0xFF000000, 0xFF808080, 0xFFFFFFFF, 0xFF204060};
        Frame src = new Frame(2, 2, px);
        Frame out = HdrKernel.tonemap(src, Settings.DEFAULT);
        assertThat(out.width()).isEqualTo(2);
        assertThat(out.height()).isEqualTo(2);
        assertThat(out.argb()).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(out.argb()[i]).isEqualTo(HdrKernel.tonemapArgb(px[i], Settings.DEFAULT));
        }
    }

    @Test
    void aFrameRefusesADegenerateRectangle() {
        assertThatThrownBy(() -> new Frame(0, 1, new int[1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Frame(2, 2, new int[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
