/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.5: depth of field as arithmetic, and the contract the GPU
 * pass is measured against.
 *
 * A bloom adds light, a tone map folds radiance, an anti-alias softens edges; depth of
 * field is the fourth cosmetic link and the first that needs a SECOND buffer -- the
 * frame's own depth. At the patched Canvas3D.preSwap() hook the depth the renderer just
 * wrote is still resident beside the colour, so GpuDofPass blits both into textures and
 * defocuses what the eye is not focused on. This kernel is the defocus in pure Java: the
 * circle of confusion a depth implies, and a disc gather whose radius that circle
 * scales, run over a packed ARGB colour frame and a normalised depth field with nothing
 * but java.base -- the reference the GLSL in GpuDofPass is checked against, the same
 * relationship BloomKernel has to GpuBloomPass and HdrKernel to GpuHdrPass.
 *
 * It implements the "scatter-as-gather" simplification: each pixel averages the colour
 * inside a disc whose radius is its own circle of confusion, so a pixel at the focus
 * distance gathers a radius-0 disc (itself, unchanged) and a pixel far from focus
 * gathers a wide, uniformly-weighted bokeh. That is a real, visible defocus and it is
 * deterministic and testable, which is why the kernel ships it rather than a variable
 * hex/sprite bokeh the shader could not be checked against pixel for pixel.
 *
 * The honest boundary, identical to every shipped GPU phase: the CURVE and the gather
 * are pure and headless-proven (DofKernelTest pins the focus point, the monotonic fall-
 * off, the symmetry, the radius cap, and that a uniform frame survives any depth
 * untouched); the on-screen pass that runs it per frame needs a display and is probe-
 * verified, not asserted in a headless suite.
 */
package org.jdesktop.wonderland.renderer.post;

/**
 * The operations of a depth-of-field pass (roadmap P3.5), as pure functions.
 * Stateless, and not fast by design: this is the definition the GPU pass is checked
 * against. Depth is normalised 0..1 (near..far) throughout, matching what a
 * {@code GL_DEPTH_COMPONENT} texture reads back.
 */
public final class DofKernel {

    /**
     * The defocus parameters. {@link #DEFAULT} focuses on mid-depth with a shallow
     * sharp band and a modest six-pixel maximum gather, so a host that accepts the
     * default gets a gentle, real defocus rather than a tilt-shift miniature.
     *
     * @param focusDistance the normalised depth that is perfectly sharp (0..1)
     * @param focusRange    the depth band around {@code focusDistance} that stays
     *                      acceptably sharp before the circle of confusion saturates
     *                      (&gt; 0)
     * @param maxRadiusPx   the largest gather radius in pixels at full defocus
     *                      (&ge; 0); 0 disables the effect entirely
     */
    public record Settings(float focusDistance, float focusRange, float maxRadiusPx) {

        public static final Settings DEFAULT = new Settings(0.5f, 0.3f, 6f);

        public Settings {
            if (!(focusDistance >= 0f)) {
                throw new IllegalArgumentException("the focus distance is a non-negative depth");
            }
            if (!(focusRange > 0f)) {
                throw new IllegalArgumentException("the focus range is a positive depth band");
            }
            if (!(maxRadiusPx >= 0f)) {
                throw new IllegalArgumentException("the gather radius is not negative");
            }
        }
    }

    private DofKernel() {
    }

    /**
     * The circle of confusion for a depth, normalised 0..1: 0 at the focus distance,
     * rising linearly to 1 once the depth is {@code focusRange} away, and clamped there.
     * Symmetric about the focus, so near blur and far blur fall off identically.
     */
    public static float circleOfConfusion(float depth, Settings s) {
        float delta = Math.abs(depth - s.focusDistance());
        return clamp01(delta / s.focusRange());
    }

    /**
     * The gather radius in whole pixels for a depth: the circle of confusion scaled by
     * the maximum radius. 0 at the focus (the pixel keeps itself), {@code maxRadiusPx}
     * at full defocus.
     */
    public static int radiusPx(float depth, Settings s) {
        return Math.round(circleOfConfusion(depth, s) * s.maxRadiusPx());
    }

    /**
     * Defocus one frame: each pixel averages the colour inside a disc of its own radius
     * ({@link #radiusPx}), edge-clamped, so a pixel at the focus is returned untouched
     * and a far pixel gathers a wide bokeh. The colour and the depth must describe the
     * same rectangle.
     *
     * @param color the packed ARGB frame
     * @param depth the normalised 0..1 depth, at least {@code width*height} long
     * @throws IllegalArgumentException if the depth field does not cover the frame
     */
    public static HdrKernel.Frame gather(HdrKernel.Frame color, float[] depth, Settings s) {
        int w = color.width();
        int h = color.height();
        if (depth == null || depth.length < w * h) {
            throw new IllegalArgumentException("the depth field must cover the frame");
        }
        int[] px = color.argb();
        int[] out = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int r = radiusPx(depth[i], s);
                out[i] = r <= 0 ? px[i] : discAverage(px, w, h, x, y, r);
            }
        }
        return new HdrKernel.Frame(w, h, out);
    }

    /** The mean ARGB over the disc of radius {@code r} centred at (cx,cy), edge-clamped. */
    private static int discAverage(int[] px, int w, int h, int cx, int cy, int r) {
        long a = 0;
        long rr = 0;
        long gg = 0;
        long bb = 0;
        int n = 0;
        for (int dy = -r; dy <= r; dy++) {
            int y = cy + dy;
            if (y < 0 || y >= h) {
                continue;
            }
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy > r * r) {
                    continue;
                }
                int x = cx + dx;
                if (x < 0 || x >= w) {
                    continue;
                }
                int p = px[y * w + x];
                a += (p >>> 24) & 0xFF;
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return px[cy * w + cx];
        }
        return ((int) (a / n) << 24) | ((int) (rr / n) << 16)
                | ((int) (gg / n) << 8) | (int) (bb / n);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
