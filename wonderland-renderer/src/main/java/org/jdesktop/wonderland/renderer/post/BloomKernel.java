/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: bloom as arithmetic, not as a GPU promise.
 *
 * The roadmap marks this phase stretch for a reason it states out loud:
 * Java 3D 1.7.2 has no post-processing pipeline. The fork's offscreen answer
 * is Canvas3D render-to-image with pixel readback -- not a texture the scene
 * renders into and a shader samples back -- so a per-frame GPU bloom on the
 * LIVE 3D canvas is not a missing flag, it is a missing architecture, and no
 * test in this repository may claim it. What IS honest, headless and useful
 * today is the bloom MATHS on pixels: bright-pass extraction, separable box
 * blurring, additive re-composite. Those three are every bloom ever made --
 * Unreal's and a demoscene's alike are the same operations with GPU shapes --
 * and they run on an int[] with nothing but java.base.
 *
 * Two real consumers, both this side of the ceiling: the probe pipeline
 * (a captured frame gains its glow in software, so "does the clinic read as
 * emissive" becomes an answer a screenshot can show), and the 2D desktop's
 * composites. If a future probe run shows the fork's offscreen chain can
 * sustain a per-frame round trip, this kernel is the reference the GPU pass
 * must match -- a contract in pixels, not a placeholder.
 */
package org.jdesktop.wonderland.renderer.post;

/**
 * The three operations of a bloom pass (roadmap P3.2), as pure functions on
 * packed ARGB pixels. Not thread-safe by state -- there is none -- and not
 * fast by design: this is the definition a fast one is checked against.
 */
public final class BloomKernel {

    /** A frame the kernel can be told about: its size and its packed pixels. */
    public record Frame(int width, int height, int[] argb) {

        public Frame {
            if (width <= 0 || height <= 0 || argb == null || argb.length < width * height) {
                throw new IllegalArgumentException("a frame is a real rectangle of pixels");
            }
        }
    }

    private BloomKernel() {
    }

    /**
     * Photometric brightness of one packed pixel: the brightest channel,
     * which is what a lens flares on (a saturated red dot blooms as much as
     * a white one of the same R value). Alpha is ignored -- extraction works
     * on what the image IS, not where it is opaque.
     */
    public static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return Math.max(r, Math.max(g, b));
    }

    /**
     * The bright pass, in 0..255 luminance units: pixels below {@code
     * threshold - knee} contribute nothing, pixels above {@code threshold +
     * knee} contribute fully, and between them a smoothstep keeps the knee
     * free of the hard edge a raw threshold prints as a ring. The colour is
     * preserved and scaled -- a bloom glows in the colour it was lit in.
     */
    public static Frame extract(Frame src, float threshold, float knee) {
        float lo = Math.max(0f, threshold - Math.max(0f, knee));
        float hi = lo + Math.max(1e-3f, 2f * Math.max(1e-3f, knee));
        int[] out = new int[src.argb().length];
        for (int i = 0; i < src.argb().length; i++) {
            int argb = src.argb()[i];
            float lum = luminance(argb);
            if (lum <= lo) {
                continue; // stays black
            }
            float t = Math.min(1f, (lum - lo) / (hi - lo));
            float weight = t * t * (3f - 2f * t); // smoothstep, [0,1]
            int r = clamp255((int) (((argb >> 16) & 0xFF) * weight));
            int g = clamp255((int) (((argb >> 8) & 0xFF) * weight));
            int b = clamp255((int) ((argb & 0xFF) * weight));
            out[i] = (255 << 24) | (r << 16) | (g << 8) | b;
        }
        return new Frame(src.width(), src.height(), out);
    }

    /**
     * One separable box blur: horizontal then vertical, radius r, edge pixels
     * clamped (a bloom must not eat a dark border into the frame -- the
     * wrap-around alternative glows the opposite edge in). Integer
     * accumulation keeps it exact: repeated passes of a small box converge
     * toward the Gaussian a real bloom wants, with no floating drift.
     */
    public static Frame blur(Frame src, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("a blur radius is a distance, not a regret");
        }
        int w = src.width();
        int h = src.height();
        int[] horizontal = new int[src.argb().length];
        int[] vertical = new int[src.argb().length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boxChannel(src.argb(), horizontal, w, y * w + x, x, y, radius, true);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boxChannel(horizontal, vertical, w, y * w + x, x, y, radius, false);
            }
        }
        return new Frame(w, h, vertical);
    }

    private static void boxChannel(int[] src, int[] dst, int width, int index,
            int x, int y, int radius, boolean horizontal) {
        int rSum = 0;
        int gSum = 0;
        int bSum = 0;
        int count = 0;
        int stride = width;
        for (int k = -radius; k <= radius; k++) {
            int sx = horizontal ? clamp(x + k, 0, width - 1) : x;
            int sy = horizontal ? y : clamp(y + k, 0, (int) (src.length / stride) - 1);
            int argb = src[sy * stride + sx];
            rSum += (argb >> 16) & 0xFF;
            gSum += (argb >> 8) & 0xFF;
            bSum += argb & 0xFF;
            count++;
        }
        dst[index] = (255 << 24)
                | ((rSum / count) << 16) | ((gSum / count) << 8) | (bSum / count);
    }

    /**
     * The add: composite the glow over the base at {@code strength}, each
     * channel saturating at 255, alpha taken from the base -- bloom adds
     * light, it never makes pixels transparent. strength 0 is the exact
     * identity (a test pins this, because every "cinematic toggle" in the
     * shell will one day mean exactly this number).
     */
    public static Frame combine(Frame base, Frame glow, float strength) {
        float s = Math.max(0f, strength);
        int[] out = new int[base.argb().length];
        for (int i = 0; i < out.length; i++) {
            int b = base.argb()[i];
            int g = glow.argb()[i];
            int r = clamp255((int) (((b >> 16) & 0xFF) + ((g >> 16) & 0xFF) * s));
            int gc = clamp255((int) (((b >> 8) & 0xFF) + ((g >> 8) & 0xFF) * s));
            int bl = clamp255((int) ((b & 0xFF) + (g & 0xFF) * s));
            out[i] = (b & 0xFF000000) | (r << 16) | (gc << 8) | bl;
        }
        return new Frame(base.width(), base.height(), out);
    }

    /** The whole pass, spelled once: extract, soften, add. */
    public static Frame bloom(Frame src, float threshold, float knee,
            int radius, int passes, float strength) {
        Frame glow = extract(src, threshold, knee);
        for (int p = 0; p < passes; p++) {
            glow = blur(glow, radius);
        }
        return combine(src, glow, strength);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
