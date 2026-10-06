/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JComponent;

/**
 * The production {@link CompositedWindowHost}: for a redirected client window it
 * wires the shared pixel pipeline into a Swing surface the 2D desktop can embed.
 *
 * <p>Per window it builds a {@link CompositeWindowImageLoader} (the
 * {@link WindowPixelSource} reading the Composite {@code NameWindowPixmap}), a
 * {@link SwingCompositedWindowSink} (the 2D {@link CompositedWindowSink}) and a
 * {@link CompositedWindowPipeline} joining them, registers the pipeline as the
 * window's {@link X11Compositor.DamageListener} so repaints flow into the canvas,
 * and primes it with one full-window read. {@code dispose()} unregisters the
 * listener and tears down the damage/pixmap tracking.</p>
 *
 * <p>This class handles the <em>display</em> half only and holds no Java 3D and
 * no {@link X11Client} reference. <em>Input</em> forwarding is attached separately
 * by whoever owns the {@link X11Client} — {@code new SwingX11InputForwarder(
 * compositor, client, hosted.getComponent())} — keeping this host decoupled from
 * the window-manager's client objects.</p>
 *
 * @see CompositedWindowHost
 * @see CompositedWindowPipeline
 */
public final class X11CompositedWindowHost implements CompositedWindowHost {

    private static final Logger logger =
        Logger.getLogger("lg.x11.compositor.host2d");

    private final X11Compositor compositor;

    /**
     * @param compositor the active compositor tracking the redirected windows
     * @throws IllegalArgumentException if {@code compositor} is null
     */
    public X11CompositedWindowHost(X11Compositor compositor) {
        if (compositor == null) {
            throw new IllegalArgumentException("compositor must be non-null");
        }
        this.compositor = compositor;
    }

    @Override
    public HostedWindow open(int windowId, String title, int width, int height) {
        CompositeWindowImageLoader source =
            new CompositeWindowImageLoader(compositor, windowId);
        SwingCompositedWindowSink sink =
            new SwingCompositedWindowSink(width, height);
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink);
        compositor.addDamageListener(windowId, pipeline);
        // Prime the canvas with a full-window read; if the pixmap is not yet
        // redirected the read returns null and the first damage event fills it.
        pipeline.damageReported(windowId, 0, 0,
            Math.max(1, width), Math.max(1, height));
        logger.log(Level.FINE, "Hosting composited window 0x{0} (''{1}'') {2}x{3}",
            new Object[] { Integer.toHexString(windowId), title, width, height });
        return new Hosted(windowId, sink, pipeline);
    }

    /** The live handle returned by {@link #open}. */
    private final class Hosted implements HostedWindow {

        private final int windowId;
        private final SwingCompositedWindowSink sink;
        private final CompositedWindowPipeline pipeline;
        private volatile boolean disposed;

        Hosted(int windowId, SwingCompositedWindowSink sink,
                CompositedWindowPipeline pipeline) {
            this.windowId = windowId;
            this.sink = sink;
            this.pipeline = pipeline;
        }

        @Override
        public JComponent getComponent() {
            return sink.getComponent();
        }

        @Override
        public void resized(int width, int height) {
            if (disposed || width <= 0 || height <= 0) {
                return;
            }
            // The old pixmap is invalidated on resize; re-issue NameWindowPixmap,
            // grow the canvas, then re-read the whole window.
            compositor.refreshPixmapForWindow(windowId);
            pipeline.resized(width, height);
            pipeline.damageReported(windowId, 0, 0, width, height);
        }

        @Override
        public void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            compositor.removeDamageListener(windowId);
            compositor.teardownDamageForWindow(windowId);
            pipeline.dispose();
            logger.log(Level.FINE, "Released composited window 0x{0}",
                Integer.toHexString(windowId));
        }
    }
}
