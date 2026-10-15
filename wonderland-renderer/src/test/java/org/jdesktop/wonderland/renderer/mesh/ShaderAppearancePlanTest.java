/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: the shader-vs-fixed decision, headless.
 *
 * What a GL context does -- compiling a program -- no test may claim. What a
 * pure function decides -- which pipeline a material runs on and which layers
 * the platform names as dropped -- is exactly what belongs here. The asserts
 * below are the contract J3dMeshFactory's P2.2 note points at: a pre-P2.1
 * world never changes path, an extra layer without a validated shader API
 * degrades to FIXED WITH THE LAYER NAMED (the roadmap's documented fallback),
 * and the GLSL artifact that will one day compile exists and names the same
 * sampler slots the decision binds.
 */
package org.jdesktop.wonderland.renderer.mesh;

import org.jdesktop.wonderland.renderer.mesh.ShaderAppearancePlan.Capability;
import org.jdesktop.wonderland.renderer.mesh.ShaderAppearancePlan.Decision;
import org.jdesktop.wonderland.renderer.mesh.ShaderAppearancePlan.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ShaderAppearancePlanTest {

    private static MaterialAppearance layered(String diffuse, String normal, String spec) {
        return new MaterialAppearance(1f, 1f, 1f, 1f, diffuse,
                false, null, null, 0f, null, normal, spec);
    }

    @Test
    void aPlainMaterialTakesTheFixedPathAndDropsNothing() {
        // The faithful pre-P2.1 world: colour, or colour + one diffuse texture.
        // Not one byte of it may change path because a shader plan exists.
        MaterialAppearance solid = new MaterialAppearance(0.5f, 0.5f, 0.5f, 1f, null);
        MaterialAppearance textured = new MaterialAppearance(1f, 1f, 1f, 1f, "wall.png");

        for (Capability capability : Capability.values()) {
            Decision plain = ShaderAppearancePlan.decide(solid, capability);
            Decision tex = ShaderAppearancePlan.decide(textured, capability);

            assertThat(plain.path()).isEqualTo(Path.FIXED);
            assertThat(plain.unappliedLayers()).isEmpty();
            assertThat(tex.path()).isEqualTo(Path.FIXED);
            assertThat(tex.unappliedLayers()).as("a diffuse texture is not an extra layer")
                    .isEmpty();
        }
    }

    @Test
    void extraLayersWithoutAValidatedShaderApiFallBackWithTheLayersNamed() {
        // The roadmap's documented ceiling: Java 3D 1.7.2's fixed pipeline has
        // no texture stage for a normal or specular map. The fallback is not a
        // silent flat surface -- the decision NAMES what it dropped.
        MaterialAppearance ma = layered("wall.png", "wall-normal.png", "wall-spec.png");

        Decision d = ShaderAppearancePlan.decide(ma, Capability.FIXED_FUNCTION_ONLY);

        assertThat(d.isFixedFunction()).isTrue();
        assertThat(d.unappliedLayers()).containsExactly("normalMap", "specularMap");
    }

    @Test
    void aSingleExtraLayerIsNamedAlone() {
        MaterialAppearance normalOnly = layered("wall.png", "wall-normal.png", null);
        MaterialAppearance specOnly = layered("wall.png", null, "wall-spec.png");

        assertThat(ShaderAppearancePlan.decide(normalOnly, Capability.FIXED_FUNCTION_ONLY)
                .unappliedLayers()).containsExactly("normalMap");
        assertThat(ShaderAppearancePlan.decide(specOnly, Capability.FIXED_FUNCTION_ONLY)
                .unappliedLayers()).containsExactly("specularMap");
    }

    @Test
    void aProbeValidatedCapabilityIsWhatFlipsThePathNotTheWish() {
        MaterialAppearance ma = layered("wall.png", "wall-normal.png", null);

        Decision d = ShaderAppearancePlan.decide(ma, Capability.SHADER_MODEL_AVAILABLE);

        assertThat(d.path()).isEqualTo(Path.SHADER);
        assertThat(d.unappliedLayers()).as("nothing is dropped on the shader path")
                .isEmpty();
    }

    @Test
    void aNullMaterialDecidesFixedRatherThanThrow() {
        // The reporter-of-facts rule the whole renderer follows: an absent
        // material is a grey quad, never an exception on the build path.
        Decision d = ShaderAppearancePlan.decide(null, Capability.SHADER_MODEL_AVAILABLE);

        assertThat(d.isFixedFunction()).isTrue();
        assertThat(d.unappliedLayers()).isEmpty();
    }

    @Test
    void theGlslArtifactNamesExactlyTheSamplerSlotsThePlanBinds() {
        // The two halves of one claim: the decision binds units 0..2 and the
        // program samples the same three uniform sampler names, so a SHADER
        // verdict and the source it would compile cannot disagree.
        assertThat(ShaderAppearancePlan.FRAGMENT_GLSL)
                .contains("uniform sampler2D diffuseMap;")
                .contains("uniform sampler2D normalMap;")
                .contains("uniform sampler2D specMap;")
                .contains("#version 120");
        assertThat(ShaderAppearancePlan.VERTEX_GLSL)
                .contains("#version 120")
                .contains("gl_TexCoord[0] = gl_MultiTexCoord0;");
    }

    @Test
    void theFragmentProgramConsumesTheRigUniformsTheLightingPhaseOwns() {
        // P0.1's rig is the only light source this program may assume; naming
        // the uniforms after it is what keeps one vocabulary across the seam.
        assertThat(ShaderAppearancePlan.FRAGMENT_GLSL)
                .contains("uniform vec3 lightDirection;")
                .contains("uniform vec3 lightColor;")
                .contains("uniform vec3 ambientColor;")
                .contains("uniform float shininess;");
    }
}
