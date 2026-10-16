/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.3: the one contract every GPU post-process pass honours.
 *
 * P3.2 shipped a single pass (GpuBloomPass) driven from a single hook
 * (Canvas3D.preSwap). A high-dynamic-range frame is not one effect but a chain --
 * tone map, bloom, an anti-alias, a depth pass -- and each link is a cosmetic GL
 * pass on Java 3D's render thread that must never become a second failure on top of
 * the frame the user was owed. So the chain is expressed as this interface: run one
 * frame against the live context, tear down, and report whether a GL fault already
 * latched the pass off. GpuBloomPass satisfies it unchanged (its render/dispose/
 * isFailed signatures are exactly these); GpuHdrPass and the passes that follow
 * implement it, and BloomCanvas runs whatever links a host hands it, skipping any
 * that has failed, in order, inside one preSwap.
 *
 * The safety contract is the reason this is an interface and not a convention: a
 * link that throws into preSwap would take the renderer down, so every implementor
 * catches everything and latches itself off instead -- the same degrade-to-no-effect
 * rule GpuBloomPass documents, now owed by every pass in the chain.
 */
package org.jdesktop.wonderland.renderer.post;

import com.jogamp.opengl.GLContext;

/** One GPU post-process link, driven from {@code Canvas3D.preSwap()}. */
public interface GpuPostEffect {

    /**
     * Run one frame against the framebuffer Java 3D is about to swap. Called on the
     * renderer thread with the context current; must never throw.
     *
     * @param ctx    the live GL context, from {@code GLContext.getCurrent()}
     * @param width  the canvas width in pixels
     * @param height the canvas height in pixels
     */
    void render(GLContext ctx, int width, int height);

    /**
     * Free the pass's GL resources. Best-effort, never throws, idempotent, and safe
     * before any {@link #render}.
     *
     * @param ctx the live GL context, or {@code null} to skip
     */
    void dispose(GLContext ctx);

    /**
     * Whether a GL fault latched this pass off. Once true it stays true, so a driver
     * that refused the effect once is not asked again and the world renders without
     * it rather than risking a second failure per frame.
     */
    boolean isFailed();
}
