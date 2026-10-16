/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.3: HDR tone mapping as arithmetic, and the contract the
 * GPU pass is measured against.
 *
 * A bloom (P3.2) adds light; tone mapping is the other half of a high-dynamic-range
 * frame -- it folds scene-referred radiance, which can sit far above the 1.0 a
 * display can show, back into the 0..1 the swap buffer carries, without crushing the
 * highlights to white or lifting the shadows to grey. The operations here are every
 * tone mapper a real-time renderer ships: an exposure scale, a filmic curve
 * (extended Reinhard with a white point, or the ACES approximation), and a gamma
 * encode. They run on a packed ARGB int[] and on float triples with nothing but
 * java.base, which is what makes this kernel the reference the GLSL pass in
 * GpuHdrPass is checked against pixel for pixel -- the same relationship BloomKernel
 * has to GpuBloomPass.
 *
 * The honest boundary, identical to the shipped bloom: the CURVE is pure and
 * headless-proven (HdrKernelTest pins the identity, monotonicity, the white point
 * and the black point, and the ACES/Reinhard split); the on-screen pass that runs it
 * per frame on the live canvas is GpuHdrPass at the patched Canvas3D.preSwap hook,
 * which needs a display and is probe-verified, not asserted in a headless suite.
 */
package org.jdesktop.wonderland.renderer.post;

/**
 * The operations of an HDR tone-mapping pass (roadmap P3.3), as pure functions.
 * Stateless, and not fast by design: this is the definition the GPU curve is
 * checked against.
 */
public final class HdrKernel {

    /**
     * The filmic curve a tone mapper folds radiance through.
     *
     * <ul>
     *   <li>{@link #LINEAR} is the identity curve -- exposure and gamma still
     *       apply, but the shape is untouched. A test pins that exposure 1 and
     *       gamma 1 under LINEAR is the exact identity, because every "HDR off"
     *       toggle in a shell will one day mean precisely this.</li>
     *   <li>{@link #REINHARD} is extended Reinhard with a white point: it never
     *       clips below the white point and rolls the shoulder smoothly.</li>
     *   <li>{@link #ACES} is the Narkowicz ACES filmic approximation -- the curve
     *       a real-time renderer reaches for when highlights must desaturate
     *       toward white rather than hue-shift.</li>
     * </ul>
     */
    public enum Operator {
        LINEAR,
        REINHARD,
        ACES
    }

    /**
     * The tone-mapping parameters. {@link #DEFAULT} is a neutral, physically
     * sensible starting point (unity exposure, extended Reinhard to a white point
     * of 4, sRGB-ish gamma 2.2), so a host that accepts the default gets a curve
     * that neither crushes nor blows out a normally-lit scene.
     *
     * @param exposure    linear exposure scale applied before the curve (&gt; 0)
     * @param operator    the filmic curve to fold through
     * @param gamma       the encode gamma applied after the curve (&gt; 0)
     * @param whitePoint  the smallest input the {@link Operator#REINHARD} curve maps
     *                    to 1.0 (&gt; 0); ignored by the other operators
     */
    public record Settings(float exposure, Operator operator, float gamma, float whitePoint) {

        public static final Settings DEFAULT =
                new Settings(1.0f, Operator.REINHARD, 2.2f, 4.0f);

        public Settings {
            if (!(exposure > 0f)) {
                throw new IllegalArgumentException("exposure is a positive scale");
            }
            if (operator == null) {
                throw new IllegalArgumentException("a tone mapper needs a curve");
            }
            if (!(gamma > 0f)) {
                throw new IllegalArgumentException("gamma is a positive exponent");
            }
            if (!(whitePoint > 0f)) {
                throw new IllegalArgumentException("the white point is a positive radiance");
            }
        }
    }

    private HdrKernel() {
    }

    /**
     * Photometric luminance of a linear-RGB triple, Rec.709 weights -- the value a
     * tone mapper may key on and the value FXAA (P3.4) reads edges from. Kept here
     * so the whole post chain agrees on one definition of "how bright".
     */
    public static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /**
     * Extended Reinhard with a white point: {@code x(1 + x/white^2) / (1 + x)}. It
     * maps 0 to 0, is strictly increasing, and reaches exactly 1.0 at
     * {@code x == whitePoint} -- the two facts HdrKernelTest pins, because a curve
     * that does not hit its own white point is not the curve it claims to be.
     *
     * @param x          the exposed radiance, clamped non-negative
     * @param whitePoint the radiance that maps to 1.0 (&gt; 0)
     */
    public static float reinhard(float x, float whitePoint) {
        float xi = Math.max(0f, x);
        float wp = Math.max(1e-4f, whitePoint);
        float wp2 = wp * wp;
        return (xi * (1f + xi / wp2)) / (1f + xi);
    }

    /**
     * The Narkowicz ACES filmic approximation, evaluated per channel and clamped to
     * 0..1. The constants are the published fit, not a tuning guess, so the GLSL
     * pass and this kernel cannot disagree about the shape.
     */
    public static float aces(float x) {
        final float a = 2.51f;
        final float b = 0.03f;
        final float c = 2.43f;
        final float d = 0.59f;
        final float e = 0.14f;
        float xi = Math.max(0f, x);
        float v = (xi * (a * xi + b)) / (xi * (c * xi + d) + e);
        return clamp01(v);
    }

    /**
     * The curve alone, one scalar: exposure, then the operator, then gamma. This is
     * the whole tone map, spelled once, so {@link #tonemap} and the GLSL fragment
     * shader are the same function. The result is always in 0..1.
     */
    public static float curve(float radiance, Settings s) {
        float exposed = Math.max(0f, radiance) * s.exposure();
        float mapped = switch (s.operator()) {
            case LINEAR -> exposed;
            case REINHARD -> reinhard(exposed, s.whitePoint());
            case ACES -> aces(exposed);
        };
        // Gamma-encode the display-referred value; a LINEAR curve with gamma 1 is
        // the identity, which the test asserts.
        return clamp01((float) Math.pow(clamp01(mapped), 1.0f / s.gamma()));
    }

    /**
     * Tone map one packed ARGB pixel. The stored 8-bit channels are treated as the
     * scene radiance already normalised to 0..1 (the fixed-function framebuffer the
     * preSwap hook sees is LDR, so a brighter-than-white scene arrives pre-scaled by
     * whatever the rig exposed); alpha is carried through untouched -- tone mapping
     * changes colour, never coverage.
     */
    public static int tonemapArgb(int argb, Settings s) {
        int a = (argb >>> 24) & 0xFF;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        int ro = Math.round(curve(r, s) * 255f);
        int go = Math.round(curve(g, s) * 255f);
        int bo = Math.round(curve(b, s) * 255f);
        return (a << 24) | (clamp255(ro) << 16) | (clamp255(go) << 8) | clamp255(bo);
    }

    /** A frame the kernel can be told about: its size and its packed pixels. */
    public record Frame(int width, int height, int[] argb) {

        public Frame {
            if (width <= 0 || height <= 0 || argb == null || argb.length < width * height) {
                throw new IllegalArgumentException("a frame is a real rectangle of pixels");
            }
        }
    }

    /** The whole frame tone mapped, pixel by pixel, through {@link #tonemapArgb}. */
    public static Frame tonemap(Frame src, Settings s) {
        int[] out = new int[src.argb().length];
        for (int i = 0; i < src.argb().length; i++) {
            out[i] = tonemapArgb(src.argb()[i], s);
        }
        return new Frame(src.width(), src.height(), out);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
