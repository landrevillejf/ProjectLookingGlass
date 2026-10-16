/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.5: the depth-of-field curve and gather, headless.
 *
 * DofKernel is the pure half of DOF -- the reference GpuDofPass's GLSL is checked
 * against -- so what this suite pins is the behaviour a host depends on before a display
 * is ever in the room: the circle of confusion is 0 exactly at the focus distance,
 * rises monotonically and symmetrically with depth error and saturates at 1; the gather
 * radius is 0 at focus and reaches its cap at full defocus; a max radius of 0 is the
 * exact identity; a uniform frame survives ANY depth field untouched (averaging a
 * constant is that constant -- the invariant that proves the disc gather cannot invent
 * detail); a pixel at the focus stays sharp while a far pixel blurs toward its
 * neighbours; and the depth field must cover the frame. The on-screen pass is probe-
 * verified like the shipped bloom; the maths it runs is proven here.
 */
package org.jdesktop.wonderland.renderer.post;

import org.jdesktop.wonderland.renderer.post.DofKernel.Settings;
import org.jdesktop.wonderland.renderer.post.HdrKernel.Frame;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DofKernelTest {

    @Test
    void theDefaultSettingsFocusMidDepthWithAShallowBand() {
        Settings d = Settings.DEFAULT;
        assertThat(d.focusDistance()).isEqualTo(0.5f);
        assertThat(d.focusRange()).isEqualTo(0.3f);
        assertThat(d.maxRadiusPx()).isEqualTo(6f);
    }

    @Test
    void settingsRefuseANegativeFocusOrRadiusAndAZeroBand() {
        assertThatThrownBy(() -> new Settings(-0.1f, 0.3f, 6f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0.5f, 0f, 6f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0.5f, 0.3f, -1f))
                .isInstanceOf(IllegalArgumentException.class);
        // A zero radius is legal: it is the exact "DOF off" identity.
        assertThat(new Settings(0.5f, 0.3f, 0f)).isNotNull();
    }

    @Test
    void theCircleOfConfusionIsZeroAtTheFocusDistance() {
        assertThat(DofKernel.circleOfConfusion(0.5f, Settings.DEFAULT)).isEqualTo(0f);
        assertThat(DofKernel.radiusPx(0.5f, Settings.DEFAULT)).isEqualTo(0);
    }

    @Test
    void theCircleOfConfusionRisesMonotonicallyAndSaturatesAtOne() {
        float prev = -1f;
        for (float d = 0.5f; d <= 2.0f; d += 0.1f) {
            float coc = DofKernel.circleOfConfusion(d, Settings.DEFAULT);
            assertThat(coc).as("depth %s", d).isBetween(0f, 1f);
            assertThat(coc).isGreaterThanOrEqualTo(prev);
            prev = coc;
        }
        // One focusRange away it is fully defocused and clamps there.
        assertThat(DofKernel.circleOfConfusion(0.8f, Settings.DEFAULT)).isCloseTo(1f, within(1e-5f));
        assertThat(DofKernel.circleOfConfusion(5.0f, Settings.DEFAULT)).isEqualTo(1f);
    }

    @Test
    void nearAndFarBlurFallOffSymmetrically() {
        float near = DofKernel.circleOfConfusion(0.3f, Settings.DEFAULT);
        float far = DofKernel.circleOfConfusion(0.7f, Settings.DEFAULT);
        assertThat(near).isCloseTo(far, within(1e-6f));
    }

    @Test
    void theGatherRadiusReachesItsCapAtFullDefocus() {
        assertThat(DofKernel.radiusPx(5.0f, Settings.DEFAULT)).isEqualTo(6);
    }

    @Test
    void aZeroMaxRadiusIsTheExactIdentity() {
        Settings off = new Settings(0.5f, 0.3f, 0f);
        int[] px = {0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFF204060};
        float[] depth = {0.1f, 0.9f, 0.2f, 0.8f};
        Frame out = DofKernel.gather(new Frame(2, 2, px), depth, off);
        assertThat(out.argb()).containsExactly(px);
    }

    @Test
    void aUniformFrameSurvivesAnyDepthFieldUntouched() {
        // Averaging a constant disc is that constant, so a flat wall cannot be blurred
        // into something it is not -- the invariant that proves the gather invents no
        // detail.
        int[] flat = new int[9];
        java.util.Arrays.fill(flat, 0xFF336699);
        float[] depth = {0f, 0.25f, 0.5f, 0.75f, 1f, 0.1f, 0.9f, 0.4f, 0.6f};
        Frame out = DofKernel.gather(new Frame(3, 3, flat), depth, Settings.DEFAULT);
        assertThat(out.argb()).containsExactly(flat);
    }

    @Test
    void aPixelAtTheFocusStaysSharpWhileAFarPixelBlurs() {
        // Centre black at the focus distance, surround white far away: the centre keeps
        // itself (radius 0) and every surrounding pixel gathers the centre into its
        // disc, darkening from 255 toward the 8-white/1-black mean.
        int[] px = new int[9];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        px[4] = 0xFF000000;
        float[] depth = new float[9];
        java.util.Arrays.fill(depth, 0.9f);
        depth[4] = 0.5f;
        Frame out = DofKernel.gather(new Frame(3, 3, px), depth, Settings.DEFAULT);
        assertThat(out.argb()[4]).as("the focus pixel stays black").isEqualTo(0xFF000000);
        for (int i = 0; i < 9; i++) {
            if (i != 4) {
                int red = (out.argb()[i] >> 16) & 0xFF;
                assertThat(red).as("far pixel %d gathered the black centre", i)
                        .isLessThan(255);
            }
        }
    }

    @Test
    void gatherPreservesTheFrameShapeAndRefusesAShortDepthField() {
        int[] px = new int[4];
        Frame out = DofKernel.gather(new Frame(2, 2, px), new float[4], Settings.DEFAULT);
        assertThat(out.width()).isEqualTo(2);
        assertThat(out.height()).isEqualTo(2);
        assertThatThrownBy(() -> DofKernel.gather(new Frame(2, 2, px), new float[3],
                Settings.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DofKernel.gather(new Frame(2, 2, px), null,
                Settings.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
