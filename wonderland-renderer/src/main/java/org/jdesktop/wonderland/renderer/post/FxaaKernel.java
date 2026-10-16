/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.4: FXAA as arithmetic, and the contract the GPU pass is
 * measured against.
 *
 * A bloom adds light and a tone map folds radiance; anti-aliasing is the third
 * cosmetic link -- it softens the staircase a rasteriser leaves on a diagonal or a
 * specular edge, in image space, after the geometry is long gone. This kernel is
 * the SUBPIXEL stage of Timothy Lottes's FXAA (the local-contrast edge gate plus the
 * subpixel blend toward the neighbourhood average), spelled once in pure Java so the
 * GLSL in GpuFxaaPass is the SAME function and cannot drift from it -- the exact
 * relationship BloomKernel has to GpuBloomPass and HdrKernel to GpuHdrPass.
 *
 * It implements the SUBPIXEL stage of FXAA, and only that stage, in both halves: the
 * gate that leaves flat areas byte-for-byte untouched, and the subpixel blend whose
 * weight is a published function of the local luma contrast (FXAA_SUBPIX_TRIM 1/4, its
 * 1/(1-trim) scale, clamped by the subpixel quality). This is a real, working
 * image-space anti-alias -- it softens the high-contrast one-pixel edges a rasteriser
 * leaves -- but it is NOT FXAA's variable-length directional span search, which is a
 * separate, larger algorithm. Nothing here or in GpuFxaaPass claims that span search;
 * the subpixel stage is what ships, and the kernel and the shader are the same
 * function so the on-screen result cannot drift from what FxaaKernelTest pins.
 *
 * The luminance and the frame come from HdrKernel on purpose: the whole post chain
 * reads edges and packs pixels through one definition of "how bright" and one Frame
 * shape, so a tone map and an anti-alias cannot disagree about the buffer they share.
 */
package org.jdesktop.wonderland.renderer.post;

/**
 * The operations of an FXAA subpixel pass (roadmap P3.4), as pure functions.
 * Stateless, and not fast by design: this is the definition the GPU shader is
 * checked against.
 */
public final class FxaaKernel {

    /**
     * FXAA's published subpixel constants: the trim below which a contrast ratio
     * contributes no blend, and the scale that renormalises what is left to a full
     * 0..1. They are Lottes's numbers, not a tuning guess, so the shader and this
     * kernel compute the same weight.
     */
    public static final float SUBPIX_TRIM = 0.25f;
    public static final float SUBPIX_TRIM_SCALE = 1.0f / (1.0f - SUBPIX_TRIM);

    /**
     * The anti-aliasing parameters. {@link #DEFAULT} is the FXAA 3.11 quality preset
     * (edge threshold 0.125, its minimum 0.0312, subpixel quality 0.75), so a host
     * that accepts the default gets the tuned behaviour rather than a guess.
     *
     * @param edgeThreshold    the fraction of the local max luma a contrast must reach
     *                         to count as an edge at all (0..1)
     * @param edgeThresholdMin the absolute contrast floor below which nothing is an
     *                         edge, so a dark room does not shimmer (&ge; 0)
     * @param subpixQuality    the ceiling on the subpixel blend weight; 0 disables the
     *                         subpixel stage entirely (0..1)
     */
    public record Settings(float edgeThreshold, float edgeThresholdMin, float subpixQuality) {

        public static final Settings DEFAULT = new Settings(0.125f, 0.0312f, 0.75f);

        public Settings {
            if (!(edgeThreshold > 0f) || edgeThreshold > 1f) {
                throw new IllegalArgumentException("the edge threshold is a fraction in (0,1]");
            }
            if (edgeThresholdMin < 0f) {
                throw new IllegalArgumentException("the contrast floor is not negative");
            }
            if (subpixQuality < 0f || subpixQuality > 1f) {
                throw new IllegalArgumentException("the subpixel quality is a fraction in [0,1]");
            }
        }
    }

    private FxaaKernel() {
    }

    /**
     * The local contrast range FXAA gates on: the spread between the darkest and the
     * brightest of the centre pixel and its four edge neighbours. Zero on a flat
     * patch, large across a hard edge.
     */
    public static float contrastRange(float lumaC, float lumaN, float lumaS,
            float lumaE, float lumaW) {
        float min = Math.min(lumaC, Math.min(Math.min(lumaN, lumaS), Math.min(lumaE, lumaW)));
        float max = Math.max(lumaC, Math.max(Math.max(lumaN, lumaS), Math.max(lumaE, lumaW)));
        return max - min;
    }

