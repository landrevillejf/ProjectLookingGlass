/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.6: decide whether a material runs the Cook-Torrance route.
 *
 * This is ShaderAppearancePlan's twin for PBR: the decision half (pure data, no Java 3D
 * import, assertable headless) plus the one GLSL artifact the decision names. The BRDF
 * itself lives in PbrKernel (shade package), and FRAGMENT_GLSL below is that kernel
 * transcribed -- the same PI, the same 0.04 dielectric F0, the same GGX D, Smith-Schlick
 * G and Schlick F, the same energy-conserving (1-F)(1-metallic) diffuse split -- so a
 * SHADER verdict and the source it would compile cannot disagree about the maths.
 *
 * The route is probe-gated exactly like ShaderAppearancePlan's SHADER path, for the
 * same stated reason: this fork moved the shader entry point onto a ShaderAppearance
 * subclass, a program validates only against a live GL context, and the one place that
 * may claim a pixel is an in-JVM probe. A WlProbe run validated the fork's Blinn-Phong
 * program (WlProbe39..49), NOT a PBR program, so until a probe validates this one,
 * production wiring keeps the Blinn-Phong route and every material takes FALLBACK with
 * its metallic/roughness recorded but unapplied -- pixels unchanged, the decision
 * honest. Deciding that fallback is what PbrPlanTest proves here.
 */
package org.jdesktop.wonderland.renderer.mesh;

/** The headless verdict of "does this material run the PBR Cook-Torrance route". */
public final class PbrPlan {

    /** Which shading model renders the material. */
    public enum Path {
        /** The existing route: fixed-function, or the validated Blinn-Phong program. */
        FALLBACK,
        /** The Cook-Torrance metallic-roughness program -- probe-gated, see decide(). */
        PBR
    }

    /**
     * Whether the running platform exposes a VALIDATED GLSL program slot. The same
     * evidence ShaderAppearancePlan names: only a probe run that compiled a program
     * against the fork's live context may answer {@link #SHADER_MODEL_AVAILABLE}.
     */
    public enum Capability {
        /** A probe validated the fork's shader API for a program of this shape. */
        SHADER_MODEL_AVAILABLE,
        /** Fixed pipeline only, or the PBR program not yet proven by a probe run. */
        FIXED_FUNCTION_ONLY
    }

    /** The decision plus the sentence a log line or a probe can read back. */
    public record Decision(Path path, String reason) {

        /** Whether the material renders through the Cook-Torrance program. */
        public boolean isPbr() {
            return path == Path.PBR;
        }
    }

    /**
     * The Cook-Torrance vertex stage: identical in shape to ShaderAppearancePlan's,
     * because it must run on the same fork's validated eye-space pipeline -- the
     * interpolated normal and view vector are the two things the fragment stage needs
     * that it cannot recompute (gl_NormalMatrix is vertex-only).
     */
    public static final String VERTEX_GLSL = """
            #version 120
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
     * The Cook-Torrance fragment stage: PbrKernel.brdf transcribed. One directional
     * light supplied as OUR OWN uniforms (the scene Light is inert on a custom
     * program -- WlProbe39), a flat ambient term, and the metallic/roughness either
     * from scalars or, when the host bound the glTF-packed map, from its blue/green
     * channels. Every constant matches the kernel: PI, the 0.04 dielectric F0, the
     * 1e-4 denominators, alpha = roughness^2, k = alpha/2.
     */
    public static final String FRAGMENT_GLSL = """
            #version 120
            varying vec3 vEyeNormal;
            varying vec3 vViewDir;
            uniform sampler2D diffuseMap;
            uniform sampler2D metallicRoughnessMap;
            uniform int hasMetallicRoughnessMap;
            uniform float metallic;
            uniform float roughness;
            uniform vec3 lightDirection;
            uniform vec3 lightColor;
            uniform vec3 ambientColor;
            const float PI = 3.14159265358979323846;
            const float DIELECTRIC_F0 = 0.04;

