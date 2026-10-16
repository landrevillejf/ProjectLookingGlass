/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: decide how a multi-layer material is applied.
 *
 * The roadmap asked for a GLSL ShaderProgram on the shapes "with a documented
 * fixed-function fallback where Java 3D 1.7.2 cannot express the effect". This
 * class is the decision half of that sentence -- pure data, no Java 3D import,
 * so the answer is assertable headless -- and it carries the one GLSL artifact
 * the decision needs. Which half runs where is the platform ceiling, stated in
 * full at decide() and in J3dMeshFactory's P2.2 note: this repo's Jogamp fork
 * moved the shader entry point from upstream Java 3D 1.6's
 * Appearance.setShaderProgram onto a ShaderAppearance subclass, its program
 * validates only against a live GL context, and the one place that claims a
 * pixel -- the in-JVM probe -- is the only place that may call it. Until a
 * probe run exists, every world takes the FIXED path, which is today's
 * renderer byte for byte: the extra P2.1 layers are resolved and recorded,
 * simply unapplied. That is the documented fallback, and deciding it is
 * provable here.
 */
package org.jdesktop.wonderland.renderer.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The headless verdict of "can this material's layers actually be applied". */
public final class ShaderAppearancePlan {

    /** Which pipeline the material runs on. */
    public enum Path {
        /** The fixed-function Appearance exactly as P0.1/P2.1 built it. */
        FIXED,
        /** A GLSL program on a ShaderAppearance -- probe-gated, see decide(). */
        SHADER
    }

    /**
     * Whether the running platform exposes the fork's shader entry point.
     *
     * <p>The probe (J3dMeshFactory) is the only honest caller of this: a fixed
     * pipeline that structurally dropped its shader hook answers false, a GL
     * context exposing {@code ShaderAppearance} answers true, and until a probe
     * has actually validated a program against a real GPU, production wiring
     * asks with false and draws what it has always drawn.</p>
     */
    public enum Capability {
        /** The live context validated the fork's shader API for this build. */
        SHADER_MODEL_AVAILABLE,
        /** Fixed pipeline only -- or simply not yet proven by a probe run. */
        FIXED_FUNCTION_ONLY
    }

    /**
     * The decision plus the evidence: which path, which layer slots a SHADER
     * program would bind, and -- on the FIXED path taken <em>because</em>
     * layers cannot be applied -- exactly which layers the platform dropped,
     * so a log line can say so instead of the world silently looking flat.
     */
    public static final class Decision {

        private final Path path;
        private final List<String> unappliedLayers;

        private Decision(Path path, List<String> unappliedLayers) {
            this.path = path;
            this.unappliedLayers = Collections.unmodifiableList(unappliedLayers);
        }

        public Path path() {
            return path;
        }

        /** Layer slot names the fixed pipeline cannot bind (empty on SHADER). */
        public List<String> unappliedLayers() {
            return unappliedLayers;
        }

        /** Whether the material renders exactly as the pre-P2.2 renderer drew it. */
        public boolean isFixedFunction() {
            return path == Path.FIXED;
        }

        @Override
        public String toString() {
            return "ShaderAppearancePlan.Decision{path=" + path
                    + ", unapplied=" + unappliedLayers + "}";
        }
    }

