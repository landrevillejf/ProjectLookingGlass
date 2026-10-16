/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.6: the Cook-Torrance BRDF, headless.
 *
 * PbrKernel is the pure half of PBR -- the reference PbrPlan's GLSL is checked against
 * -- so what this suite pins is the physics a host depends on before a GPU is in the
 * room: a dielectric's F0 is the model's 0.04 and a metal's is its own base colour, the
 * Schlick Fresnel returns F0 head-on and rises to 1.0 at grazing, the GGX distribution
 * is positive and peaks sharper as roughness falls, the Smith geometry stays in (0,1],
 * the assembled BRDF is non-negative and contributes nothing for a light or an eye at
 * or below the horizon, a black metal reflects nothing (its F0 IS its black base), and
 * shade() adds the ambient and scales the direct term by the cosine. The on-screen
 * program is probe-gated like ShaderAppearancePlan's; the maths it runs is proven here.
 */
package org.jdesktop.wonderland.renderer.shade;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PbrKernelTest {

    private static final float[] UP = {0f, 0f, 1f};
    private static final float[] WHITE = {1f, 1f, 1f};
    private static final float[] BLACK = {0f, 0f, 0f};

    @Test
    void theDielectricF0IsTheModelsFourPercent() {
        assertThat(PbrKernel.DIELECTRIC_F0).isCloseTo(0.04f, within(1e-6f));
        float[] f0 = PbrKernel.f0(WHITE, 0f);
        assertThat(f0[0]).isCloseTo(0.04f, within(1e-6f));
        assertThat(f0[1]).isCloseTo(0.04f, within(1e-6f));
        assertThat(f0[2]).isCloseTo(0.04f, within(1e-6f));
    }

    @Test
    void aMetalsF0IsItsOwnBaseColour() {
        float[] brass = {0.8f, 0.6f, 0.2f};
        float[] f0 = PbrKernel.f0(brass, 1f);
        assertThat(f0[0]).isCloseTo(0.8f, within(1e-6f));
        assertThat(f0[1]).isCloseTo(0.6f, within(1e-6f));
        assertThat(f0[2]).isCloseTo(0.2f, within(1e-6f));
    }

    @Test
    void aPartialMetalInterpolatesBetweenDielectricAndBase() {
        float[] f0 = PbrKernel.f0(WHITE, 0.5f);
        // Halfway between 0.04 and 1.0 on every channel.
        assertThat(f0[0]).isCloseTo(0.52f, within(1e-6f));
    }

    @Test
    void fresnelReturnsF0HeadOnAndRisesToOneAtGrazing() {
        float[] f0 = {0.04f, 0.04f, 0.04f};
        float[] headOn = PbrKernel.fresnelSchlick(1f, f0);
        assertThat(headOn[0]).isCloseTo(0.04f, within(1e-6f));
        float[] grazing = PbrKernel.fresnelSchlick(0f, f0);
        assertThat(grazing[0]).isCloseTo(1f, within(1e-6f));
    }

    @Test
    void theGgxDistributionIsPositiveAndSharperAsRoughnessFalls() {
        float polished = PbrKernel.distributionGgx(1f, 0.1f);
        float rough = PbrKernel.distributionGgx(1f, 0.9f);
        assertThat(polished).isPositive();
        assertThat(rough).isPositive();
        assertThat(polished)
                .as("a polished surface concentrates the lobe into a taller peak")
                .isGreaterThan(rough);
    }

    @Test
    void theSmithGeometryStaysWithinUnitRange() {
        for (float r : new float[] {0.05f, 0.5f, 1f}) {
            float g = PbrKernel.geometrySmith(0.7f, 0.6f, r);
            assertThat(g).as("roughness %s", r).isBetween(0f, 1f);
            assertThat(PbrKernel.geometrySchlickGgx(0.5f, r)).isBetween(0f, 1f);
        }
    }

    @Test
    void theBrdfIsNonNegativeForAFacingLightAndEye() {
        float[] fr = PbrKernel.brdf(UP, UP, UP, WHITE, 0f, 0.5f);
        for (float c : fr) {
            assertThat(c).isGreaterThanOrEqualTo(0f);
            assertThat(Float.isFinite(c)).isTrue();
        }
        assertThat(fr[0]).as("a white dielectric facing the light reflects something")
                .isGreaterThan(0f);
    }

    @Test
    void theBrdfContributesNothingBelowTheHorizon() {
        // Light behind the surface: nDotL <= 0.
        float[] below = PbrKernel.brdf(UP, UP, new float[] {0f, 0f, -1f}, WHITE, 0f, 0.5f);
        assertThat(below).containsExactly(0f, 0f, 0f);
        // Eye below the horizon: nDotV <= 0.
        float[] eye = PbrKernel.brdf(UP, new float[] {0f, 0f, -1f}, UP, WHITE, 0f, 0.5f);
        assertThat(eye).containsExactly(0f, 0f, 0f);
    }

    @Test
    void aBlackMetalReflectsNothingBecauseItsF0IsItsBlackBase() {
        // metallic 1 with a black base: F0 = 0, so both the specular (D*G*F0) and the
        // diffuse ((1-F)(1-metallic)) terms vanish -- the model's metals-are-their-own-
        // specular claim, pinned at the zero corner.
        float[] fr = PbrKernel.brdf(UP, UP, UP, BLACK, 1f, 0.4f);
        assertThat(fr[0]).isCloseTo(0f, within(1e-6f));
        assertThat(fr[1]).isCloseTo(0f, within(1e-6f));
        assertThat(fr[2]).isCloseTo(0f, within(1e-6f));
    }

    @Test
    void shadeAddsTheAmbientAndScalesTheDirectTermByTheCosine() {
        float[] ambient = {0.1f, 0.1f, 0.1f};
        // Facing the light straight on, nDotL = 1: the direct term is at full strength.
        float[] lit = PbrKernel.shade(UP, UP, UP, WHITE, 0f, 0.5f, WHITE, ambient);
        // Light below the horizon: only the ambient survives.
        float[] dark = PbrKernel.shade(UP, UP, new float[] {0f, 0f, -1f}, WHITE, 0f,
                0.5f, WHITE, ambient);
        assertThat(dark[0]).isCloseTo(0.1f, within(1e-6f));
        assertThat(lit[0]).as("a facing light adds radiance on top of the ambient")
                .isGreaterThan(dark[0]);
    }
}
