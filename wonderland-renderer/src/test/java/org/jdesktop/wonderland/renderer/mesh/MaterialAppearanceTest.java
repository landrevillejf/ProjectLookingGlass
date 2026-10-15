/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P0.1: the lighting model of a material, headless.
 *
 * MaterialAppearance is a pure value, so what it decides about shading -- the
 * faithful unlit default, the per-material opt-in, the fallbacks for a missing
 * ambient/specular/emissive triple, the defensive copy that keeps it immutable
 * -- is all testable without a Java 3D node. The Java 3D Material that J3dMeshFactory
 * builds from these numbers is probe-only (it needs the native stack); this suite
 * pins the *decision* the factory reads, and that a material asking not to be lit
 * carries no lighting information that could ever reach the raster.
 */
package org.jdesktop.wonderland.renderer.mesh;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MaterialAppearanceTest {

    @Test
    void theFiveArgumentMaterialIsUnlitAndCarriesNoShadingToApply() {
        MaterialAppearance ma = new MaterialAppearance(0.9f, 0.1f, 0.1f, 1f, "wall.png");

        assertThat(ma.lit()).as("the faithful pre-lit default is unlit").isFalse();
        assertThat(ma.rgba()).containsExactly(0.9f, 0.1f, 0.1f, 1f);
        assertThat(ma.texturePath()).isEqualTo("wall.png");
        // Even unlit it stays a well-formed value: ambient falls back to the
        // diffuse so a later opt-in is never invisible in shadow.
        assertThat(ma.ambient()).containsExactly(0.9f, 0.1f, 0.1f);
        assertThat(ma.emissive()).containsExactly(0f, 0f, 0f);
    }

    @Test
    void aLitMaterialKeepsEveryChannelTheCallerGaveIt() {
        MaterialAppearance ma = new MaterialAppearance(0.2f, 0.4f, 0.6f, 1f, null,
                true, new float[] {0.1f, 0.1f, 0.1f},
                new float[] {0.8f, 0.8f, 0.8f}, 32f, new float[] {0.05f, 0.05f, 0.05f});

        assertThat(ma.lit()).isTrue();
        assertThat(ma.ambient()).containsExactly(0.1f, 0.1f, 0.1f);
        assertThat(ma.specular()).containsExactly(0.8f, 0.8f, 0.8f);
        assertThat(ma.emissive()).containsExactly(0.05f, 0.05f, 0.05f);
        assertThat(ma.shininess()).isCloseTo(32f, within(0f));
    }

    @Test
    void theLightingArraysAreCopiedSoTheValueStaysImmutable() {
        float[] ambient = {0.5f, 0.5f, 0.5f};
        MaterialAppearance ma = new MaterialAppearance(1f, 1f, 1f, 1f, null,
                true, ambient, null, 0f, null);
        ambient[0] = 0f; // the caller's array must not reach inside the value

        assertThat(ma.ambient()).as("defensive copy on the way in")
                .containsExactly(0.5f, 0.5f, 0.5f);
    }

    @Test
    void aMissingOrNullTripleFallsBackToASaneShadingDefault() {
        MaterialAppearance ma = new MaterialAppearance(0.3f, 0.3f, 0.3f, 1f, null,
                true, null, null, 0f, null);

        assertThat(ma.ambient()).as("null ambient becomes the diffuse colour")
                .containsExactly(0.3f, 0.3f, 0.3f);
        assertThat(ma.specular()).as("null specular is a neutral highlight")
                .containsExactly(0.2f, 0.2f, 0.2f);
        assertThat(ma.emissive()).as("null emissive is black")
                .containsExactly(0f, 0f, 0f);
    }

    @Test
    void aShortArrayIsTreatedAsMissingRatherThanReadOffTheEnd() {
        MaterialAppearance ma = new MaterialAppearance(1f, 1f, 1f, 1f, null,
                true, new float[] {0.5f, 0.5f}, null, 0f, null);

        assertThat(ma.ambient()).as("only three or more elements count as a colour")
                .containsExactly(1f, 1f, 1f); // falls back to diffuse
    }

    @Test
    void aNegativeShininessIsClampedToTheMaterialRange() {
        MaterialAppearance ma = new MaterialAppearance(1f, 1f, 1f, 1f, null,
                true, null, null, -5f, null);

        assertThat(ma.shininess()).as("Java 3D shininess is never negative")
                .isCloseTo(0f, within(0f));
    }

    @Test
    void theStringCarriesTheLitFlagSoALogLineSaysWhetherASurfaceIsShaded() {
        assertThat(new MaterialAppearance(1f, 1f, 1f, 1f, null).toString())
                .contains("lit=false");
        assertThat(new MaterialAppearance(1f, 1f, 1f, 1f, null,
                true, null, null, 0f, null).toString()).contains("lit=true");
    }

    // ---- roadmap P2.1: multi-layer materials -------------------------------

    @Test
    void theShortConstructorsCarryNoExtraLayers() {
        MaterialAppearance plain = new MaterialAppearance(1f, 1f, 1f, 1f, "diffuse.png");
        assertThat(plain.hasNormalMap()).isFalse();
        assertThat(plain.hasSpecularMap()).isFalse();
        assertThat(plain.normalMapPath()).isNull();
        assertThat(plain.specularMapPath()).isNull();
        assertThat(plain.layerCount()).isEqualTo(1);

        MaterialAppearance litOnly = new MaterialAppearance(1f, 1f, 1f, 1f, null,
                true, null, null, 0f, null);
        assertThat(litOnly.layerCount()).as("lit flags are not texture layers")
                .isZero();
    }

    @Test
    void theFullConstructorKeepsEveryNamedLayer() {
        MaterialAppearance ma = new MaterialAppearance(1f, 1f, 1f, 1f,
                "diffuse.png", false, null, null, 0f, null,
                "wall-normal.png", "wall-spec.png");

        assertThat(ma.texturePath()).isEqualTo("diffuse.png");
        assertThat(ma.normalMapPath()).isEqualTo("wall-normal.png");
        assertThat(ma.specularMapPath()).isEqualTo("wall-spec.png");
        assertThat(ma.hasNormalMap()).isTrue();
        assertThat(ma.hasSpecularMap()).isTrue();
        assertThat(ma.layerCount()).isEqualTo(3);
    }

    @Test
    void aBlankLayerPathIsNoLayer() {
        MaterialAppearance ma = new MaterialAppearance(1f, 1f, 1f, 1f, null,
                false, null, null, 0f, null, "   ", "");

        assertThat(ma.hasNormalMap()).as("whitespace is not a resolved path").isFalse();
        assertThat(ma.hasSpecularMap()).isFalse();
        assertThat(ma.normalMapPath()).isNull();
    }

    @Test
    void theStringNamesTheLayersSoALogLineShowsWhatWasResolved() {
        MaterialAppearance ma = new MaterialAppearance(1f, 1f, 1f, 1f, "d.png",
                false, null, null, 0f, null, "n.png", null);

        assertThat(ma.toString())
                .contains("normalMap=n.png")
                .contains("specularMap=null");
    }

    // ---- roadmap P2.5: environment reflection / refraction ----------------

    @Test
    void theShortAndTwelveArgConstructorsCarryNoEnvironment() {
        // Adding P2.5 must not disturb a pre-P2.5 material: no cube map, no
        // bend, no environment weight, whatever constructor the caller used.
        MaterialAppearance plain = new MaterialAppearance(1f, 1f, 1f, 1f, "diffuse.png");
        MaterialAppearance layered = new MaterialAppearance(1f, 1f, 1f, 1f, "d.png",
                false, null, null, 0f, null, "n.png", "s.png");

        for (MaterialAppearance ma : new MaterialAppearance[] {plain, layered}) {
            assertThat(ma.hasEnvironmentMap()).isFalse();
            assertThat(ma.environmentMapPath()).isNull();
            assertThat(ma.hasRefraction()).as("ior 1 bends nothing").isFalse();
            assertThat(ma.refractionIor()).isCloseTo(1f, within(0f));
            assertThat(ma.reflectivity()).isZero();
        }
    }

    @Test
    void theFullConstructorKeepsEveryEnvironmentField() {
        MaterialAppearance ma = new MaterialAppearance(1f, 1f, 1f, 1f, "pond.png",
                false, null, null, 0f, null, null, null, "sky.png", 0.6f, 1.33f);

        assertThat(ma.environmentMapPath()).isEqualTo("sky.png");
        assertThat(ma.hasEnvironmentMap()).isTrue();
        assertThat(ma.reflectivity()).isCloseTo(0.6f, within(0f));
        assertThat(ma.refractionIor()).isCloseTo(1.33f, within(0f));
        assertThat(ma.hasRefraction()).isTrue();
        // the environment is not one of the diffuse/normal/spec texture layers:
        assertThat(ma.layerCount()).as("env is a cube, not a stacked texture layer")
                .isEqualTo(1);
    }

    @Test
    void reflectivityIsClampedAndANonPositiveIorReadsAsNoBend() {
        MaterialAppearance over = new MaterialAppearance(1f, 1f, 1f, 1f, null,
                false, null, null, 0f, null, null, null, "sky.png", 2.5f, 0f);
        assertThat(over.reflectivity()).as("clamped into [0,1]").isCloseTo(1f, within(0f));
        assertThat(over.refractionIor()).as("ior <= 0 means no refraction").isCloseTo(1f, within(0f));
        assertThat(over.hasRefraction()).isFalse();
    }

    @Test
    void aBlankEnvironmentPathIsNoEnvironment() {
        MaterialAppearance ma = new MaterialAppearance(1f, 1f, 1f, 1f, null,
                false, null, null, 0f, null, null, null, "   ", 0.5f, 1.5f);

        assertThat(ma.hasEnvironmentMap()).as("whitespace is not a resolved path").isFalse();
        assertThat(ma.environmentMapPath()).isNull();
        assertThat(ma.toString()).contains("environmentMap=null");
    }
}
