/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.3: the Canvas3D that carries the GPU post-process chain.
 *
 * This generalizes the P3.2 BloomCanvas. That canvas ran exactly one pass
 * (GpuBloomPass); a high-dynamic-range frame is not one effect but a chain -- tone
 * map, bloom, and the anti-alias/depth passes that follow -- so the seam now runs
 * whatever ordered links a host hands it, each a GpuPostEffect, and it fires them all
 * inside one preSwap() against the one context Java 3D made current.
 *
 * It exists because a pass has to fire at one exact moment -- after this frame's
 * rendering is synchronized and the context made current, immediately before the
 * buffer swap that presents it -- and the only hook at that moment is the overridable
 * Canvas3D.preSwap() a two-file source patch adds to Java 3D 1.7.2
 * (lg3d-core/src/patches/java3d-core-preswap). The public postRender() hook cannot
 * serve: it runs earlier in the render pass, before the sync, where the default
 * framebuffer still reads black and driving GL is pathological (~1 s/frame) --
 * WlProbe54 measured exactly that, which is why the patch and so this class exist.
 *
 * The method is deliberately thin. It reads the current context once and hands it,
 * with the canvas size, to each link in order -- each link owns its own GL chain,
 * state bracket and failure latch, so a link that faults latches ITSELF off and the
 * next frame skips it without the canvas having to know why. It is NOT annotated
 * @Override: the build resolves the STOCK org.jogamp.java3d:java3d-core:1.7.2, whose
 * Canvas3D declares no preSwap() to override. WorldViewer.preSwapHookPresent() reflects
 * for that method, finds it absent, and so decides NO effect takes the GPU_POSTPROCESS
 * route -- the chain is empty, WorldViewer builds a plain Canvas3D, and this class is
 * never even constructed. Even were one built, a stock Renderer never calls preSwap(),
 * so the world renders without its post chain rather than crashing. This class and the
 * whole Gpu*Pass chain it drives stay compiled and DORMANT: Phase 2 re-hosts them on a
 * JOGL GLEventListener, which is the per-frame seam stock java3d lacks.
 *
 * Not constructed in CI: a Canvas3D needs a display and the native GL stack, so this
 * is probe-verified (WlProbe55/56 measured the bloom chain at 71-94 fps and read the
 * visible halo off the swapped buffer), exactly like the rest of this module's live
 * window.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.GraphicsConfiguration;
import java.util.List;

import com.jogamp.opengl.GLContext;

import org.jdesktop.wonderland.renderer.post.GpuPostEffect;
import org.jogamp.java3d.Canvas3D;

/**
 * A {@link Canvas3D} that runs an ordered chain of {@link GpuPostEffect} links on
 * every presented frame in GL, at the patched {@code preSwap()} hook.
 * {@link WorldViewer} builds one in place of a plain canvas when {@code
 * PostProcessPlan} decides any effect takes the GPU_POSTPROCESS route, handing it the
 * passes for whichever effects were decided on.
 */
public final class PostProcessCanvas extends Canvas3D {

    private final List<GpuPostEffect> effects;

    /**
     * @param config  the graphics configuration, normally
     *                {@code SimpleUniverse.getPreferredConfiguration()}
     * @param effects the ordered post-process links to run each frame; a null or empty
     *                chain makes this an ordinary canvas whose preSwap does nothing
     */
    public PostProcessCanvas(GraphicsConfiguration config, GpuPostEffect... effects) {
        super(config);
        if (effects == null) {
            this.effects = List.of();
        } else {
            // Copy and drop nulls so a host cannot later mutate the chain under the
            // renderer thread, and a missing link degrades to "no such effect" rather
            // than a per-frame NPE inside preSwap.
            this.effects = java.util.Arrays.stream(effects)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
    }

    /**
     * The ordered chain this canvas runs -- exposed so a host can read each link's
     * health (which effects latched themselves off) without inspecting GL.
     */
    public List<GpuPostEffect> getEffects() {
        return effects;
    }

    /**
     * Runs the chain against the current context. Under the patched java3d-core this
     * was called on the renderer thread, in its SWAP handler, between the glFinish and
     * the buffer swap, with the context current and the frame's pixels complete, so each
     * link composited into the very buffer about to be swapped. Against the STOCK jar the
     * build now resolves nothing calls it: {@code WorldViewer.preSwapHookPresent()} finds
     * no such method on Canvas3D, builds an empty chain and uses a plain Canvas3D, so this
     * class is never even constructed. It is kept -- unannotated, dormant -- for Phase 2's
     * JOGL GLEventListener to drive the same links.
     *
     * <p>It cannot throw into a host: each link latches itself off on any GL fault and
     * every later frame skips it here. A chain that cannot run degrades to the raw frame,
     * never a broken renderer.</p>
     */
    public void preSwap() {
        if (effects.isEmpty()) {
            return;
        }
        GLContext ctx = GLContext.getCurrent();
        if (ctx == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        for (GpuPostEffect effect : effects) {
            // A link that already faulted is skipped, so one refused effect never
            // costs the others their frame and never re-tries the same fault.
            if (!effect.isFailed()) {
                effect.render(ctx, w, h);
            }
        }
    }
}
