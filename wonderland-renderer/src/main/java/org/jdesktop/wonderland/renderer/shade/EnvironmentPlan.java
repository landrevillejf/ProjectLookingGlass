/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.5: can a material's environment reflection and
 * refraction actually be applied -- decided headless, ceiling stated out loud.
 *
 * This is the same shape as ShaderAppearancePlan for P2.2 and PostProcessPlan
 * for P3.2: the honest deliverable for a GPU-bound effect is the DECISION about
 * the GPU, readable by a human, plus the artefact that survives whatever the
 * decision is. Java 3D 1.7.2's fixed pipeline can bind a cube texture, but it
 * cannot compute a per-fragment Schlick fresnel that blends reflection against
 * a Snell-refracted lookup -- that needs the fork's ShaderAppearance program,
 * which validates only against a live GL context. WlProbe50 settled the one
 * unknown this route rested on: a {@code samplerCube} binds on its own texture
 * unit and samples the correct face live on this fork (+X red, +Y green, +Z blue
 * each read back their own face), and WlProbe51 drove the shipping factory's
 * environment program -- a diffuse {@code sampler2D} on unit 0 beside the cube on
 * unit 1 -- and read the sky gradient back off a facing quad. So an environment
 * material now takes the SHADER route whenever the caller reports the validated
 * capability, exactly as P2.2 applies its normal/spec maps; a caller that reports
 * FIXED_FUNCTION_ONLY still takes FIXED, and this plan NAMES the effects that
 * pipeline drops, so a log line says "this world's water lost its reflections"
 * instead of the surface silently reading flat.
 *
 * The capability vocabulary is reused from ShaderAppearancePlan on purpose: one
 * host, one "is the shader path validated" answer, two features reading it.
 */
package org.jdesktop.wonderland.renderer.shade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jdesktop.wonderland.renderer.mesh.MaterialAppearance;
import org.jdesktop.wonderland.renderer.mesh.ShaderAppearancePlan;

/** The P2.5 verdict: whether environment reflection/refraction run, or what fell away. */
public final class EnvironmentPlan {

    /** Which pipeline an environment material renders on. */
    public enum Path {
        /** The fixed-function Appearance: no fresnel blend, so no environment. */
        FIXED,
        /** A GLSL environment program on a ShaderAppearance -- probe-gated. */
        SHADER
    }

    /** The two effects P2.5 asks for, named exactly as the fallback reports them. */
    public static final String REFLECTION = "environmentReflection";
    public static final String REFRACTION = "refraction";

    /**
     * The decision plus its evidence: which path, and -- on the FIXED route
     * taken <em>because</em> the platform cannot compute the blend -- exactly
     * which environment effects it dropped, so the caller can log the loss
     * rather than draw a flat pond and call it done.
     */
    public static final class Decision {

        private final Path path;
        private final List<String> unappliedEffects;

        private Decision(Path path, List<String> unappliedEffects) {
            this.path = path;
            this.unappliedEffects = Collections.unmodifiableList(unappliedEffects);
        }

        public Path path() {
            return path;
        }

        /** Environment effects the fixed pipeline cannot compute (empty on SHADER). */
        public List<String> unappliedEffects() {
            return unappliedEffects;
        }

        public boolean isFixedFunction() {
            return path == Path.FIXED;
        }

        /** Whether anything will actually glow with the environment on this verdict. */
        public boolean appliesEnvironment() {
            return path == Path.SHADER;
        }

        @Override
        public String toString() {
            return "EnvironmentPlan.Decision{path=" + path
                    + ", unapplied=" + unappliedEffects + "}";
        }
    }

    /**
     * The environment-mapping GLSL 1.20 vertex stage.
     *
     * <p>The same eye-space pair {@link ShaderAppearancePlan#VERTEX_GLSL} proved
     * live (WlProbe49): the interpolated eye-space normal and the per-fragment
     * view direction the fresnel blend needs, plus the diffuse texture
     * coordinate. A reflection computed from ONE uniform direction for the whole
     * surface reads flat -- the exact defect the earlier draft of this plan
     * carried -- so the eye vectors ride in as varyings, one per vertex.</p>
     */
    public static final String ENV_VERTEX_GLSL = """
            #version 120
            // P2.5 environment vertex stage: the eye-space normal and view
            // direction the fragment's reflect/refract/fresnel cannot recompute
            // there (gl_NormalMatrix and the eye position are vertex-only).
            varying vec3 vEyeNormal;
            varying vec3 vViewDir;
            void main() {
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                gl_TexCoord[0] = gl_MultiTexCoord0;
                vEyeNormal = normalize(gl_NormalMatrix * gl_Normal);
                vec3 eyePos = (gl_ModelViewMatrix * gl_Vertex).xyz;
                vViewDir = normalize(-eyePos);
            }
            """;

