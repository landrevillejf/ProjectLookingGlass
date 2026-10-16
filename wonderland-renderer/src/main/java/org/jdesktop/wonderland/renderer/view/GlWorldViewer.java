/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.4c: the JOGL twin of WorldViewer -- an AWT window that
 * hosts a GLCanvas running a GlView, and reads one presented frame back on
 * demand.
 *
 * WorldViewer drove a java3d Canvas3D whose framebuffer could only be reached in
 * postRender/postSwap, which is why the whole Gpu*Pass chain needed a source patch
 * to a preSwap() hook stock java3d does not have. This window needs no patch: a
 * GLCanvas's GLEventListener.display() runs with the context current and the scene
 * already ours, so GlView draws the GlScene and runs the live chain over the
 * presented frame, and capture() asks GlView for exactly those pixels (see
 * GlView.requestCapture / awaitCapture -- the on-demand handshake honoured at the
 * end of display()).
 *
 * It is deliberately the narrow slice of WorldViewer the desktop and the probe
 * need right now: show a world in a window, read a frame, close. Navigation, the
 * follow rig, spatial audio and the trigger dispatch stay on the java3d window
 * until the cutover proves this path at parity; nothing here replaces them yet.
 *
 * Not constructed in CI: a GLCanvas needs a display and the native GL stack, so
 * this class is probe/host-only like WorldViewer -- a headless test can hold a
 * GlScene and a Camera (both pure) but must never build the window or drive the
 * listener callbacks.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.FPSAnimator;

import org.jdesktop.wonderland.renderer.gl.Camera;
import org.jdesktop.wonderland.renderer.gl.GlScene;
import org.jdesktop.wonderland.renderer.gl.GlView;

/**
 * A live JOGL window onto one {@link GlScene} seen through one {@link Camera},
 * rendered by a {@link GlView} with the live post-process chain. AutoCloseable so
 * a host can show it in a try-with-resources; {@link #close()} is idempotent and
 * safe from any thread.
 */
public final class GlWorldViewer implements AutoCloseable {

    /** Frames per second the canvas animates at while the window is showing. */
    private static final int FPS = 60;

    private final GlView view;
    private final Frame frame;
    private final GLCanvas canvas;
    private final FPSAnimator animator;

    /**
     * Build the window but do not show it; {@link #show()} puts it on screen and
     * starts the animator. Constructs a real AWT/GL surface, so it is probe/host
     * only and never called in a headless test.
     *
     * @param title  the window title, normally the world's name
     * @param scene  the meshes and models to draw (not null)
     * @param camera the view/projection the scene is drawn through (not null)
     * @param width  the canvas width in pixels
     * @param height the canvas height in pixels
     */
    public GlWorldViewer(String title, GlScene scene, Camera camera,
            int width, int height) {
        this.view = new GlView(scene, camera);
        this.canvas = view.createCanvas();
        this.animator = new FPSAnimator(canvas, FPS);
        this.frame = new Frame(title == null ? "Wonderland" : title);
        frame.setLayout(new BorderLayout());
        frame.add(canvas, BorderLayout.CENTER);
        frame.setSize(Math.max(1, width), Math.max(1, height));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });
    }

    /**
     * Show the window and start rendering. Runs on the EDT and waits, so a caller
     * that captures immediately afterwards sees a showing frame -- the same
     * guarantee {@code WorldRender.runOnEdt(viewer::show)} gives the java3d path.
     */
    public void show() {
        runOnEdt(() -> {
            frame.setVisible(true);
            animator.start();
        });
    }

    /** Whether the world is on screen. */
    public boolean isShowing() {
        return frame.isShowing();
    }

    /** The window's canvas, for a caller that wants to composite it elsewhere. */
    public GLCanvas getCanvas() {
        return canvas;
    }

    /** The view this window renders with, chain and all. Never null. */
    public GlView getView() {
        return view;
    }

    /**
     * Read the current presented frame back as an image. Blocks up to
     * {@code timeoutMillis} for the animator to fulfil the read on the GL thread;
     * call off the EDT.
     *
     * @param timeoutMillis how long to wait for the render thread
     * @return the pixels, or {@code null} when the window is not live or the read
     *         did not answer in time -- a missing snapshot is never a failure
     */
    public BufferedImage capture(long timeoutMillis) {
        if (!frame.isShowing()) {
            return null;
        }
        view.requestCapture();
        return view.awaitCapture(timeoutMillis);
    }

    /**
     * Tear the window down: stop the animator, then dispose the frame. Safe to
     * call twice and from any thread; the AWT calls hop to the EDT. GlView.dispose
     * releases the scene's GL resources when the canvas's context goes away.
     */
    @Override
    public void close() {
        EventQueue.invokeLater(() -> {
            if (animator.isAnimating()) {
                animator.stop();
            }
            frame.dispose();
        });
    }

    /** Run on the EDT and wait; a bare run when already there. */
    private static void runOnEdt(Runnable action) {
        if (EventQueue.isDispatchThread()) {
            action.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(action);
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("the EDT did not run the action", thrown);
        } catch (InterruptedException thrown) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the EDT did not run the action", thrown);
        }
    }
}
