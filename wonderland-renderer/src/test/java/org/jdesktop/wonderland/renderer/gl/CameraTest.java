/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: the camera maths is pure, so it is asserted here
 * headless -- the one part of the JOGL scene core that needs no GL context.
 */
package org.jdesktop.wonderland.renderer.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CameraTest {

    private static final float EPS = 1e-4f;

    @Test
    void lookingDownMinusZWithUpYIsATranslationOnlyView() {
        // eye (0,0,5) looking at the origin with +Y up: the view rotation is the
        // identity and the world slides to -z, so the origin lands at view z -5.
        Camera c = new Camera(0, 0, 5, 0, 0, 0, 0, 1, 0,
                (float) Math.toRadians(60), 16f / 9f, 0.1f, 100f);
        float[] v = c.view();
        assertThat(v[0]).isCloseTo(1f, within(EPS));
        assertThat(v[5]).isCloseTo(1f, within(EPS));
        assertThat(v[10]).isCloseTo(1f, within(EPS));
        assertThat(v[14]).isCloseTo(-5f, within(EPS));
        assertThat(v[15]).isCloseTo(1f, within(EPS));
    }

    @Test
    void projectionCarriesThePerspectiveSignature() {
        float fov = (float) Math.toRadians(90);
        Camera c = new Camera(0, 0, 0, 0, 0, -1, 0, 1, 0, fov, 2f, 1f, 11f);
        float[] p = c.projection();
        // fov 90 -> tan(half)=1, so m[5] = 1; aspect 2 -> m[0] = 1/2.
        assertThat(p[5]).isCloseTo(1f, within(EPS));
        assertThat(p[0]).isCloseTo(0.5f, within(EPS));
        // the perspective divide row
        assertThat(p[11]).isCloseTo(-1f, within(EPS));
        assertThat(p[15]).isCloseTo(0f, within(EPS));
        // -(f+n)/(f-n) and -2fn/(f-n) for n=1, f=11
        assertThat(p[10]).isCloseTo(-1.2f, within(EPS));
        assertThat(p[14]).isCloseTo(-2.2f, within(EPS));
    }

    @Test
    void matricesAreCopiedNotShared() {
        Camera c = new Camera(0, 0, 5, 0, 0, 0, 0, 1, 0, 1f, 1f, 0.1f, 10f);
        float[] a = c.view();
        a[0] = 999f;
        assertThat(c.view()[0]).isNotEqualTo(999f);
    }

    @Test
    void degenerateLensesAreRejected() {
        assertThatThrownBy(() -> new Camera(0, 0, 5, 0, 0, 0, 0, 1, 0, 1f, 0f, 0.1f, 10f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Camera(0, 0, 5, 0, 0, 0, 0, 1, 0, 1f, 1f, 0f, 10f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Camera(0, 0, 5, 0, 0, 0, 0, 1, 0, 1f, 1f, 10f, 10f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Camera(0, 0, 5, 0, 0, 0, 0, 1, 0, 0f, 1f, 0.1f, 10f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Camera(0, 0, 5, 0, 0, 0, 0, 1, 0,
                (float) Math.PI, 1f, 0.1f, 10f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void degeneratePosesAreRejected() {
        // eye == target
        assertThatThrownBy(() -> new Camera(1, 1, 1, 1, 1, 1, 0, 1, 0, 1f, 1f, 0.1f, 10f))
                .isInstanceOf(IllegalArgumentException.class);
        // up parallel to the view direction
        assertThatThrownBy(() -> new Camera(0, 0, 0, 0, 0, -1, 0, 0, -1, 1f, 1f, 0.1f, 10f))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