    /**
     * The environment-mapping GLSL 1.20 fragment stage.
     *
     * <p>Bound to what this renderer can supply and nothing more: a diffuse
     * sampler and a cube-map sampler (WlProbe50 measured a {@code samplerCube}
     * binding on its own unit and sampling the correct face live on this fork),
     * the eye and normal vectors {@link #ENV_VERTEX_GLSL} interpolates, the scene
     * key, the material's index of refraction and its reflectivity. It reads like
     * {@link EnvMapKernel} spelled in shading language -- incident {@code I} is
     * the ray travelling toward the surface ({@code -vViewDir}), {@code N} points
     * out toward the eye, {@code reflect}, {@code refract} with its total-
     * internal-reflection fallback, Schlick {@code fresnel}, then a {@code mix}
     * of the two environment colours laid over the lit diffuse -- which is the
     * whole point: the headless kernel and the program that replaces it cannot
     * disagree. It is a compiled string here, asserted to name the sampler the
     * plan binds; WlProbe51 drove it through the shipping factory and read the
     * sky gradient back off a facing quad.</p>
     */
    public static final String ENV_FRAGMENT_GLSL = """
            #version 120
            // P2.5 environment reflection + refraction through a cube map,
            // weighted by Schlick fresnel, laid over the same lit diffuse base
            // P2.2 shades. This is the effect the fixed pipeline cannot compute;
            // on the ShaderAppearance path only. The eye vectors are per-vertex
            // VARYINGS from ENV_VERTEX_GLSL, not one uniform direction for the
            // whole surface -- a uniform eye vector reads flat, which is the bug
            // this stage exists to avoid.
            varying vec3 vEyeNormal;
            varying vec3 vViewDir;
            uniform sampler2D diffuseMap;
            uniform samplerCube envMap;
            uniform vec3 lightDirection;
            uniform vec3 lightColor;
            uniform vec3 ambientColor;
            uniform float ior;           // the material's index of refraction
            uniform float envStrength;   // how much of the environment shows

            void main() {
                vec4 base = texture2D(diffuseMap, gl_TexCoord[0].st);
                vec3 N = normalize(vEyeNormal);
                vec3 L = normalize(-lightDirection);
                float ndl = max(dot(N, L), 0.0);
                vec3 lit = base.rgb * (ambientColor + lightColor * ndl);

                // Incident points where the ray travels (toward the surface),
                // exactly EnvMapKernel's convention: I = -vViewDir, N out to eye.
                vec3 I = normalize(-vViewDir);
                vec3 R = reflect(I, N);
                float eta = 1.0 / max(ior, 1e-3);
                vec3 refr = refract(I, N, eta);
                float cosT = max(dot(N, -I), 0.0);
                float f0 = pow((1.0 - ior) / (1.0 + ior), 2.0);
                float fresnel = f0 + (1.0 - f0) * pow(1.0 - cosT, 5.0);
                // a zero-length refract() is total internal reflection: fall back
                // to the reflection, exactly as EnvMapKernel.refract answers null
                vec3 reflColor = textureCube(envMap, R).rgb;
                vec3 refrColor = dot(refr, refr) > 0.0 ? textureCube(envMap, refr).rgb
                                                       : reflColor;
                vec3 env = mix(refrColor, reflColor, fresnel);
                vec3 color = mix(lit, env, clamp(envStrength, 0.0, 1.0));
                gl_FragColor = vec4(min(color, vec3(1.0)), base.a);
            }
            """;

    private EnvironmentPlan() {
    }

    /**
     * Which pipeline renders one material's environment, given what the platform
     * can do. Conservative in both directions, exactly like {@code
     * ShaderAppearancePlan.decide}: a material with no cube map to sample and no
     * index of refraction to bend has nothing environment-shaped to lose, so it
     * stays FIXED with an empty drop-list and no pre-P2.5 world changes a byte.
     * A material that asks for the environment takes SHADER only when a probe
     * validated the fork's shader API; otherwise it takes FIXED <em>with the
     * lost effects named</em> -- the roadmap's documented ceiling.
     */
    public static Decision decide(MaterialAppearance material,
            ShaderAppearancePlan.Capability capability) {
        if (material == null) {
            return new Decision(Path.FIXED, List.of());
        }
        List<String> requested = environmentEffects(material);
        if (requested.isEmpty()) {
            return new Decision(Path.FIXED, List.of());
        }
        if (capability == ShaderAppearancePlan.Capability.SHADER_MODEL_AVAILABLE) {
            return new Decision(Path.SHADER, List.of());
        }
        return new Decision(Path.FIXED, requested);
    }

    /**
     * The environment effects this material names. Reflection needs a cube map
     * to reflect; refraction needs both an index of refraction that bends (one
     * that differs from 1) AND a cube map to bend the lookup through -- a
     * refracted ray that samples nothing is not an effect, so a lone {@code ior}
     * requests nothing. A plain material therefore asks for neither, which is
     * what keeps every shipped pre-P2.5 world on the untouched FIXED path.
     */
    private static List<String> environmentEffects(MaterialAppearance material) {
        List<String> effects = new ArrayList<>(2);
        boolean hasCube = material.hasEnvironmentMap();
        if (hasCube) {
            effects.add(REFLECTION);
            if (material.hasRefraction()) {
                effects.add(REFRACTION);
            }
        }
        return effects;
    }
}
