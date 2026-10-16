/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.6: physically-based shading as arithmetic, and the contract
 * the GLSL route is measured against.
 *
 * The renderer's lit materials have always run Blinn-Phong (ShaderAppearancePlan's
 * GLSL 1.20 pair): an empirical specular lobe with a hand-tuned shininess. PBR is the
 * roadmap's P2.6 ask -- a Cook-Torrance metallic-roughness BRDF, the model every
 * modern authoring pipeline bakes its maps for, where a surface's response to light is
 * derived from energy conservation and a microfacet normal distribution rather than
 * guessed. This kernel is that model in pure Java: the GGX/Trowbridge-Reitz normal
 * distribution D, the Smith-Schlick geometry term G, the Schlick Fresnel F, the
 * dielectric/metal specular colour F0, and the assembled direct-light BRDF. It imports
 * nothing but java.base, which is what makes it the reference the GLSL fragment shader
 * in PbrPlan is checked against -- the same relationship BloomKernel has to
 * GpuBloomPass and HdrKernel to GpuHdrPass.
 *
 * The honest boundary, identical to every GPU-bound phase this repo has shipped: the
 * BRDF MATHS are pure and headless-proven (PbrKernelTest pins the physical invariants
 * -- a dielectric's F0 is 0.04, a metal's is its base colour, Fresnel returns F0 head-
 * on and 1.0 at grazing, GGX peaks sharper as roughness falls, energy is non-negative
 * and a back-facing or below-horizon sample contributes nothing). The on-screen route
 * that evaluates it per fragment is PbrPlan's GLSL, which is probe-gated exactly like
 * ShaderAppearancePlan's SHADER path: production wiring keeps the validated Blinn-Phong
 * program until a WlProbe run validates the PBR program against the fork's shader API,
 * because a GL program compiles only against a live context and no headless suite may
 * claim a pixel.
 */
package org.jdesktop.wonderland.renderer.shade;

/**
 * The Cook-Torrance metallic-roughness BRDF (roadmap P2.6), as pure functions.
 * Vectors are {@code float[3]}; colours are linear-space {@code float[3]} in 0..1.
 * Stateless, and not fast by design: this is the definition the GPU shader is checked
 * against.
 */
public final class PbrKernel {

    /** pi, spelled once so the kernel and the shader cannot disagree about it. */
    public static final float PI = 3.14159265358979323846f;

    /**
     * The reflectance every non-metal shares at normal incidence -- the 4 % the
     * metallic-roughness model hard-codes for dielectrics, so a plastic and a ceramic
     * glint identically white until a base colour says otherwise.
     */
    public static final float DIELECTRIC_F0 = 0.04f;

    /** Guards a divide-by-zero where the BRDF's denominator would otherwise blow up. */
    private static final float EPS = 1e-4f;

    private PbrKernel() {
    }

    /**
     * The specular colour at normal incidence: a dielectric reflects the neutral 4 %,
     * a metal reflects its own base colour (metals have no diffuse, their "colour" IS
     * their specular tint), and a partial metallic interpolates. This is the single
     * fact that makes one base-colour texture read as plastic or as brass.
     *
     * @param baseColor linear base colour, 0..1 per channel
     * @param metallic  0 = dielectric, 1 = metal, clamped
     */
    public static float[] f0(float[] baseColor, float metallic) {
        float m = clamp01(metallic);
        float[] bc = baseColor == null ? new float[] {1f, 1f, 1f} : baseColor;
        return new float[] {
            mix(DIELECTRIC_F0, bc[0], m),
            mix(DIELECTRIC_F0, bc[1], m),
            mix(DIELECTRIC_F0, bc[2], m),
        };
    }

    /**
     * The GGX / Trowbridge-Reitz normal distribution function D: the fraction of
     * microfacets whose normal bisects the light and view directions, given their
     * dot product. It is the lobe's SHAPE -- tall and tight for a polished surface,
     * low and broad for a rough one -- and integrates to 1 over the hemisphere, which
     * is what makes the model energy-conserving rather than merely pretty.
     *
     * @param nDotH    the normal/half-vector dot, clamped non-negative
     * @param roughness perceptual roughness 0..1; the GGX alpha is its square
     */
    public static float distributionGgx(float nDotH, float roughness) {
        float a = clamp01(roughness);
        float a2 = a * a;
        float d = Math.max(0f, nDotH);
        float denom = d * d * (a2 - 1f) + 1f;
        return a2 / (PI * denom * denom + EPS);
    }

    /**
     * One Schlick-GGX geometry factor G1: the fraction of microfacets visible from a
     * given direction, accounting for the shadowing/masking that a rough surface's
     * own bumps cast on each other. {@code k = alpha/2} is the direct-lighting form.
     */
    public static float geometrySchlickGgx(float nDotV, float roughness) {
        float a = clamp01(roughness);
        float k = (a * a) / 2f;
        float v = Math.max(0f, nDotV);
        return v / (v * (1f - k) + k + EPS);
    }

