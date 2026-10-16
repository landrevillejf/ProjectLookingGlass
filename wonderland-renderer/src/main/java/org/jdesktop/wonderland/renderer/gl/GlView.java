/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.3: the JOGL view that replaces java3d's WorldViewer as
 * the live renderer -- the per-frame seam java3d never had.
 *
 * WorldViewer drove a java3d Canvas3D and could only reach the framebuffer in
 * postRender/postSwap, both AFTER the swap, which is why the whole Gpu*Pass
 * chain needed a source patch to a preSwap() hook stock java3d does not have. A
 * JOGL GLCanvas needs no patch: GLEventListener.display() runs with the context
 * current and the scene already ours to draw, so the view renders GlScene into
 * the default framebuffer and then runs the passes over it in order -- the same
 * passes, unchanged, because each GpuPostEffect.render(ctx,w,h) already captures
 * whatever is in the drawable's target framebuffer, processes it and composites
 * back. SSAO -> bloom -> HDR -> DOF -> FXAA, each gated by a WorldFeatures kill
 * switch and each skipped once it has latched failed.
 *
 * The chain ASSEMBLY is pure and headless-tested (buildChain); the GL runtime
 * (init/display/reshape/dispose/capturePreview) is probe-only, because a
 * GLContext cannot be constructed in a headless test JVM -- the repo-wide rule
 * for every Jogamp/JOGL object. P2.5's probe measures this view rendering all
 * eight worlds with effects ON.
 */
package org.jdesktop.wonderland.renderer.gl;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;

import org.jdesktop.wonderland.common.features.WorldFeatures;
import org.jdesktop.wonderland.renderer.post.GpuBloomPass;
import org.jdesktop.wonderland.renderer.post.GpuDofPass;
import org.jdesktop.wonderland.renderer.post.GpuFxaaPass;
import org.jdesktop.wonderland.renderer.post.GpuHdrPass;
import org.jdesktop.wonderland.renderer.post.GpuPostEffect;
import org.jdesktop.wonderland.renderer.post.GpuSsaoPass;

/**
 * A JOGL {@link GLEventListener} that draws a {@link GlScene} through a
 * {@link Camera} and runs the {@link GpuPostEffect} chain over the presented
 * frame. Not thread-safe: it is driven from the single GL render thread of the
 * {@link GLCanvas} {@link #createCanvas()} hands back.
 */
public final class GlView implements GLEventListener {

    /** Clear colour for the framebuffer before the scene draws. */
    private static final float CLEAR_R = 0.05f;
    private static final float CLEAR_G = 0.06f;
    private static final float CLEAR_B = 0.08f;

    private final GlScene scene;
    private final Camera camera;
    private final List<GpuPostEffect> passes;
    private int width;
    private int height;

    /**
     * The on-demand capture handshake (P2.4c). A caller on the EDT or a worker
     * thread asks {@link #requestCapture()} for the next presented frame and then
     * blocks in {@link #awaitCapture}; the single GL render thread fulfils it at
     * the end of {@link #display()}, once the post-process chain has composited
     * back into the default framebuffer, so the pixels handed back are the very
     * ones the eye was shown -- the JOGL twin of java3d's postSwap raster read,
     * but reachable without a source patch. These four fields are the one piece of
     * shared state crossing the two threads, so every read and write of them holds
     * {@code captureLock}; the rest of the view stays single-threaded as before.
     */
    private final Object captureLock = new Object();
    /** A capture has been asked for and the GL thread has not answered it yet. */
    private boolean captureWanted;
    /** The GL thread has a fresh answer (possibly null) to hand back. */
    private boolean captureReady;
    /** The captured frame, or null when the readback could not produce one. */
    private BufferedImage captureFrame;

    /**
     * A view over {@code scene} from {@code camera}, running the chain the
     * {@link WorldFeatures} kill switches select.
     *
     * @param scene  the meshes and models to draw (not null)
     * @param camera the view/projection the scene is drawn through (not null)
     */
    public GlView(GlScene scene, Camera camera) {
        this(scene, camera, defaultChain());
    }

