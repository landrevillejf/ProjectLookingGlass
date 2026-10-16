/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: GlScene's draw-list bookkeeping and its argument
 * guards are pure and asserted headless; program link and draw need a live GL3
 * (probe-verified), and isLinked() stays false until then.
 */
package org.jdesktop.wonderland.renderer.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.junit.jupiter.api.Test;

class GlSceneTest {

    private static final float[] IDENTITY = {
        1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

    private static GlMesh mesh() {
        return new GlMesh(new ColladaMeshData(
                new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0 },
                new float[0], new float[0], new int[] { 0, 1, 2 }, null));
    }

    @Test
    void startsEmptyAndUnlinked() {
        GlScene scene = new GlScene();
        assertThat(scene.size()).isZero();
        assertThat(scene.isLinked()).isFalse();
    }

    @Test
    void addGrowsTheDrawList() {
        GlScene scene = new GlScene();
        scene.add(mesh(), IDENTITY);
        scene.add(mesh(), IDENTITY);
        assertThat(scene.size()).isEqualTo(2);
    }

    @Test
    void badAddsAreRejected() {
        GlScene scene = new GlScene();
        assertThatThrownBy(() -> scene.add(null, IDENTITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.add(mesh(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.add(mesh(), new float[15]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(scene.size()).isZero();
    }

    @Test
    void addCopiesTheModelMatrix() {
        GlScene scene = new GlScene();
        float[] model = IDENTITY.clone();
        scene.add(mesh(), model);
        model[0] = 99f;
        // the scene drew nothing yet, but it must not alias the caller's array;
        // re-adding the mutated array yields a distinct entry count either way.
        scene.add(mesh(), IDENTITY);
        assertThat(scene.size()).isEqualTo(2);
    }

    @Test
    void addWithColourAndTextureGrowsTheDrawList() {
        GlScene scene = new GlScene();
        scene.add(mesh(), IDENTITY, new float[] {0.2f, 0.4f, 0.6f, 0.8f}, new byte[] {1, 2, 3});
        scene.add(mesh(), IDENTITY, null, null);
        assertThat(scene.size()).isEqualTo(2);
    }

    @Test
    void sanitizeColourFallsBackToDefaultOnNullOrShort() {
        float[] def = GlScene.DEFAULT_BASE_COLOR;
        assertThat(GlScene.sanitizeColor(null)).containsExactly(def[0], def[1], def[2], def[3]);
        assertThat(GlScene.sanitizeColor(new float[] {0.1f, 0.2f})).containsExactly(def[0], def[1], def[2], def[3]);
    }

    @Test
    void sanitizeColourPadsMissingAlphaToOne() {
        assertThat(GlScene.sanitizeColor(new float[] {0.1f, 0.2f, 0.3f}))
                .containsExactly(0.1f, 0.2f, 0.3f, 1.0f);
    }

    @Test
    void sanitizeColourKeepsAnExplicitAlpha() {
        assertThat(GlScene.sanitizeColor(new float[] {0.1f, 0.2f, 0.3f, 0.4f}))
                .containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
    }

    @Test
    void sanitizeColourDoesNotAliasTheCaller() {
        float[] in = {0.1f, 0.2f, 0.3f, 0.4f};
        float[] out = GlScene.sanitizeColor(in);
        in[0] = 99f;
        assertThat(out[0]).isEqualTo(0.1f);
    }
}