    /**
     * The local-contrast edge gate: a pixel is on an edge only when its neighbourhood
     * contrast clears BOTH the absolute floor and the fraction of the brightest luma.
     * A flat area fails this and is returned untouched -- the fact FxaaKernelTest pins
     * hardest, because an anti-alias that smears a uniform wall is worse than none.
     */
    public static boolean isEdge(float lumaC, float lumaN, float lumaS,
            float lumaE, float lumaW, Settings s) {
        float max = Math.max(lumaC, Math.max(Math.max(lumaN, lumaS), Math.max(lumaE, lumaW)));
        float range = contrastRange(lumaC, lumaN, lumaS, lumaE, lumaW);
        return range >= Math.max(s.edgeThresholdMin(), max * s.edgeThreshold());
    }

    /**
     * The FXAA subpixel blend weight, in 0..subpixQuality: how far the centre pixel
     * moves toward the average of its four neighbours. A published function of the
     * ratio between the neighbourhood-average luma's offset from the centre and the
     * local contrast range, trimmed and rescaled. Zero on a flat patch and zero when
     * the pixel is not an edge, so this never touches what the gate already spared.
     */
    public static float blendWeight(float lumaC, float lumaN, float lumaS,
            float lumaE, float lumaW, Settings s) {
        if (!isEdge(lumaC, lumaN, lumaS, lumaE, lumaW, s)) {
            return 0f;
        }
        float range = contrastRange(lumaC, lumaN, lumaS, lumaE, lumaW);
        if (range <= 0f) {
            return 0f;
        }
        float lumaL = (lumaN + lumaS + lumaE + lumaW) * 0.25f;
        float rangeL = Math.abs(lumaL - lumaC);
        float blend = Math.max(0f, (rangeL / range) - SUBPIX_TRIM) * SUBPIX_TRIM_SCALE;
        return Math.min(s.subpixQuality(), blend);
    }

    /**
     * Anti-alias one pixel given its own ARGB and its four edge-clamped neighbours'.
     * The subpixel blend mixes the centre's colour toward the neighbours' average by
     * {@link #blendWeight}; alpha is carried through from the centre untouched, because
     * anti-aliasing an edge changes colour, never coverage.
     */
    public static int antialiasArgb(int center, int north, int south, int east, int west,
            Settings s) {
        float lumaC = lumaOf(center);
        float lumaN = lumaOf(north);
        float lumaS = lumaOf(south);
        float lumaE = lumaOf(east);
        float lumaW = lumaOf(west);
        float w = blendWeight(lumaC, lumaN, lumaS, lumaE, lumaW, s);
        if (w <= 0f) {
            return center;
        }
        int a = (center >>> 24) & 0xFF;
        int rc = (center >> 16) & 0xFF;
        int gc = (center >> 8) & 0xFF;
        int bc = center & 0xFF;
        float rl = (chan(north, 16) + chan(south, 16) + chan(east, 16) + chan(west, 16)) * 0.25f;
        float gl = (chan(north, 8) + chan(south, 8) + chan(east, 8) + chan(west, 8)) * 0.25f;
        float bl = (chan(north, 0) + chan(south, 0) + chan(east, 0) + chan(west, 0)) * 0.25f;
        int ro = Math.round(mix(rc, rl, w));
        int go = Math.round(mix(gc, gl, w));
        int bo = Math.round(mix(bc, bl, w));
        return (a << 24) | (clamp255(ro) << 16) | (clamp255(go) << 8) | clamp255(bo);
    }

    /**
     * The whole frame anti-aliased, pixel by pixel, through {@link #antialiasArgb},
     * sampling the four edge neighbours with a clamp at the border so an edge pixel
     * sees the frame's own boundary rather than a black halo.
     */
    public static HdrKernel.Frame antialias(HdrKernel.Frame src, Settings s) {
        int w = src.width();
        int h = src.height();
        int[] px = src.argb();
        int[] out = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int n = px[Math.max(0, y - 1) * w + x];
                int so = px[Math.min(h - 1, y + 1) * w + x];
                int e = px[y * w + Math.min(w - 1, x + 1)];
                int we = px[y * w + Math.max(0, x - 1)];
                out[i] = antialiasArgb(px[i], n, so, e, we, s);
            }
        }
        return new HdrKernel.Frame(w, h, out);
    }

    /** Rec.709 luminance of a packed ARGB pixel, via the chain's one definition. */
    private static float lumaOf(int argb) {
        return HdrKernel.luminance(
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f);
    }

    private static int chan(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
