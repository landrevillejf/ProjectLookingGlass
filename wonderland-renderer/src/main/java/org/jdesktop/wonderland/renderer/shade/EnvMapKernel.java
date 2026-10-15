/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.5: environment reflection and refraction as
 * arithmetic, not as a GPU promise.
 *
 * The roadmap's P0..P5 never enumerated reflection or refraction; they were
 * named later as the missing "modern 3D" beat beside the water and glossy
 * surfaces. Like P3.2's bloom, the honest deliverable under the Java 3D 1.7.2
 * ceiling is the MATHS a real environment pass is built from -- the reflect
 * vector, Snell's refraction with its total-internal-reflection refusal, the
 * Schlick fresnel that weights one against the other, the cube-map face a
 * direction samples, and the two pixel blends that marry them to a base
 * colour. Every one of those runs on float[] and int[] with nothing but
 * java.base, so the answer is assertable in CI; only binding a live cube
 * texture and running the per-fragment program is the probe's job, and
 * EnvironmentPlan says so in the same probe-gated voice as ShaderAppearancePlan.
 *
 * This is the reference a future GPU pass must match vector for vector -- a
 * contract in arithmetic, never a placeholder that claims a painted pixel.
 */
package org.jdesktop.wonderland.renderer.shade;

/**
 * The five operations of an environment-mapping pass (roadmap P2.5), as pure
 * functions on 3-component float vectors and packed ARGB pixels. No state, so
 * no thread-safety concerns; no Java 3D import, so headless-testable.
 *
 * <p>Vector convention, stated once because every formula below depends on it:
 * an {@code incident} vector points where the ray is TRAVELLING (from the eye
 * toward the surface), a {@code normal} points OUT of the surface toward the
 * eye. That is the same handedness GLSL's {@code reflect}/{@code refract} use
 * on the negated view vector, so {@link EnvironmentPlan}'s fragment program and
 * this kernel compute the identical thing from identical inputs.</p>
 */
public final class EnvMapKernel {

    /** A cube-map face and the [0,1] texture coordinate a direction lands on. */
    public record CubeSample(int face, float s, float t) {
    }

    /** The six cube faces in the GL_TEXTURE_CUBE_MAP_POSITIVE_X..NEGATIVE_Z order. */
    public static final int FACE_POS_X = 0;
    public static final int FACE_NEG_X = 1;
    public static final int FACE_POS_Y = 2;
    public static final int FACE_NEG_Y = 3;
    public static final int FACE_POS_Z = 4;
    public static final int FACE_NEG_Z = 5;

    private EnvMapKernel() {
    }

    /**
     * The reflected direction {@code R = I - 2(I.N)N} for a unit-ish incident
     * ray {@code I} and surface normal {@code N}; the result is normalised.
     *
     * <p>Returns {@code null} when either input is the zero vector -- there is
     * no reflection of nothing, and inventing one would put a NaN direction
     * into a cube lookup. A caller that must always draw answers with its own
     * safe default; the kernel reports, it never fabricates.</p>
     */
    public static float[] reflect(float[] incident, float[] normal) {
        float[] i = normalize(incident);
        float[] n = normalize(normal);
        if (i == null || n == null) {
            return null;
        }
        float dot = i[0] * n[0] + i[1] * n[1] + i[2] * n[2];
        return normalize(new float[] {
                i[0] - 2 * dot * n[0],
                i[1] - 2 * dot * n[1],
                i[2] - 2 * dot * n[2],
        });
    }

    /**
     * Snell refraction of {@code incident} into a denser medium across a surface
     * with normal {@code normal} and relative index ratio {@code eta}.
     *
     * <p>GLSL's {@code refract(I, N, eta)} law: with {@code cosi = -(I.N)} and
     * {@code k = 1 - eta^2 (1 - cosi^2)}, the bent ray is
     * {@code eta*I + (eta*cosi - sqrt(k))*N}. When {@code k < 0} the ray is past
     * the critical angle and reflects entirely instead -- total internal
     * reflection -- so this returns {@code null}, the same answer the fragment
     * program detects with {@code dot(refr,refr)==0} and falls back to the
     * reflection. {@code eta == 1} (equal media) returns the incident direction
     * unchanged, which is the identity a test pins.</p>
     */
    public static float[] refract(float[] incident, float[] normal, float eta) {
        float[] i = normalize(incident);
        float[] n = normalize(normal);
        if (i == null || n == null || !(eta > 0f)) {
            return null;
        }
        float cosi = -(i[0] * n[0] + i[1] * n[1] + i[2] * n[2]);
        float k = 1f - eta * eta * (1f - cosi * cosi);
        if (k < 0f) {
            return null; // total internal reflection: no transmitted ray exists
        }
        float coeff = eta * cosi - (float) Math.sqrt(k);
        return normalize(new float[] {
                eta * i[0] + coeff * n[0],
                eta * i[1] + coeff * n[1],
                eta * i[2] + coeff * n[2],
        });
    }

