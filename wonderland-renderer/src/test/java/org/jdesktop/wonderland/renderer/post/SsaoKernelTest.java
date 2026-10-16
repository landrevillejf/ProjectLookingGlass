/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.7: the ambient-occlusion estimate, headless.
 *
 * SsaoKernel is the pure half of SSAO -- the reference GpuSsaoPass's GLSL is checked
 * against -- so what this suite pins is the behaviour a host depends on before a display
 * is ever in the room: the linearisation maps the near and far clips and is monotonic; a
 * FLAT field is unoccluded everywhere (no surface darkens itself); a pit floor beside its
 * own wall IS occluded while the flat middle of that pit is not (the wall blocks the
 * hemisphere of the pixel next to it, and only that pixel); a raised platform beside a
 * lower field is NOT occluded (a bump is not a crevice -- the directional heart of AO);
 * the factor stays in 0..1; a zero intensity is the exact identity; a background at the
 * far clip is untouched. The geometry uses a near/far/fov chosen so the perspective
 * reconstruction is predictable, and every assertion is a strict property a wrong sign, a
 * flipped hemisphere or a missing range-check would break. The on-screen pass is probe-
 * verified like the shipped bloom; the maths it runs is proven here.
 */
package org.jdesktop.wonderland.renderer.post;

import org.jdesktop.wonderland.renderer.post.HdrKernel.Frame;
import org.jdesktop.wonderland.renderer.post.SsaoKernel.Settings;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SsaoKernelTest {

    /** near 1, far 5, fov tangent 1, square pixels, a 1-unit radius at full strength. */
    private static final Settings GEO = new Settings(1f, 5f, 1f, 1f, 1f, 1f, 0.01f);

    @Test
    void theDefaultSettingsMatchTheWorldViewerCameraAtItsMinimumBackClip() {
        Settings d = Settings.DEFAULT;
        assertThat(d.near()).isEqualTo(0.05f);
        assertThat(d.far()).isEqualTo(1000f);
        assertThat(d.tanHalfFovY()).isCloseTo((float) Math.tan(Math.toRadians(22.5)), within(1e-6f));
        assertThat(d.aspect()).isCloseTo(16f / 9f, within(1e-6f));
        assertThat(d.radius()).isEqualTo(0.5f);
        assertThat(d.intensity()).isEqualTo(1f);
        assertThat(d.bias()).isEqualTo(0.025f);
    }

    @Test
    void settingsRejectImpossibleClipsAndNegativeControls() {
        assertThatThrownBy(() -> new Settings(0f, 5f, 1f, 1f, 1f, 1f, 0.01f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(5f, 5f, 1f, 1f, 1f, 1f, 0.01f))
                .as("far must be beyond near").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1f, 5f, 0f, 1f, 1f, 1f, 0.01f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1f, 5f, 1f, 0f, 1f, 1f, 0.01f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1f, 5f, 1f, 1f, 0f, 1f, 0.01f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1f, 5f, 1f, 1f, 1f, -1f, 0.01f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1f, 5f, 1f, 1f, 1f, 1f, -0.1f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHemisphereIsTheFixedTwelvePointKernel() {
        assertThat(SsaoKernel.sampleCount()).isEqualTo(12);
    }

    @Test
    void linearZSpansNearToFarAndIsMonotonic() {
        assertThat(SsaoKernel.linearZ(0f, GEO)).isCloseTo(1f, within(1e-5f));
        assertThat(SsaoKernel.linearZ(1f, GEO)).isCloseTo(5f, within(1e-5f));
        float prev = -1f;
        for (float d = 0f; d <= 1.0001f; d += 0.1f) {
            float z = SsaoKernel.linearZ(Math.min(1f, d), GEO);
            assertThat(z).as("depth %s", d).isGreaterThanOrEqualTo(prev);
            prev = z;
        }
    }

    @Test
    void aFlatDepthFieldIsUnoccludedEverywhere() {
        // No surface darkens itself: a flat field has nothing blocking any hemisphere,
        // so every factor is exactly 1 -- the property a wrong bias or a self-occluding
        // sample would break.
        float[] flat = new float[81];
        java.util.Arrays.fill(flat, 0.5f);
        float[] ao = SsaoKernel.occlusion(flat, 9, 9, GEO);
        for (int i = 0; i < ao.length; i++) {
            assertThat(ao[i]).as("pixel %d on a flat field", i).isEqualTo(1f);
        }
    }

    @Test
    void aPitFloorBesideItsWallIsOccludedButTheFlatMiddleIsNot() {
        // A 5x5 pit (far floor) sunk into a near rim: the pixel at the pit's inner corner
        // has the rim wall beside it blocking part of its hemisphere, while the pixel in
        // the middle of the pit floor has only same-depth neighbours and stays lit.
        float[] pit = new float[81];
        java.util.Arrays.fill(pit, 0f); // rim at the near clip
        for (int y = 2; y <= 6; y++) {
            for (int x = 2; x <= 6; x++) {
                pit[y * 9 + x] = 0.5f; // pit floor, deeper
            }
        }
        float corner = SsaoKernel.occlusionAt(pit, 9, 9, 2, 2, GEO);
        float middle = SsaoKernel.occlusionAt(pit, 9, 9, 4, 4, GEO);
        assertThat(corner).as("the pit wall occludes the floor beside it").isLessThan(1f);
        assertThat(middle).as("the flat middle of the pit is unoccluded").isEqualTo(1f);
        assertThat(corner).isLessThan(middle);
    }

    @Test
    void aRaisedPlatformBesideALowerFieldIsNotOccluded() {
        // The inverse of the pit, and the directional heart of AO: a 3x3 platform raised
        // toward the eye out of a lower field has neighbours that are FARTHER, not nearer,
        // so nothing blocks the hemisphere of a pixel on its edge and it stays fully lit.
        float[] bump = new float[81];
        java.util.Arrays.fill(bump, 0.5f); // lower field, deeper
        for (int y = 3; y <= 5; y++) {
            for (int x = 3; x <= 5; x++) {
                bump[y * 9 + x] = 0f; // platform, raised toward the eye
            }
        }
        assertThat(SsaoKernel.occlusionAt(bump, 9, 9, 3, 3, GEO))
                .as("a bump is not a crevice").isEqualTo(1f);
        assertThat(SsaoKernel.occlusionAt(bump, 9, 9, 4, 4, GEO)).isEqualTo(1f);
    }

    @Test
    void theOcclusionFactorStaysWithinZeroAndOne() {
        float[] varied = new float[64];
        for (int i = 0; i < varied.length; i++) {
            varied[i] = ((i * 37) % 100) / 100f;
        }
        float[] ao = SsaoKernel.occlusion(varied, 8, 8, GEO);
        assertThat(ao).hasSize(64);
        for (float v : ao) {
            assertThat(v).isBetween(0f, 1f);
        }
    }

    @Test
    void aZeroIntensityIsTheExactIdentity() {
        Settings off = new Settings(1f, 5f, 1f, 1f, 1f, 0f, 0.01f);
        int[] px = {0xFF102030, 0xFF405060, 0xFF708090, 0xFFA0B0C0};
        float[] depth = {0.1f, 0.9f, 0.4f, 0.6f};
        Frame out = SsaoKernel.apply(new Frame(2, 2, px), depth, off);
        assertThat(out.argb()).containsExactly(px);
    }

    @Test
    void aBackgroundFieldAtTheFarClipIsUnoccluded() {
        // Everything at the far clip is one flat sky: nothing to occlude, so the frame
        // passes through apply() byte for byte.
        int[] px = new int[9];
        java.util.Arrays.fill(px, 0xFF336699);
        float[] sky = new float[9];
        java.util.Arrays.fill(sky, 1f);
        Frame out = SsaoKernel.apply(new Frame(3, 3, px), sky, GEO);
        assertThat(out.argb()).containsExactly(px);
    }

    @Test
    void applyPreservesTheFrameShapeAndRejectsAShortOrNullDepthField() {
        int[] px = new int[4];
        Frame out = SsaoKernel.apply(new Frame(2, 2, px), new float[4], GEO);
        assertThat(out.width()).isEqualTo(2);
        assertThat(out.height()).isEqualTo(2);
        assertThatThrownBy(() -> SsaoKernel.apply(new Frame(2, 2, px), new float[3], GEO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SsaoKernel.apply(new Frame(2, 2, px), null, GEO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SsaoKernel.occlusion(new float[3], 2, 2, GEO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
