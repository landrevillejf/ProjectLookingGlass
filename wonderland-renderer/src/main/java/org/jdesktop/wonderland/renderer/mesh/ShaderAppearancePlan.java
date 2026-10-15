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
     * <p>Bound exactly to what this renderer can supply: the eye-space vertex
     * pipeline Java 3D already computes (inPosition/inNormal/inTexCoord), up to
     * three texture layers on units 0..2, and the five uniforms the lighting
     * rig (P0.1) already owns. The normal map is read as a tangent-space
     * perturbation and the specular map scales the Blinn highlight when the
     * effect named one. It is a compiled string here -- asserted to exist and
     * to name its samplers consistently -- and it becomes a live program only
     * in a probe run, never in a headless test.</p>
     */
    public static final String VERTEX_GLSL = """
            #version 120
            // P2.2 layered-material vertex stage: the standard Java 3D eye-space
            // pipeline, with the three vectors the fragment stage needs.
            void main() {
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                vec3 eyeNormal = normalize(gl_NormalMatrix * gl_Normal);
                // the tangent frame is derived here rather than shipped
                // per-vertex, because ColladaMeshData carries no tangents and
                // inventing a vertex attribute needs the probe's geometry-array
                // agreement.
                vec3 tangent = normalize(gl_Vertex.xyz - gl_Normal);
                vec3 binormal = cross(eyeNormal, tangent);
                gl_FrontColor = gl_Color;
                gl_TexCoord[0] = gl_MultiTexCoord0;
                // the fog distance the fixed pipeline would have computed, in
                // eye space (gl_FogFragCoord is a vertex-shader INPUT -- an
                // assignment to it is exactly the kind of compile error only a
                // live context can catch, which is why this string is written
                // against the spec and validated by a probe, never by a test).
                gl_FogFragCoord = length((gl_ModelViewMatrix * gl_Vertex).xyz);
                // pass the eye-space basis through per-face varyings
                gl_FrontSecondaryColor = vec4(tangent, dot(eyeNormal, binormal) > 0.0 ? 1.0 : -1.0);
            }
            """;

    /** The fragment half of {@link #VERTEX_GLSL}. */
    public static final String FRAGMENT_GLSL = """
            #version 120
            // P2.2 layered-material fragment stage: one directional light,
            // Blinn-Phong, tangent-space normal map on unit 1, optional
            // specular map on unit 2. Uniform names mirror the P0.1 rig; a
            // world without a spec map passes HAS_SPEC_MAP 0 and unit 2 is
            // simply never sampled.
            uniform sampler2D diffuseMap;
            uniform sampler2D normalMap;
            uniform sampler2D specMap;
            uniform vec3 lightDirection;
            uniform vec3 lightColor;
            uniform vec3 ambientColor;
            uniform float shininess;
            uniform int hasSpecMap;

            void main() {
                vec4 base = texture2D(diffuseMap, gl_TexCoord[0].st);
                // Unpack the tangent-space normal (the 0.5..1.0 encoding every
                // authoring tool writes) and perturb the interpolated normal.
                vec3 mapNormal = texture2D(normalMap, gl_TexCoord[0].st).xyz * 2.0 - 1.0;
                vec3 eyeNormal = normalize(gl_NormalMatrix * mapNormal);
                vec3 L = normalize(-lightDirection);
                float ndl = max(dot(eyeNormal, L), 0.0);
                vec3 diffuse = base.rgb * (ambientColor + lightColor * ndl);
                if (hasSpecMap != 0) {
                    float specMask = texture2D(specMap, gl_TexCoord[0].st).r;
                    vec3 V = normalize(gl_LightSource[0].halfVector.xyz);
                    float spec = pow(max(dot(eyeNormal, V), 0.0), max(shininess, 1.0));
                    diffuse += lightColor * spec * specMask;
                }
                gl_FragColor = vec4(diffuse, base.a);
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
