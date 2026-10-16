/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: the Canvas3D that carries the GPU bloom.
 *
 * GpuBloomPass is the pass; BloomCanvas is the seam that lets Java 3D run it. It
 * exists because the pass has to fire at one exact moment -- after this frame's
 * rendering is synchronized and the context made current, immediately before the
 * buffer swap that presents it -- and the only hook at that moment is the
 * overridable Canvas3D.preSwap() a two-file source patch adds to Java 3D 1.7.2
 * (lg3d-core/src/patches/java3d-core-preswap). The public postRender() hook
 * cannot serve: it runs earlier in the render pass, before the sync, where the
 * default framebuffer still reads black and driving GL is pathological (~1
 * s/frame) -- WlProbe54 measured exactly that, which is why the patch and so this
 * class exist.
 *
 * The override is deliberately thin. It reads the current context Java 3D already
 * made current on the renderer thread and hands it, with the canvas size, to the
 * pass -- which owns the whole GL chain, the state bracket and the failure latch.
 * A @Override that will not compile against a stock java3d-core is the point: the
 * build only resolves the patched jar (see the root build.gradle's
 * buildPatchedJava3dCore), so this class compiling at all is evidence the hook the
 * probes measured is the one shipping. At runtime against a stock jar the override
 * would simply never be called and the world would render un-bloomed -- never a
 * crash, because preSwap() is public and a subclass may always declare it.
 *
 * Not constructed in CI: a Canvas3D needs a display and the native GL stack, so
 * this is probe-verified (WlProbe55 measured 71 fps and read the visible halo off
 * the swapped buffer), exactly like the rest of this module's live window.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.GraphicsConfiguration;

import com.jogamp.opengl.GLContext;

import org.jdesktop.wonderland.renderer.post.GpuBloomPass;
import org.jogamp.java3d.Canvas3D;

/**
 * A {@link Canvas3D} that blooms every presented frame in GL, at the patched
 * {@code preSwap()} hook. {@link WorldViewer} builds one in place of a plain
 * canvas when {@code PostProcessPlan} decides the GPU_POSTPROCESS route.
 */
public final class BloomCanvas extends Canvas3D {

    private final GpuBloomPass bloom;

    /**
     * @param config the graphics configuration, normally
     *               {@code SimpleUniverse.getPreferredConfiguration()}
     * @param bloom  the pass to run each frame, or {@code null} for a default one
     */
    public BloomCanvas(GraphicsConfiguration config, GpuBloomPass bloom) {
        super(config);
        this.bloom = bloom == null ? new GpuBloomPass() : bloom;
    }

    /** The pass this canvas blooms with -- exposed so a host can read its health. */
    public GpuBloomPass getBloomPass() {
        return bloom;
    }

    /**
     * Java 3D calls this on the renderer thread, in its SWAP handler, between the
     * glFinish and the buffer swap, with the context current and the frame's
     * pixels complete. The pass composites the glow into the very buffer about to
     * be swapped, so the bloom is present in the presented frame.
     *
     * <p>The base {@code preSwap()} is a no-op the patch documents as
     * "applications should not call", so this override does not call super. It
     * also cannot throw into Java 3D: the pass latches itself off on any GL fault
     * and every later frame short-circuits here, and the patched renderer guards
     * the call anyway (Issue 264). A bloom that cannot run degrades to an
     * un-bloomed frame, never a broken renderer.</p>
     */
    @Override
    public void preSwap() {
        if (bloom.isFailed()) {
            return;
        }
        GLContext ctx = GLContext.getCurrent();
        if (ctx == null) {
            return;
        }
        bloom.render(ctx, getWidth(), getHeight());
    }
}