    /**
     * A view over {@code scene} from {@code camera}, running exactly {@code passes}
     * in order. Pure: constructing a view touches no GL, so it is safe to build one
     * long before a canvas exists (and safe to build one in a headless test, which
     * may inspect the chain but must never drive the listener callbacks).
     *
     * @param scene  the meshes and models to draw (not null)
     * @param camera the view/projection the scene is drawn through (not null)
     * @param passes the post-process chain, in order; null or empty for none
     */
    public GlView(GlScene scene, Camera camera, List<GpuPostEffect> passes) {
        if (scene == null) {
            throw new IllegalArgumentException("scene == null");
        }
        if (camera == null) {
            throw new IllegalArgumentException("camera == null");
        }
        this.scene = scene;
        this.camera = camera;
        this.passes = passes == null
                ? new ArrayList<>()
                : new ArrayList<>(passes);
    }

    /**
     * The chain the live desktop runs, read from the {@link WorldFeatures} kill
     * switches. Thin wrapper over the pure {@link #buildChain} so the ordering and
     * gating are asserted directly instead of through process-wide property reads.
     */
    public static List<GpuPostEffect> defaultChain() {
        return buildChain(
                WorldFeatures.enabled(WorldFeatures.SSAO),
                WorldFeatures.enabled(WorldFeatures.BLOOM),
                WorldFeatures.enabled(WorldFeatures.HDR),
                WorldFeatures.enabled(WorldFeatures.DOF),
                WorldFeatures.enabled(WorldFeatures.FXAA));
    }

    /**
     * Assemble the post-process chain in the one order the effects compose
     * correctly: occlusion first (it darkens the lit surface), then bloom (it
     * picks up what the occlusion left bright), then the HDR tone map (it folds
     * radiance into display range), then depth of field (it defocuses the tonemapped
     * image), then FXAA last (it softens the edges of the finished frame). Each link
     * is included only when its feature is on.
     *
     * @param ssao  include {@link GpuSsaoPass}
     * @param bloom include {@link GpuBloomPass}
     * @param hdr   include {@link GpuHdrPass}
     * @param dof   include {@link GpuDofPass}
     * @param fxaa  include {@link GpuFxaaPass}
     * @return the passes in composition order; never null, empty when all are off
     */
    public static List<GpuPostEffect> buildChain(
            boolean ssao, boolean bloom, boolean hdr, boolean dof, boolean fxaa) {
        List<GpuPostEffect> chain = new ArrayList<>();
        if (ssao) {
            chain.add(new GpuSsaoPass());
        }
        if (bloom) {
            chain.add(new GpuBloomPass());
        }
        if (hdr) {
            chain.add(new GpuHdrPass());
        }
        if (dof) {
            chain.add(new GpuDofPass());
        }
        if (fxaa) {
            chain.add(new GpuFxaaPass());
        }
        return chain;
    }

    /** The post-process chain this view runs, in order. Never null. */
    public List<GpuPostEffect> passes() {
        return Collections.unmodifiableList(passes);
    }

    /** The scene this view draws. Never null. */
    public GlScene scene() {
        return scene;
    }

    /** The camera this view draws through. Never null. */
    public Camera camera() {
        return camera;
    }

    /** The last width {@link #reshape} saw, or 0 before the first reshape. */
    public int width() {
        return width;
    }

    /** The last height {@link #reshape} saw, or 0 before the first reshape. */
    public int height() {
        return height;
    }

