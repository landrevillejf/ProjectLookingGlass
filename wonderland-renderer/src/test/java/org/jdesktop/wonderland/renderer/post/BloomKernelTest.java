/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: the bloom maths, headless.
 *
 * Every assert is arithmetic about light: dark pixels contribute nothing,
 * bright ones contribute their colour, the knee is smooth, a blur keeps
 * energy and never rings, and strength zero is the exact identity a
 * "cinematic toggle" will one day lean on. What no test here claims: that a
 * bloom runs on the LIVE 3D canvas -- that route is PostProcessPlan's
 * probe-gated decision, and the ceiling says so out loud.
 */
package org.jdesktop.wonderland.renderer.post;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BloomKernelTest {

    private static int rgb(int r, int g, int b) {
        return (255 << 24) | (r << 16) | (g << 8) | b;
    }

    /** One frame: black everywhere except a white hot-spot at (x, y). */
    private static BloomKernel.Frame spot(int width, int height, int x, int y, int level) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, rgb(0, 0, 0));
        pixels[y * width + x] = rgb(level, level, level);
        return new BloomKernel.Frame(width, height, pixels);
    }

    @Test
    void thePhotometerReadsTheBrightestChannel() {
        assertThat(BloomKernel.luminance(rgb(200, 30, 10))).isEqualTo(200);
        assertThat(BloomKernel.luminance(rgb(0, 0, 0))).isZero();
        assertThat(BloomKernel.luminance(0x00FFFFFF /* alpha ignored */)).isEqualTo(255);
    }

    @Test
    void belowTheKneeNothingContributesAndAboveItTheColourSurvives() {
        int[] pixels = {rgb(40, 20, 20), rgb(0, 0, 0), rgb(200, 100, 50)};
        BloomKernel.Frame frame = new BloomKernel.Frame(3, 1, pixels);

        BloomKernel.Frame glow = BloomKernel.extract(frame, 128f, 0f);

        assertThat(glow.argb()[0] & 0xFFFFFF).as("dark: nothing").isZero();
        assertThat(glow.argb()[1] & 0xFFFFFF).isZero();
        assertThat(glow.argb()[2]).as("hot pixel keeps its exact colour above the knee")
                .isEqualTo(rgb(200, 100, 50));
    }

    @Test
    void theKneeIsSmoothNotAHardRing() {
        // threshold 100, knee 50: 50 and below contribute 0, 150 and above
        // contribute fully, and halfway in between is between -- the
        // smoothstep's own value at the midpoint is exactly half.
        int[] pixels = {rgb(50, 50, 50), rgb(100, 100, 100), rgb(150, 150, 150)};
        BloomKernel.Frame frame = new BloomKernel.Frame(3, 1, pixels);

        BloomKernel.Frame glow = BloomKernel.extract(frame, 100f, 50f);

        assertThat(glow.argb()[0] & 0xFF).isZero();
        int mid = glow.argb()[1] & 0xFF;
        assertThat(mid).as("halfway across the knee is halfway, not clipped or whole")
                .isBetween(45, 55);
        assertThat(glow.argb()[2] & 0xFF).isEqualTo(150);
    }

    @Test
    void theBlurSpreadsLightWithoutInventingMuchOrDestroyingMuch() {
        // Box truth, stated exactly: a radius-1 separable box turns one 255
        // point into a uniform 3x3 block (integer means of 255/3/3 land at
        // 28), nothing beyond it lights, and the energy only ever leaks a
        // rounding floor -- never gains, never vanishes.
        BloomKernel.Frame bright = spot(9, 9, 4, 4, 255);

        BloomKernel.Frame blurred = BloomKernel.blur(bright, 1);

        int total = 0;
        int lit = 0;
        for (int pixel : blurred.argb()) {
            total += BloomKernel.luminance(pixel);
            if (BloomKernel.luminance(pixel) > 0) {
                lit++;
            }
        }
        assertThat(lit).as("the 3x3 footprint of one radius-1 box, and only it")
                .isEqualTo(9);
        assertThat(BloomKernel.luminance(blurred.argb()[4 * 9 + 4]))
                .as("every block pixel carries the same mean -- flat, not peaked")
                .isEqualTo(BloomKernel.luminance(blurred.argb()[4 * 9 + 5]));
        assertThat(total).as("integer means only ever floor, so energy lands just under")
                .isBetween(240, 255);
        assertThat(BloomKernel.luminance(blurred.argb()[7 * 9 + 4]))
                .as("two steps out stayed dark at radius 1")
                .isZero();
    }

    @Test
    void theBlurDoesNotRingTheEdges() {
        // A frame fully lit at the left edge must stay fully lit there: the
        // clamp reads the edge back into itself, so no dark border creeps in.
        int[] pixels = new int[5 * 3];
        java.util.Arrays.fill(pixels, rgb(255, 255, 255));

        BloomKernel.Frame blurred = BloomKernel.blur(
                new BloomKernel.Frame(5, 3, pixels), 2);

        assertThat(blurred.argb()).containsOnly(rgb(255, 255, 255));
    }

    @Test
    void strengthZeroIsTheExactIdentityAndStrengthAddsLight() {
        BloomKernel.Frame base = spot(5, 5, 2, 2, 100);
        BloomKernel.Frame glow = spot(5, 5, 2, 2, 200);

        assertThat(BloomKernel.combine(base, glow, 0f).argb()).isEqualTo(base.argb());

        BloomKernel.Frame added = BloomKernel.combine(base, glow, 1f);
        assertThat(BloomKernel.luminance(added.argb()[2 * 5 + 2]))
                .as("100 + 200 saturates at 255, it does not wrap")
                .isEqualTo(255);
        assertThat(BloomKernel.luminance(added.argb()[0]))
                .as("the dark corner gets nothing it did not have")
                .isZero();
    }

    @Test
    void theWholePassIsItsThreeOperationsSpelledOnce() {
        BloomKernel.Frame bright = spot(9, 9, 4, 4, 255);

        BloomKernel.Frame bloomed = BloomKernel.bloom(bright, 128f, 0f, 1, 1, 0.5f);

        assertThat(BloomKernel.luminance(bloomed.argb()[4 * 9 + 4]))
                .as("the hot-spot itself is brighter than a dark neighbour")
                .isGreaterThan(BloomKernel.luminance(bloomed.argb()[4 * 9 + 5]));
        assertThat(BloomKernel.luminance(bloomed.argb()[4 * 9 + 5]))
                .as("and the neighbour glows a little (half-strength of a third)")
                .isGreaterThan(0);
    }

    @Test
    void aFrameIsARealRectangleAndABlurIsADistance() {
        assertThatThrownBy(() -> new BloomKernel.Frame(0, 5, new int[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BloomKernel.Frame(2, 2, new int[3]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BloomKernel.blur(spot(3, 3, 1, 1, 10), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePlanDecidesTheCeilingSaysAndCarriesItsReason() {
        assertThat(PostProcessPlan.decide(Set.of()).path())
                .isEqualTo(PostProcessPlan.Path.NONE);
        assertThat(PostProcessPlan.decide(null).path())
                .as("a null capability set is a host that asked for nothing")
                .isEqualTo(PostProcessPlan.Path.NONE);

        var wanted = PostProcessPlan.decide(
                Set.of(PostProcessPlan.Capability.BLOOM_WANTED));
        assertThat(wanted.path()).isEqualTo(PostProcessPlan.Path.SOFTWARE_CAPTURE);
        assertThat(wanted.reason()).as("the slow path says WHY in its own words")
                .contains("probe-gated");

        assertThat(PostProcessPlan.decide(Set.of(
                PostProcessPlan.Capability.BLOOM_WANTED,
                PostProcessPlan.Capability.READBACK_PROBED_USABLE)).path())
                .as("evidence, and only evidence, opens the live route")
                .isEqualTo(PostProcessPlan.Path.OFFSCREEN_READBACK);
    }
}
