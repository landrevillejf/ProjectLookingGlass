/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.5: the environment-vs-fixed decision, headless.
 *
 * What a GL context does -- binding a cube map and running the fresnel program
 * -- no test may claim. What a pure function decides -- whether the environment
 * applies and which effects the platform drops -- is exactly what belongs here.
 * The asserts are the contract the probe reads: a pre-P2.5 world never changes
 * path, an environment material without a validated shader API degrades to
 * FIXED WITH THE LOST EFFECTS NAMED (the documented ceiling), and the GLSL
 * artifact that would one day compile names the same sampler the plan binds.
 */
package org.jdesktop.wonderland.renderer.shade;

import org.jdesktop.wonderland.renderer.mesh.MaterialAppearance;
import org.jdesktop.wonderland.renderer.mesh.ShaderAppearancePlan.Capability;
import org.jdesktop.wonderland.renderer.shade.EnvironmentPlan.Decision;
import org.jdesktop.wonderland.renderer.shade.EnvironmentPlan.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentPlanTest {

    private static MaterialAppearance env(String cubeMap, float reflectivity, float ior) {
        return new MaterialAppearance(1f, 1f, 1f, 1f, "pond.png",
                false, null, null, 0f, null, null, null, cubeMap, reflectivity, ior);
    }

    @Test
    void aPlainMaterialHasNoEnvironmentToLose() {
        // Every shipped pre-P2.5 world: colour, maybe a texture, no cube map.
        // It must stay FIXED with nothing dropped under either capability, so
        // adding an environment plan changes not one byte of the existing scenes.
        MaterialAppearance solid = new MaterialAppearance(1f, 1f, 1f, 1f, null);

        for (Capability capability : Capability.values()) {
            Decision d = EnvironmentPlan.decide(solid, capability);
            assertThat(d.path()).isEqualTo(Path.FIXED);
            assertThat(d.unappliedEffects()).isEmpty();
            assertThat(d.appliesEnvironment()).isFalse();
        }
    }

    @Test
    void anEnvironmentWithoutAValidatedShaderFallsBackWithTheLostEffectsNamed() {
        // The roadmap's ceiling: the fixed pipeline can bind a cube map but
        // cannot compute the fresnel/refraction blend, so BOTH are reported lost
        // rather than the pond silently reading flat.
        Decision d = EnvironmentPlan.decide(env("sky.png", 0.6f, 1.33f),
                Capability.FIXED_FUNCTION_ONLY);

        assertThat(d.isFixedFunction()).isTrue();
        assertThat(d.appliesEnvironment()).isFalse();
        assertThat(d.unappliedEffects())
                .containsExactly(EnvironmentPlan.REFLECTION, EnvironmentPlan.REFRACTION);
    }

    @Test
    void aReflectiveButNonRefractiveSurfaceLosesOnlyReflection() {
        Decision d = EnvironmentPlan.decide(env("sky.png", 0.5f, 1f), // ior 1 = no bend
                Capability.FIXED_FUNCTION_ONLY);

        assertThat(d.unappliedEffects()).containsExactly(EnvironmentPlan.REFLECTION);
    }

    @Test
    void refractionAloneWithoutACubeMapRequestsNothing() {
        // A bending index with nothing to bend the lookup through is not an
        // effect: a lone ior must not fabricate a drop the fixed pipeline
        // "lost", or the fallback would report a loss that never existed.
        Decision d = EnvironmentPlan.decide(env(null, 0.5f, 1.5f),
                Capability.FIXED_FUNCTION_ONLY);

        assertThat(d.unappliedEffects()).as("no cube map to sample = nothing to lose")
                .isEmpty();
        assertThat(d.isFixedFunction()).isTrue();
    }

    @Test
    void aProbeValidatedCapabilityIsWhatAppliesTheEnvironmentNotTheWish() {
        Decision d = EnvironmentPlan.decide(env("sky.png", 0.6f, 1.33f),
                Capability.SHADER_MODEL_AVAILABLE);

        assertThat(d.path()).isEqualTo(Path.SHADER);
        assertThat(d.appliesEnvironment()).isTrue();
        assertThat(d.unappliedEffects()).as("nothing is dropped on the shader path")
                .isEmpty();
    }

    @Test
    void aNullMaterialDecidesFixedRatherThanThrow() {
        Decision d = EnvironmentPlan.decide(null, Capability.SHADER_MODEL_AVAILABLE);

        assertThat(d.isFixedFunction()).isTrue();
        assertThat(d.unappliedEffects()).isEmpty();
    }

    @Test
    void theGlslArtifactNamesTheSamplerAndOperatorsThePlanAndKernelAgreeOn() {
        // The two halves of one claim: the plan binds a cube sampler and the
        // program samples it, computing the same reflect/refract/fresnel the
        // headless kernel does, so a SHADER verdict and the source it would
        // compile cannot disagree about what the environment pass is.
        assertThat(EnvironmentPlan.ENV_FRAGMENT_GLSL)
                .contains("#version 120")
                .contains("uniform samplerCube envMap;")
                .contains("textureCube(envMap, R)")
                .contains("reflect(I, N)")
                .contains("refract(I, N, eta)")
                .contains("pow(1.0 - cosT, 5.0)"); // Schlick, matching EnvMapKernel.fresnel
    }
}
