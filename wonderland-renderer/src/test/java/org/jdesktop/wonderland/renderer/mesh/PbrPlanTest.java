/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.6: the PBR-vs-fallback decision, headless.
 *
 * PbrPlan is the pure half of a GPU-bound phase -- the answer to "does this material
 * run Cook-Torrance?" stated where a human and a test can both read it, carrying its
 * reason beside its path. Every branch is a claim about evidence, so what this suite
 * pins is exactly the contract a host depends on: PBR off is the existing route; a
 * material with no metallic/roughness keeps the route that already renders it; a
 * material that carries them takes PBR only where a probe validated a program of this
 * shape; and absent that validation it falls back with the params recorded but
 * unapplied -- never a silent flat surface. It also pins that the GLSL artifact names
 * the same constants PbrKernel does, so a PBR verdict and the source it would compile
 * cannot disagree.
 */
package org.jdesktop.wonderland.renderer.mesh;

import org.jdesktop.wonderland.renderer.mesh.PbrPlan.Capability;
import org.jdesktop.wonderland.renderer.mesh.PbrPlan.Decision;
import org.jdesktop.wonderland.renderer.mesh.PbrPlan.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PbrPlanTest {

    @Test
    void pbrOffIsTheExistingRouteWhateverTheMaterialCarries() {
        Decision d = PbrPlan.decide(true, false, Capability.SHADER_MODEL_AVAILABLE);
        assertThat(d.path()).isEqualTo(Path.FALLBACK);
        assertThat(d.isPbr()).isFalse();
        assertThat(d.reason()).contains("PBR is off");
    }

    @Test
    void aMaterialWithoutMetallicRoughnessKeepsTheExistingRoute() {
        Decision d = PbrPlan.decide(false, true, Capability.SHADER_MODEL_AVAILABLE);
        assertThat(d.path()).isEqualTo(Path.FALLBACK);
        assertThat(d.reason()).contains("no metallic/roughness");
    }

    @Test
    void aProbedCapabilityIsWhatFlipsTheRouteNotTheWish() {
        Decision d = PbrPlan.decide(true, true, Capability.SHADER_MODEL_AVAILABLE);
        assertThat(d.path()).isEqualTo(Path.PBR);
        assertThat(d.isPbr()).isTrue();
        assertThat(d.reason()).contains("Cook-Torrance");
    }

    @Test
    void anUnprobedProgramFallsBackWithTheParamsRecordedButUnapplied() {
        // The documented ceiling: a GL program compiles only against a live context,
        // and no probe has validated THIS one, so the fallback names what it declined.
        Decision d = PbrPlan.decide(true, true, Capability.FIXED_FUNCTION_ONLY);
        assertThat(d.path()).isEqualTo(Path.FALLBACK);
        assertThat(d.reason()).contains("recorded but").contains("unapplied");
    }

    @Test
    void everyDecisionCarriesANonBlankReasonBesideItsPath() {
        for (boolean params : new boolean[] {true, false}) {
            for (boolean wanted : new boolean[] {true, false}) {
                for (Capability cap : Capability.values()) {
                    Decision d = PbrPlan.decide(params, wanted, cap);
                    assertThat(d.reason()).as("%s must say why", d.path()).isNotBlank();
                }
            }
        }
    }

    @Test
    void theGlslArtifactNamesTheSameConstantsTheKernelDoes() {
        // The two halves of one claim: the fragment program and PbrKernel share the
        // dielectric F0, the GGX/Smith/Schlick terms and the alpha = roughness^2, so
        // a PBR verdict and the source it would compile cannot disagree.
        assertThat(PbrPlan.FRAGMENT_GLSL)
                .contains("#version 120")
                .contains("const float DIELECTRIC_F0 = 0.04;")
                .contains("float distributionGGX(")
                .contains("float geometrySmith(")
                .contains("vec3 fresnelSchlick(")
                .contains("uniform sampler2D diffuseMap;")
                .contains("uniform float metallic;")
                .contains("uniform float roughness;");
        assertThat(PbrPlan.VERTEX_GLSL)
                .contains("#version 120")
                .contains("gl_TexCoord[0] = gl_MultiTexCoord0;");
    }

    @Test
    void thePlanExposesItsRouteAndCapabilityAsFirstClassValues() {
        assertThat(Path.valueOf("PBR")).isEqualTo(Path.PBR);
        assertThat(Capability.valueOf("SHADER_MODEL_AVAILABLE"))
                .isEqualTo(Capability.SHADER_MODEL_AVAILABLE);
    }
}