    /** The combined Smith geometry term G for both the view and the light directions. */
    public static float geometrySmith(float nDotV, float nDotL, float roughness) {
        return geometrySchlickGgx(nDotV, roughness) * geometrySchlickGgx(nDotL, roughness);
    }

    /**
     * The Schlick Fresnel approximation F: how much more a surface reflects the closer
     * to grazing you look at it. Returns {@code f0} head-on (cosTheta 1) and rises
     * toward 1.0 at the horizon (cosTheta 0) -- the effect that makes a floor
     * matte underfoot and mirror-like in the distance.
     *
     * @param cosTheta the (clamped) cosine between the view and the half vector
     * @param f0       the normal-incidence reflectance, per channel
     */
    public static float[] fresnelSchlick(float cosTheta, float[] f0) {
        float c = clamp01(cosTheta);
        float flip = (float) Math.pow(1f - c, 5.0);
        float[] base = f0 == null ? new float[] {DIELECTRIC_F0, DIELECTRIC_F0, DIELECTRIC_F0} : f0;
        return new float[] {
            base[0] + (1f - base[0]) * flip,
            base[1] + (1f - base[1]) * flip,
            base[2] + (1f - base[2]) * flip,
        };
    }

    /**
     * The assembled direct-light BRDF f_r (diffuse + specular), NOT yet scaled by the
     * light's radiance or the {@code nDotL} cosine -- the caller multiplies by
     * {@code lightColor * nDotL} to get the outgoing radiance. Returns all-zero when
     * either the light or the view is at or below the surface horizon, because a
     * back-facing sample reflects nothing.
     *
     * <p>The diffuse lobe is Lambert {@code base/pi} scaled by {@code (1 - F)} and by
     * {@code (1 - metallic)}: a metal has no diffuse transmission, and whatever the
     * Fresnel reflects as specular is removed from what is transmitted, which is the
     * energy conservation the whole model rests on.</p>
     *
     * @param n         the surface normal (unit)
     * @param v         the direction to the eye (unit)
     * @param l         the direction to the light (unit)
     * @param baseColor linear base colour, 0..1
     * @param metallic  0..1
     * @param roughness 0..1
     * @return the BRDF value per channel, all non-negative
     */
    public static float[] brdf(float[] n, float[] v, float[] l,
            float[] baseColor, float metallic, float roughness) {
        float nDotV = Math.max(dot(n, v), 0f);
        float nDotL = Math.max(dot(n, l), 0f);
        if (nDotL <= 0f || nDotV <= 0f) {
            return new float[] {0f, 0f, 0f};
        }
        float[] h = normalize(new float[] {v[0] + l[0], v[1] + l[1], v[2] + l[2]});
        float nDotH = Math.max(dot(n, h), 0f);
        float hDotV = Math.max(dot(h, v), 0f);

        float[] f0 = f0(baseColor, metallic);
        float d = distributionGgx(nDotH, roughness);
        float g = geometrySmith(nDotV, nDotL, roughness);
        float[] f = fresnelSchlick(hDotV, f0);

        float denom = 4f * nDotV * nDotL + EPS;
        float m = clamp01(metallic);
        float[] bc = baseColor == null ? new float[] {1f, 1f, 1f} : baseColor;
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float spec = (d * g * f[i]) / denom;
            float kd = (1f - f[i]) * (1f - m);
            float diff = kd * bc[i] / PI;
            out[i] = Math.max(0f, diff + spec);
        }
        return out;
    }

    /**
     * The outgoing radiance for one direct light: the BRDF scaled by the light's
     * colour and the Lambert cosine, plus a flat ambient term. This is the whole
     * per-fragment shading the GLSL route runs, spelled once.
     *
     * @param roughness perceptual roughness 0..1
     * @param lightColor the light's linear radiance, per channel
     * @param ambient    the ambient/irradiance term added on top, per channel
     */
    public static float[] shade(float[] n, float[] v, float[] l, float[] baseColor,
            float metallic, float roughness, float[] lightColor, float[] ambient) {
        float nDotL = Math.max(dot(n, l), 0f);
        float[] fr = brdf(n, v, l, baseColor, metallic, roughness);
        float[] amb = ambient == null ? new float[] {0f, 0f, 0f} : ambient;
        float[] lc = lightColor == null ? new float[] {1f, 1f, 1f} : lightColor;
        return new float[] {
            fr[0] * lc[0] * nDotL + amb[0],
            fr[1] * lc[1] * nDotL + amb[1],
            fr[2] * lc[2] * nDotL + amb[2],
        };
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] normalize(float[] a) {
        float len = (float) Math.sqrt(dot(a, a));
        if (len < EPS) {
            return new float[] {0f, 0f, 1f};
        }
        return new float[] {a[0] / len, a[1] / len, a[2] / len};
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
