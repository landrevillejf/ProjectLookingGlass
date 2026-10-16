/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: the SOFTWARE_CAPTURE route as one callable pass.
 *
 * BloomKernel is the maths and PostProcessPlan is the decision; BloomPass is the
 * seam a real capture calls. Hand it the frame a readRaster or a 2D composite
 * produced plus what the host knows, and it returns what the plan allows -- the
 * exact identity when the user asked for no glow (NONE), the bloomed frame
 * otherwise. It is the single entry point the probe-capture pipeline and the 2D
 * desktop's composites use, so neither re-derives the plan-or-kernel wiring, and
 * neither has to know a BufferedImage from a packed-ARGB Frame.
 *
 * What this does NOT do is run a per-frame glow on a live Canvas3D. WlProbe52
 * measured the fork's readback round trip on the reference card (GL 4.6 Mesa
 * radeonsi): ~128 ms for a 640x363 live capture-convert-bloom, and ~419 ms for a
 * native 1920x1080 software bloom. The pass is real and correct -- it bloomed a
 * captured LIVE frame, lifting the halo around a hot quad from luminance 69 to
 * 76 -- but that cost caps a per-frame native composite near 2-8 fps. So the
 * live route is confined to stills, on-demand snapshots and reduced resolution,
 * never per-frame at native res, and that measured number is exactly why
 * PostProcessPlan keeps OFFSCREEN_READBACK gated behind READBACK_PROBED_USABLE
 * rather than enabling it on a wish.
 */
package org.jdesktop.wonderland.renderer.post;

import java.awt.image.BufferedImage;
import java.util.Set;

/**
 * The P3.2 software-capture bloom as a single pass a capture path calls: a frame
 * plus the host's capabilities in, the plan-allowed frame out. Pure and
 * stateless like {@link BloomKernel}; the only decision it owns is the identity
 * the NONE path must preserve pixel for pixel.
 */
public final class BloomPass {

    /**
     * The bloom parameters. {@link #DEFAULT} is the exact setting WlProbe52 ran
     * when it measured the round trip and proved the glow, so a caller that
     * accepts the default reproduces the measured, verified pass.
     */
    public record Settings(float threshold, float knee, int radius, int passes, float strength) {

        /** threshold 128, knee 32, radius 3, two blur passes, 0.6 strength. */
        public static final Settings DEFAULT = new Settings(128f, 32f, 3, 2, 0.6f);

        public Settings {
            if (threshold < 0f || knee < 0f || radius < 0 || passes < 0 || strength < 0f) {
                throw new IllegalArgumentException(
                        "bloom settings are magnitudes, not regrets -- none may be negative");
            }
        }
    }

    private BloomPass() {
    }

    /**
     * Apply a decision already in hand. NONE returns {@code frame} itself -- the
     * identity, not a copy, because a user who asked for no glow must get the
     * pixels they rendered and a needless copy is a per-frame cost this pass
     * exists to avoid. Any other path blooms with {@code settings} (null settings
     * mean {@link Settings#DEFAULT}). A null frame is returned null: a capture
     * that produced nothing is not this pass's error to invent pixels for.
     */
    public static BloomKernel.Frame apply(BloomKernel.Frame frame,
            PostProcessPlan.Decision decision, Settings settings) {
        if (frame == null) {
            return null;
        }
        if (decision == null || decision.path() == PostProcessPlan.Path.NONE) {
            return frame;
        }
        Settings s = settings == null ? Settings.DEFAULT : settings;
        return BloomKernel.bloom(frame, s.threshold(), s.knee(),
                s.radius(), s.passes(), s.strength());
    }

    /**
     * The one call a host usually wants: decide from what it knows, then apply.
     * An empty or null capability set decides NONE, so this is the identity --
     * a world that never asked for bloom is untouched by having the pass wired.
     */
    public static BloomKernel.Frame apply(BloomKernel.Frame frame,
            Set<PostProcessPlan.Capability> capabilities, Settings settings) {
        return apply(frame, PostProcessPlan.decide(capabilities), settings);
    }

    /**
     * The BufferedImage seam: a captured or composited image in, the plan-allowed
     * image out. NONE returns {@code image} itself (the identity, no copy); a
     * bloomed path returns a fresh TYPE_INT_ARGB image. Null in, null out.
     */
    public static BufferedImage apply(BufferedImage image,
            PostProcessPlan.Decision decision, Settings settings) {
        if (image == null) {
            return null;
        }
        BloomKernel.Frame src = toFrame(image);
        BloomKernel.Frame out = apply(src, decision, settings);
        return out == src ? image : toImage(out);
    }

    /** Copy a BufferedImage's pixels into a kernel {@link BloomKernel.Frame}. */
    public static BloomKernel.Frame toFrame(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] argb = new int[w * h];
        image.getRGB(0, 0, w, h, argb, 0, w);
        return new BloomKernel.Frame(w, h, argb);
    }

    /** Copy a kernel {@link BloomKernel.Frame}'s pixels into a TYPE_INT_ARGB image. */
    public static BufferedImage toImage(BloomKernel.Frame frame) {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(),
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, frame.width(), frame.height(), frame.argb(), 0, frame.width());
        return image;
    }
}