            float distributionGGX(float nDotH, float r) {
                float a2 = clamp(r, 0.0, 1.0) * clamp(r, 0.0, 1.0);
                float d = max(nDotH, 0.0);
                float denom = d * d * (a2 - 1.0) + 1.0;
                return a2 / (PI * denom * denom + 1e-4);
            }
            float geometrySchlickGGX(float nDotV, float r) {
                float a = clamp(r, 0.0, 1.0);
                float k = (a * a) / 2.0;
                float v = max(nDotV, 0.0);
                return v / (v * (1.0 - k) + k + 1e-4);
            }
            float geometrySmith(float nDotV, float nDotL, float r) {
                return geometrySchlickGGX(nDotV, r) * geometrySchlickGGX(nDotL, r);
            }
            vec3 fresnelSchlick(float cosTheta, vec3 f0) {
                float c = clamp(cosTheta, 0.0, 1.0);
                float flip = pow(1.0 - c, 5.0);
                return f0 + (1.0 - f0) * flip;
            }
            void main() {
                vec4 base = texture2D(diffuseMap, gl_TexCoord[0].st);
                float m = metallic;
                float r = roughness;
                if (hasMetallicRoughnessMap != 0) {
                    // glTF packs roughness in green and metallic in blue.
                    vec4 mr = texture2D(metallicRoughnessMap, gl_TexCoord[0].st);
                    m *= mr.b;
                    r *= mr.g;
                }
                vec3 N = normalize(vEyeNormal);
                vec3 V = normalize(vViewDir);
                vec3 L = normalize(-lightDirection);
                float nDotL = max(dot(N, L), 0.0);
                vec3 color = base.rgb * ambientColor;
                if (nDotL > 0.0) {
                    vec3 H = normalize(V + L);
                    float nDotV = max(dot(N, V), 0.0);
                    float nDotH = max(dot(N, H), 0.0);
                    float hDotV = max(dot(H, V), 0.0);
                    vec3 f0 = mix(vec3(DIELECTRIC_F0), base.rgb, m);
                    float D = distributionGGX(nDotH, r);
                    float G = geometrySmith(nDotV, nDotL, r);
                    vec3 F = fresnelSchlick(hDotV, f0);
                    vec3 spec = (D * G * F) / (4.0 * nDotV * nDotL + 1e-4);
                    vec3 kd = (1.0 - F) * (1.0 - m);
                    vec3 diffuse = kd * base.rgb / PI;
                    color += (diffuse + spec) * lightColor * nDotL;
                }
                gl_FragColor = vec4(min(color, vec3(1.0)), base.a);
            }
            """;

    private PbrPlan() {
    }

    /**
     * Which shading model renders one material, given what it carries, whether PBR is
     * wanted, and what the platform proved.
     *
     * <p>Conservative in every direction, and every branch carries its sentence: PBR
     * off is the existing route; a material with no metallic/roughness
     * has nothing for a BRDF to consume and keeps the route that already renders it;
     * a material that does carry them takes PBR only where a probe validated a program
     * of this shape; and absent that validation it FALLS BACK with the metallic and
     * roughness recorded but unapplied -- the roadmap's documented fixed-function
     * fallback, never a silent flat surface.</p>
     *
     * @param hasMetallicRoughness whether the material actually carries PBR params
     * @param pbrWanted            whether {@code lg.wonderland.pbr} is on
     * @param capability           what a probe validated for this build
     */
    public static Decision decide(boolean hasMetallicRoughness, boolean pbrWanted,
            Capability capability) {
        if (!pbrWanted) {
            return new Decision(Path.FALLBACK,
                    "PBR is off (lg.wonderland.pbr=off or the master switch); the "
                            + "existing fixed/Blinn-Phong route renders the material");
        }
        if (!hasMetallicRoughness) {
            return new Decision(Path.FALLBACK,
                    "the material carries no metallic/roughness, so there is nothing "
                            + "for the BRDF to consume; the existing route renders it "
                            + "unchanged");
        }
        if (capability == Capability.SHADER_MODEL_AVAILABLE) {
            return new Decision(Path.PBR,
                    "a probe validated the fork's shader API for a program of this "
                            + "shape and the material carries metallic/roughness, so "
                            + "the Cook-Torrance route renders it");
        }
        return new Decision(Path.FALLBACK,
                "PBR needs a validated GLSL program; no probe has compiled this one "
                        + "against the fork's live context, so the existing route "
                        + "renders and the metallic/roughness stay recorded but "
                        + "unapplied");
    }
}
