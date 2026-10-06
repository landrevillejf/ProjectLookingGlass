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

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * The <b>2D</b> {@link CompositedWindowSink}: paints a redirected client
 * window's pixels into a Swing component that the conventional 2D desktop can
 * host <em>inside</em> a {@code Desktop2DWindow}, so a native X11 application
 * appears within the desktop rather than escaping as a separate top-level
 * window.
 *
 * <p>It keeps a full-window {@link BufferedImage} canvas. Each
 * {@link #present} blits the freshly-read damage region into the canvas at its
 * window-relative offset and repaints only that rectangle; {@link #resized}
 * reallocates the canvas. The canvas is the single source the component paints,
 * so the class needs no live {@code gnu.x11.Display} and no Java 3D and is fully
 * unit-testable headlessly (the blit is a plain {@code Graphics2D} memory copy
 * and a {@code JPanel} constructs under {@code java.awt.headless=true}).</p>
 *
 * <h3>Threading</h3>
 * <p>{@link #present} runs on the window-manager event thread. The blit is
 * performed under a lock shared with {@link CanvasPanel#paintComponent} so the
 * canvas is never painted mid-copy; only the cheap {@code repaint()} is marshalled
 * to the EDT (Swing's {@code repaint()} is itself thread-safe, but the dirty
 * rectangle is queued explicitly for clarity).</p>
 *
 * @see CompositedWindowPipeline
 * @see WindowPixelSource
 */
public final class SwingCompositedWindowSink implements CompositedWindowSink {

    private static final Logger logger =
        Logger.getLogger("lg.x11.compositor.swing");

    /** Guards {@link #canvas} between the WM-thread blit and the EDT paint. */
    private final Object canvasLock = new Object();

    /** Full-window pixel canvas; reassigned on {@link #resized}. */
    private BufferedImage canvas;

    /** The Swing surface hosting the canvas, for embedding in a 2D window. */
    private final CanvasPanel component;

    /** True once {@link #dispose()} has run; later presents are ignored. */
    private volatile boolean disposed;

    /**
     * Creates a sink with a canvas of the given initial size.
     *
     * @param width  initial window width in pixels (clamped to &ge; 1)
     * @param height initial window height in pixels (clamped to &ge; 1)
     */
    public SwingCompositedWindowSink(int width, int height) {
        this.component = new CanvasPanel();
        this.canvas = newCanvas(width, height);
        this.component.setPreferredSize(new java.awt.Dimension(
            Math.max(1, width), Math.max(1, height)));
    }

    /** The Swing component to embed in a {@code Desktop2DWindow}. */
    public JComponent getComponent() {
        return component;
    }

    /**
     * Blits the damaged region into the canvas at its window offset and repaints
     * that rectangle. A null region, a disposed sink or a missing canvas is a
     * no-op.
     */
    @Override
    public void present(BufferedImage region, int x, int y, int width, int height) {
        if (disposed || region == null || width <= 0 || height <= 0) {
            return;
        }
        synchronized (canvasLock) {
            if (canvas == null) {
                return;
            }
            Graphics2D g = canvas.createGraphics();
            try {
                g.drawImage(region, x, y, null);
            } finally {
                g.dispose();
            }
        }
        // Repaint only the damaged rectangle; clip to the canvas bounds.
        final int rx = Math.max(0, x);
        final int ry = Math.max(0, y);
        final int rw = Math.min(width, canvas.getWidth() - rx);
        final int rh = Math.min(height, canvas.getHeight() - ry);
        if (rw > 0 && rh > 0) {
            component.repaint(rx, ry, rw, rh);
        }
    }

    /** Reallocates the canvas for a new window size, preserving the top-left. */
    @Override
    public void resized(int width, int height) {
        if (disposed || width <= 0 || height <= 0) {
            return;
        }
        synchronized (canvasLock) {
            BufferedImage next = newCanvas(width, height);
            if (canvas != null) {
                Graphics2D g = next.createGraphics();
                try {
                    g.drawImage(canvas, 0, 0, null);
                } finally {
                    g.dispose();
                }
            }
            canvas = next;
        }
        component.setPreferredSize(new java.awt.Dimension(width, height));
        component.revalidate();
        component.repaint();
    }

    /** Drops the canvas; the component paints nothing afterwards. */
    @Override
    public void dispose() {
        disposed = true;
        synchronized (canvasLock) {
            canvas = null;
        }
    }

    /**
     * Allocates an opaque canvas, clamping degenerate sizes to 1&times;1 so a
     * {@link BufferedImage} constructor never throws on a zero/negative edge.
     */
    private static BufferedImage newCanvas(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    /** Package-private canvas accessor for headless tests. */
    BufferedImage canvasSnapshot() {
        synchronized (canvasLock) {
            if (canvas == null) {
                return null;
            }
            BufferedImage copy = new BufferedImage(
                canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            try {
                g.drawImage(canvas, 0, 0, null);
            } finally {
                g.dispose();
            }
            return copy;
        }
    }

    /**
     * The painted surface: fills its background then draws the canvas under the
     * shared lock so a concurrent blit never tears.
     */
    private final class CanvasPanel extends JPanel {

        private static final long serialVersionUID = 1L;

        CanvasPanel() {
            setOpaque(true);
            setDoubleBuffered(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage snapshot;
            synchronized (canvasLock) {
                snapshot = canvas;
                if (snapshot == null) {
                    return;
                }
                if (g instanceof Graphics2D) {
                    ((Graphics2D) g).drawImage(snapshot, 0, 0, null);
                }
            }
            if (logger.isLoggable(Level.FINEST)) {
                logger.finest("painted composited canvas "
                    + snapshot.getWidth() + "x" + snapshot.getHeight());
            }
        }
    }
}