    /**
     * The one-light Blinn-Phong GLSL 1.20 pair for a layered material.
     *
     * <p>Bound to what this fork's live GL context was MEASURED to expose, not to
     * what upstream Java 3D documents. Three probes settled the shape (build-
     * gradle/scratch, run on the repo's X:0 / GL 4.6 radeonsi context):
     * <ul>
     *   <li>WlProbe39: the scene graph's {@code Light} does NOT reach a custom
     *       program -- {@code gl_LightSource[0]} read back its OpenGL default for a
     *       facing and a grazing normal alike, even with a properly bounded
     *       {@code DirectionalLight}. So the light is passed as the program's OWN
     *       {@code lightDirection / lightColor / ambientColor} uniforms, which
     *       WlProbe40 proved {@code ShaderAttributeSet} delivers and WlProbe41
     *       proved shade per fragment (facing 255,255,255 vs grazing 31,31,31).</li>
     *   <li>WlProbe40: one {@code sampler2D} bound through {@code TextureUnitState}
     *       samples correctly.</li>
     *   <li>WlProbe48: this fork binds EVERY texture unit to its own sampler once
     *       the sampler is named on the program AND assigned its unit through a
     *       {@code ShaderAttributeValue} -- it measured three distinct textures
     *       (diffuse/normal/spec) live on one program. WlProbe42's earlier "one
     *       texture only" reading was a binding omission, not a platform limit: it
     *       set two {@code TextureUnitState}s but never assigned the second sampler
     *       a unit, so both defaulted to unit 0. The normal- and specular-map stages
     *       are therefore real and sampled whenever the host supplies those layers
     *       ({@code hasNormalMap / hasSpecMap} set from what actually resolved).</li>
     * </ul>
     * The specular half-vector is therefore the eye-space view direction
     * ({@code vViewDir}), not {@code gl_LightSource[0].halfVector}.</p>
     */
    public static final String VERTEX_GLSL = """
            #version 120
            // P2.2 layered-material vertex stage: the eye-space pipeline Java 3D
            // already computes, plus the two vectors the fragment needs that it
            // cannot recompute there (gl_NormalMatrix is vertex-only).
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

    /** The fragment half of {@link #VERTEX_GLSL}. */
    public static final String FRAGMENT_GLSL = """
            #version 120
            // P2.2 layered-material fragment stage: one directional light supplied
            // as OUR OWN uniforms (the scene Light is inert here -- see the class
            // note), Blinn-Phong against the eye-space view vector, tangent-space
            // normal on its own unit and a specular mask on the one after, each
            // sampled whenever the host bound that layer (hasNormalMap /
            // hasSpecMap). WlProbe48 measured every unit binding its own sampler
            // once the sampler is named and assigned a unit, and WlProbe49 proved
            // the shipping factory's normal map perturbs the lighting; a host that
            // binds just the diffuse passes 0 for both and gets per-pixel lighting
            // off the diffuse alone.
            varying vec3 vEyeNormal;
            varying vec3 vViewDir;
            uniform sampler2D diffuseMap;
            uniform sampler2D normalMap;
            uniform sampler2D specMap;
            uniform vec3 lightDirection;
            uniform vec3 lightColor;
            uniform vec3 ambientColor;
            uniform float shininess;
            uniform int hasSpecMap;
            uniform int hasNormalMap;

            void main() {
                vec4 base = texture2D(diffuseMap, gl_TexCoord[0].st);
                vec3 N = normalize(vEyeNormal);
                if (hasNormalMap != 0) {
                    // Unpack the 0.5..1.0 tangent-space encoding every authoring
                    // tool writes and perturb the interpolated normal.
                    vec3 mapN = texture2D(normalMap, gl_TexCoord[0].st).xyz * 2.0 - 1.0;
                    vec3 up = abs(N.z) < 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
                    vec3 T = normalize(cross(up, N));
                    vec3 B = normalize(cross(N, T));
                    N = normalize(T * mapN.x + B * mapN.y + N * max(mapN.z, 0.1));
                }
                vec3 L = normalize(-lightDirection);
                float ndl = max(dot(N, L), 0.0);
                vec3 diffuse = base.rgb * (ambientColor + lightColor * ndl);
                if (hasSpecMap != 0) {
                    float specMask = texture2D(specMap, gl_TexCoord[0].st).r;
                    vec3 H = normalize(L + vViewDir);
                    float spec = pow(max(dot(N, H), 0.0), max(shininess, 1.0)) * specMask;
                    diffuse += lightColor * spec;
                }
                gl_FragColor = vec4(min(diffuse, vec3(1.0)), base.a);
            }
            """;

    private ShaderAppearancePlan() {
    }

    /**
     * Which pipeline renders one material, given what the platform can do.
     *
     * <p>The rule is conservative in both directions. A material with nothing
     * beyond the diffuse layer takes the FIXED path even where shaders exist:
     * a GLSL program would replace a texture stage the fixed pipeline already
     * draws identically, for no pixel of gain. A material with extra layers
     * takes SHADER only when the capability says the program would actually
     * compile and validate; otherwise it takes FIXED <em>with the dropped
     * layers named</em>, which is the roadmap's "documented fixed-function
     * fallback where Java 3D 1.7.2 cannot express the effect" -- the layers
     * stay resolved in the material, and the log says which ones the platform
     * could not apply.</p>
     */
    public static Decision decide(MaterialAppearance material, Capability capability) {
        if (material == null) {
            return new Decision(Path.FIXED, List.of());
        }
        List<String> beyondDiffuse = layersBeyondDiffuse(material);
        if (beyondDiffuse.isEmpty()) {
            return new Decision(Path.FIXED, List.of());
        }
        if (capability == Capability.SHADER_MODEL_AVAILABLE) {
            return new Decision(Path.SHADER, List.of());
        }
        return new Decision(Path.FIXED, beyondDiffuse);
    }

    /** The P2.1 layer slots a fixed pipeline has no texture stage for. */
    private static List<String> layersBeyondDiffuse(MaterialAppearance material) {
        List<String> layers = new ArrayList<>(2);
        if (material.hasNormalMap()) {
            layers.add("normalMap");
        }
        if (material.hasSpecularMap()) {
            layers.add("specularMap");
        }
        return layers;
    }
}
