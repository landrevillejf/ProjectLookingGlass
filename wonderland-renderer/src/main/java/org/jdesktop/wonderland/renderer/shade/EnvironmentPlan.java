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
 * which validates only against a live GL context. So, exactly as P2.2 gates its
 * normal/spec maps, an environment material takes the SHADER route ONLY when a
 * probe has validated the fork's shader API; until then it takes FIXED and this
 * plan NAMES the effects the platform dropped, so a log line says "this world's
 * water lost its reflections" instead of the surface silently reading flat.
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
     * The environment-mapping GLSL 1.20 fragment stage.
     *
     * <p>Bound to what this renderer can supply and nothing more: a cube-map
     * sampler, the eye and normal vectors the eye rig already computes, the
     * material's index of refraction and a fresnel power. It reads like
     * {@link EnvMapKernel} spelled in shading language -- {@code reflect},
     * {@code refract}, Schlick {@code fresnel}, then a {@code mix} of the two
     * environment colours -- which is the whole point: the headless kernel and
     * the program that would one day replace it cannot disagree. It is a
     * compiled string here, asserted to exist and to name the sampler the plan
     * binds; it becomes a live program only in a probe run, never in a test.</p>
     */
    public static final String ENV_FRAGMENT_GLSL = """
            #version 120
            // P2.5 environment reflection + refraction through a cube map,
            // weighted by Schlick fresnel. This is the effect the fixed
            // pipeline cannot compute; on the ShaderAppearance path only.
            uniform samplerCube envMap;
            uniform vec3 eyeDirection;   // toward the surface, as in EnvMapKernel
            uniform vec3 surfaceNormal;  // out of the surface, toward the eye
            uniform float ior;           // the material's index of refraction
            uniform float envStrength;   // how much of the environment shows

            void main() {
                vec3 N = normalize(surfaceNormal);
                vec3 I = normalize(eyeDirection);
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
                gl_FragColor = vec4(mix(gl_FragColor.rgb, env, clamp(envStrength, 0.0, 1.0)), 1.0);
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