    /**
     * Build the AWT {@link GLCanvas} that hosts this view: a double-buffered,
     * 24-bit-depth GL2-compatibility surface (compatibility so the passes'
     * {@code getGL2()} and this view's {@code getGL3()} both resolve on one
     * context) with this listener attached. Probe/host-only: it constructs a real
     * AWT/GL surface, so a headless test never calls it.
     */
    public GLCanvas createCanvas() {
        GLProfile profile = GLProfile.get(GLProfile.GL2);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(24);
        caps.setDoubleBuffered(true);
        GLCanvas canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        return canvas;
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        scene.init(gl);
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LEQUAL);
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glCullFace(GL.GL_BACK);
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL3 gl = drawable.getGL().getGL3();
        gl.glClearColor(CLEAR_R, CLEAR_G, CLEAR_B, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        scene.draw(gl, camera);
        // The per-frame seam: run the chain over the just-drawn default framebuffer.
        // Each pass captures the target framebuffer, processes and composites back,
        // so they compose in order; a pass that latched failed is skipped, never
        // retried into a second failure on the render thread.
        GLContext ctx = GLContext.getCurrent();
        for (GpuPostEffect pass : passes) {
            if (!pass.isFailed()) {
                pass.render(ctx, width, height);
            }
        }
        // Honour an on-demand capture now that the chain has finished: read back the
        // presented framebuffer and hand it to whoever is waiting. Probe/host-only --
        // it issues GL, so it runs on the render thread and never in a headless test.
        boolean wanted;
        synchronized (captureLock) {
            wanted = captureWanted;
        }
        if (wanted) {
            BufferedImage frame = capturePreview(drawable.getGL().getGL2(), width, height);
            synchronized (captureLock) {
                captureWanted = false;
                captureFrame = frame;
                captureReady = true;
                captureLock.notifyAll();
            }
        }
    }

    /**
     * Ask the GL thread to read back the next presented frame. Non-blocking: it
     * only records the request, and {@link #awaitCapture} collects the answer. A
     * second request before the first is fulfilled simply replaces it, so a slow
     * render thread never queues a backlog of reads. Safe from any thread; the
     * canvas's animator drives the {@link #display} that fulfils it.
     */
    public void requestCapture() {
        synchronized (captureLock) {
            captureWanted = true;
            captureReady = false;
            captureFrame = null;
        }
    }

    /**
     * Wait for the frame {@link #requestCapture()} asked the GL thread for.
     *
     * @param timeoutMillis how long to block; non-positive still waits one poll so
     *                      a frame that is already on its way is not dropped
     * @return the captured frame, or {@code null} when the render thread produced
     *         no frame in time (the window is not live, or nothing was presented) --
     *         a missing capture is never an exception, exactly as the java3d path
     */
    public BufferedImage awaitCapture(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (captureLock) {
            while (!captureReady) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return null;
                }
                try {
                    captureLock.wait(remaining);
                } catch (InterruptedException waited) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return captureFrame;
        }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int w, int h) {
        this.width = w;
        this.height = h;
        drawable.getGL().getGL3().glViewport(0, 0, w, h);
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GLContext ctx = GLContext.getCurrent();
        for (GpuPostEffect pass : passes) {
            pass.dispose(ctx);
        }
        scene.dispose(drawable.getGL().getGL3());
    }

    /**
     * Read the presented framebuffer back into a {@link BufferedImage}, flipping GL's
     * bottom-left origin to the image's top-left. The probe seam: a JOGL view reads
     * its own pixels with {@code glReadPixels} off the GPU rather than the java3d
     * offscreen-buffer round trip. Probe/host-only -- it issues GL, so it must be
     * called with {@code gl}'s context current and is never called in a headless test.
     *
     * @param gl the current context's GL, or {@code null} for no image
     * @param w  the width in pixels; non-positive yields no image
     * @param h  the height in pixels; non-positive yields no image
     * @return the captured frame, or {@code null} when the arguments cannot capture
     */
    public BufferedImage capturePreview(GL2 gl, int w, int h) {
        if (gl == null || w <= 0 || h <= 0) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
        gl.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buf);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            buf.position((h - 1 - y) * w * 4);
            for (int x = 0; x < w; x++) {
                int r = buf.get() & 0xFF;
                int g = buf.get() & 0xFF;
                int b = buf.get() & 0xFF;
                int a = buf.get() & 0xFF;
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }
}
