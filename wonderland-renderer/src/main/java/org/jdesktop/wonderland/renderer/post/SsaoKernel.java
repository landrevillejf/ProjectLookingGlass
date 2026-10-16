/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.7: screen-space ambient occlusion as arithmetic, and the
 * contract the GPU pass is measured against.
 *
 * A bloom adds light, a tone map folds radiance, an anti-alias softens edges, a defocus
 * blurs what the eye is not on; ambient occlusion is the one link that DARKENS -- it puts
 * the contact shadow back into the crevices a rasteriser's flat ambient term washed out.
 * Like the defocus it needs the frame's DEPTH, which at the patched Canvas3D.preSwap()
 * hook is still resident beside the colour, so GpuSsaoPass blits both into textures and
 * estimates, per pixel, how much of the hemisphere above the surface is blocked by nearby
 * geometry. This kernel is that estimate in pure Java: reconstruct a view-space position
 * and normal from the depth field, shoot a fixed hemisphere of samples, count how many a
 * nearer surface hides, and turn the count into an occlusion factor -- with nothing but
 * java.base, the reference the GLSL in GpuSsaoPass is checked against, the same
 * relationship DofKernel has to GpuDofPass and HdrKernel to GpuHdrPass.
 *
 * The reconstruction is the standard symmetric-perspective unprojection: a raw window
 * depth d in 0..1 linearises to {@code near*far / (far - d*(far-near))}, and a pixel's
 * view position is that linear depth scaled by its NDC offset and the frustum tangent. The
 * normal is the cross product of the reconstructed position's screen-space gradients,
 * flipped toward the camera. The occlusion itself is John-Chapman-style hemisphere
 * sampling with a fixed twelve-point kernel (identical literals in the shader, so the two
 * halves cannot drift), a deterministic per-pixel rotation of the tangent frame to break
 * banding, a depth range-check so distant geometry does not occlude, and a bias so a
 * surface does not occlude itself. A post-chain AO can only modulate the composited
 * colour, so {@link #apply} multiplies RGB by the factor -- it darkens a crevice wholesale
 * rather than only its ambient term, the honest approximation available to a pass that
 * sees the final frame and not the direct/ambient split.
 *
 * The honest boundary, identical to every shipped GPU phase: the reconstruction, the
 * sampling and the occlusion count are pure and headless-proven (SsaoKernelTest pins that
 * a flat field is unoccluded, a pit is occluded while a bump is not, the factor stays in
 * 0..1, a zero intensity is the identity and a background field is untouched); the
 * on-screen pass that runs it per frame needs a display and is probe-verified, not
 * asserted in a headless suite.
 */
package org.jdesktop.wonderland.renderer.post;

/**
 * The operations of a screen-space ambient-occlusion pass (roadmap P2.7), as pure
 * functions. Stateless, and not fast by design: this is the definition the GPU pass is
 * checked against. Depth is the raw window value normalised 0..1 (near..far) throughout,
 * matching what a {@code GL_DEPTH_COMPONENT} texture reads back. The depth field is
 * indexed {@code depth[y*width + x]} with {@code y = 0} at the BOTTOM row, the same
 * orientation {@code gl_FragCoord} gives the shader, so the two agree pixel for pixel.
 */
public final class SsaoKernel {

    /** The fixed hemisphere the occlusion is sampled with; identical literals in GLSL. */
    private static final float[][] KERNEL = {
            {0.0000f, 0.0000f, 0.3000f},
            {0.5000f, 0.0000f, 0.4000f},
            {0.0000f, 0.5000f, 0.4000f},
            {-0.5000f, 0.0000f, 0.4000f},
            {0.0000f, -0.5000f, 0.4000f},
            {0.3536f, 0.3536f, 0.5500f},
            {-0.3536f, 0.3536f, 0.5500f},
            {-0.3536f, -0.3536f, 0.5500f},
            {0.3536f, -0.3536f, 0.5500f},
            {0.7071f, 0.0000f, 0.7000f},
            {0.0000f, 0.7071f, 0.7000f},
            {-0.7071f, 0.0000f, 0.7000f},
    };

    /**
     * The occlusion parameters. {@link #DEFAULT} matches the {@code WorldViewer} camera
     * at its minimum back clip (near 0.05, far 1000), a 45-degree vertical field of view
     * and a 16:9 canvas, with a half-metre contact radius at full strength -- a gentle,
     * real crevice darkening rather than a heavy grime pass. A host whose camera differs
     * supplies its own near/far/fov/aspect so the reconstruction matches the frame.
     *
     * @param near         the front clip distance in world units (&gt; 0)
     * @param far          the back clip distance in world units (&gt; near)
     * @param tanHalfFovY  the tangent of half the vertical field of view (&gt; 0)
     * @param aspect       the canvas width over its height (&gt; 0)
     * @param radius       the hemisphere reach in world units (&gt; 0)
     * @param intensity    how far a fully occluded pixel darkens, 0 = off, 1 = to black
     *                     (&ge; 0)
     * @param bias         the linear-depth slack that stops a surface occluding itself
     *                     (&ge; 0)
     */
    public record Settings(float near, float far, float tanHalfFovY, float aspect,
            float radius, float intensity, float bias) {

        public static final Settings DEFAULT = new Settings(0.05f, 1000f,
                (float) Math.tan(Math.toRadians(22.5)), 16f / 9f, 0.5f, 1f, 0.025f);

        public Settings {
            if (!(near > 0f)) {
                throw new IllegalArgumentException("the near clip is positive");
            }
            if (!(far > near)) {
                throw new IllegalArgumentException("the far clip is beyond the near clip");
            }
            if (!(tanHalfFovY > 0f)) {
                throw new IllegalArgumentException("the frustum tangent is positive");
            }
            if (!(aspect > 0f)) {
                throw new IllegalArgumentException("the aspect ratio is positive");
            }
            if (!(radius > 0f)) {
                throw new IllegalArgumentException("the hemisphere radius is positive");
            }
            if (!(intensity >= 0f)) {
                throw new IllegalArgumentException("the intensity is not negative");
            }
            if (!(bias >= 0f)) {
                throw new IllegalArgumentException("the bias is not negative");
            }
        }
    }

    private SsaoKernel() {
    }

    /** How many samples the fixed hemisphere fires per pixel. */
    public static int sampleCount() {
        return KERNEL.length;
    }

    /**
     * Linearise a raw window depth 0..1 into eye depth in world units: {@code near} at
     * d=0 rising to {@code far} at d=1, the exact inverse of a symmetric perspective
     * projection's depth distribution (most precision near the eye).
     */
    public static float linearZ(float d, Settings s) {
        float clamped = Math.max(0f, Math.min(1f, d));
        return s.near() * s.far() / (s.far() - clamped * (s.far() - s.near()));
    }

    /**
     * The occlusion factor at one pixel, 1 (fully lit) down to {@code 1 - intensity}
     * (fully occluded): the fraction of the hemisphere above the surface that a nearer
     * surface hides, scaled by the intensity.
     *
     * @param depth the raw normalised depth field, at least {@code w*h} long
     * @throws IllegalArgumentException if the depth field does not cover the frame
     */
    public static float occlusionAt(float[] depth, int w, int h, int x, int y, Settings s) {
        checkDepth(depth, w, h);
        float[] p = viewPos(depth, w, h, x, y, s);
        float[] n = normal(depth, w, h, x, y, s);
        // A deterministic orthonormal tangent frame around the normal, then a per-pixel
        // rotation of it (integer angle, so the shader's rotation is bit-identical) to
        // break the banding a fixed frame would leave on a flat surface.
        float[] helper = Math.abs(n[0]) > 0.5f ? new float[] {0f, 1f, 0f} : new float[] {1f, 0f, 0f};
        float[] t = normalize(cross(n, helper));
        float[] b = cross(n, t);
        float theta = ((x * 7 + y * 13) & 7) * ((float) Math.PI / 4f);
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        float tx = t[0] * cos + b[0] * sin;
        float ty = t[1] * cos + b[1] * sin;
        float tz = t[2] * cos + b[2] * sin;
        float bx = b[0] * cos - t[0] * sin;
        float by = b[1] * cos - t[1] * sin;
        float bz = b[2] * cos - t[2] * sin;

        float tanX = s.tanHalfFovY() * s.aspect();
        float radius = s.radius();
        int occluded = 0;
        for (float[] sample : KERNEL) {
            // Rotate the hemisphere sample into this pixel's tangent frame.
            float ox = tx * sample[0] + bx * sample[1] + n[0] * sample[2];
            float oy = ty * sample[0] + by * sample[1] + n[1] * sample[2];
            float oz = tz * sample[0] + bz * sample[1] + n[2] * sample[2];
            float spx = p[0] + ox * radius;
            float spy = p[1] + oy * radius;
            float spz = p[2] + oz * radius;
            if (spz <= s.near() * 0.5f) {
                continue; // the sample swung behind the eye; it sees nothing
            }
            // Project the sample back to the screen and read the depth actually there.
            float invZ = 1f / spz;
            float u = (spx * invZ / tanX) * 0.5f + 0.5f;
            float v = (spy * invZ / s.tanHalfFovY()) * 0.5f + 0.5f;
            if (u < 0f || u >= 1f || v < 0f || v >= 1f) {
                continue; // off-frame: no evidence of an occluder
            }
            int sx = clamp((int) (u * w), 0, w - 1);
            int sy = clamp((int) (v * h), 0, h - 1);
            float sceneZ = linearZ(depth[sy * w + sx], s);
            // Range-check: geometry far from this surface is not touching it, so it
            // cannot occlude its contact light.
            if (Math.abs(sceneZ - p[2]) >= radius) {
                continue;
            }
            // The sample is hidden when the surface at that screen point sits between it
            // and the eye (nearer, i.e. a smaller linear depth), past the self-bias.
            if (sceneZ + s.bias() < spz) {
                occluded++;
            }
        }
        float ao = 1f - s.intensity() * (occluded / (float) KERNEL.length);
        return clamp01(ao);
    }

    /**
     * The occlusion factor for every pixel, row-major from the bottom, each in 0..1.
     *
     * @param depth the raw normalised depth field, at least {@code w*h} long
     * @throws IllegalArgumentException if the depth field does not cover the frame
     */
    public static float[] occlusion(float[] depth, int w, int h, Settings s) {
        checkDepth(depth, w, h);
        float[] ao = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                ao[y * w + x] = occlusionAt(depth, w, h, x, y, s);
            }
        }
        return ao;
    }

    /**
     * Darken a frame by its own ambient occlusion: each pixel's RGB is multiplied by its
     * occlusion factor and its alpha is carried through, so a crevice reads as a crevice
     * and a flat wall is unchanged. The colour and the depth must describe the same
     * rectangle.
     *
     * @param color the packed ARGB frame
     * @param depth the raw normalised depth field, at least {@code width*height} long
     * @throws IllegalArgumentException if the depth field does not cover the frame
     */
    public static HdrKernel.Frame apply(HdrKernel.Frame color, float[] depth, Settings s) {
        int w = color.width();
        int h = color.height();
        checkDepth(depth, w, h);
        int[] px = color.argb();
        int[] out = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float ao = occlusionAt(depth, w, h, x, y, s);
                int p = px[i];
                int a = (p >>> 24) & 0xFF;
                int r = Math.round(((p >> 16) & 0xFF) * ao);
                int g = Math.round(((p >> 8) & 0xFF) * ao);
                int b = Math.round((p & 0xFF) * ao);
                out[i] = (a << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
        }
        return new HdrKernel.Frame(w, h, out);
    }

    // ------------------------------------------------------------ reconstruction

    /** The view-space position of a pixel: NDC offset scaled by linear depth and frustum. */
    private static float[] viewPos(float[] depth, int w, int h, int x, int y, Settings s) {
        float linearZ = linearZ(depth[y * w + x], s);
        float ndcX = ((x + 0.5f) / w) * 2f - 1f;
        float ndcY = ((y + 0.5f) / h) * 2f - 1f;
        float tanX = s.tanHalfFovY() * s.aspect();
        return new float[] {ndcX * linearZ * tanX, ndcY * linearZ * s.tanHalfFovY(), linearZ};
    }

    /**
     * The view-space unit normal of a pixel, from the screen-space gradients of the
     * reconstructed position, oriented toward the eye (a negative Z in this +Z-into-screen
     * convention). A degenerate gradient falls back to straight at the viewer.
     */
    private static float[] normal(float[] depth, int w, int h, int x, int y, Settings s) {
        float[] pl = viewPos(depth, w, h, clamp(x - 1, 0, w - 1), y, s);
        float[] pr = viewPos(depth, w, h, clamp(x + 1, 0, w - 1), y, s);
        float[] pd = viewPos(depth, w, h, x, clamp(y - 1, 0, h - 1), s);
        float[] pu = viewPos(depth, w, h, x, clamp(y + 1, 0, h - 1), s);
        float[] dx = {pr[0] - pl[0], pr[1] - pl[1], pr[2] - pl[2]};
        float[] dy = {pu[0] - pd[0], pu[1] - pd[1], pu[2] - pd[2]};
        float[] n = normalize(cross(dx, dy));
        if (n[2] > 0f) {
            n = new float[] {-n[0], -n[1], -n[2]};
        }
        return n;
    }

    // ------------------------------------------------------------------ vector math

    private static float[] cross(float[] a, float[] b) {
        return new float[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
        };
    }

    /** Unit length, or straight at the viewer if the input has no direction. */
    private static float[] normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-8f) {
            return new float[] {0f, 0f, -1f};
        }
        return new float[] {v[0] / len, v[1] / len, v[2] / len};
    }

    private static void checkDepth(float[] depth, int w, int h) {
        if (depth == null || depth.length < w * h) {
            throw new IllegalArgumentException("the depth field must cover the frame");
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