    /**
     * Schlick's fresnel approximation of reflectance at one incidence:
     * {@code F = F0 + (1 - F0)(1 - cos)^5}, with {@code F0 = ((1 - ior)/(1 + ior))^2}.
     *
     * <p>{@code cosTheta} is the (clamped) cosine between the surface normal and
     * the view direction -- head-on gives {@code F0}, grazing approaches 1, the
     * two facts every realistic mirror or water surface turns on. The result is
     * always within [0,1]; a caller hands a raw dot product and gets a weight it
     * can lerp with directly.</p>
     */
    public static float fresnel(float cosTheta, float ior) {
        float f0 = ((1f - ior) / (1f + ior));
        f0 *= f0;
        float c = Math.max(0f, Math.min(1f, cosTheta));
        float oneMinus = 1f - c;
        float f = f0 + (1f - f0) * oneMinus * oneMinus * oneMinus * oneMinus * oneMinus;
        return Math.max(0f, Math.min(1f, f));
    }

    /**
     * Which cube-map face a direction samples and where on it.
     *
     * <p>The canonical OpenGL cube-selection rule: the dominant axis names the
     * face and its magnitude is {@code ma}; the two {@code sc}/{@code tc}
     * cross-axes per the specification table are then mapped into [0,1] as
     * {@code 0.5*(v/ma + 1)}. A direction straight down a face axis lands at the
     * exact face with {@code s == t == 0.5}, which is the anchor every cube
     * lookup shares; nothing here needs a texture object, only the ray.</p>
     */
    public static CubeSample cubeFace(float[] direction) {
        float[] d = normalize(direction);
        if (d == null) {
            return null;
        }
        float x = d[0];
        float y = d[1];
        float z = d[2];
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        int face;
        float sc;
        float tc;
        float ma;
        if (ax >= ay && ax >= az) {                 // X dominant
            ma = ax;
            if (x >= 0) {
                face = FACE_POS_X;
                sc = -z;
                tc = -y;
            } else {
                face = FACE_NEG_X;
                sc = z;
                tc = -y;
            }
        } else if (ay >= ax && ay >= az) {          // Y dominant
            ma = ay;
            if (y >= 0) {
                face = FACE_POS_Y;
                sc = x;
                tc = z;
            } else {
                face = FACE_NEG_Y;
                sc = x;
                tc = -z;
            }
        } else {                                    // Z dominant
            ma = az;
            if (z >= 0) {
                face = FACE_POS_Z;
                sc = x;
                tc = -y;
            } else {
                face = FACE_NEG_Z;
                sc = -x;
                tc = -y;
            }
        }
        return new CubeSample(face, 0.5f * (sc / ma + 1f), 0.5f * (tc / ma + 1f));
    }

    /**
     * Per-channel linear blend of two packed ARGB colours at {@code t}
     * (clamped to [0,1]), alpha from {@code a}. {@code t == 0} is exactly
     * {@code a} and {@code t == 1} is {@code b} -- the identity every
     * reflection/refraction weighting is checked against.
     */
    public static int mix(int a, int b, float t) {
        float w = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = clamp255((int) (ar + (br - ar) * w));
        int g = clamp255((int) (ag + (bg - ag) * w));
        int bl = clamp255((int) (ab + (bb - ab) * w));
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    /**
     * The whole environment composite in one call, spelled like the fragment
     * stage: the refracted colour bent by {@code eta} toward the reflected
     * colour by the {@code fresnel} weight, then that environment colour laid
     * over {@code base} at {@code coverage}. {@code coverage == 0} is the exact
     * base pixel (a surface that names no environment), which is the mirror of
     * {@code BloomKernel.combine}'s strength-zero identity -- every "glossy
     * toggle" downstream will one day mean precisely this number.
     */
    public static int composite(int base, int reflection, int refraction,
                                float fresnel, float coverage) {
        int env = mix(refraction, reflection, fresnel);
        return mix(base, env, coverage);
    }

    /** Normalise a 3-vector, or null when it is null/short/zero-length. */
    private static float[] normalize(float[] v) {
        if (v == null || v.length < 3) {
            return null;
        }
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!(len > 0f) || !Float.isFinite(len)) {
            return null;
        }
        return new float[] {v[0] / len, v[1] / len, v[2] / len};
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
