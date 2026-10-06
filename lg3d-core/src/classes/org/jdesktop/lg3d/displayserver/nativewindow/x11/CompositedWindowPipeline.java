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

import java.awt.image.BufferedImage;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wires one {@link WindowPixelSource} to one {@link CompositedWindowSink}: on a
 * damage report it reads the damaged region and presents it to the sink.
 *
 * <p>This is the presentation-agnostic spine of the compositor. It implements
 * {@link X11Compositor.DamageListener} so it can be registered directly with
 * {@link X11Compositor#addDamageListener(int, X11Compositor.DamageListener)} for
 * a window, and it holds no {@code gnu.x11.Display} and no Java 3D reference, so
 * the whole dispatch/clamping/lifecycle decision is unit-testable headlessly with
 * a fake source and a fake sink. The 3D desktop registers a texture sink; the 2D
 * desktop registers a Swing sink — the same pipeline drives both.</p>
 *
 * <h3>Threading</h3>
 * <p>{@link #damageReported} runs on the window-manager event thread (the thread
 * the {@link X11Compositor} dispatches on), which is the thread that may safely
 * perform the source's synchronous X read. A sink that must update a Swing
 * component marshals to the EDT itself inside
 * {@link CompositedWindowSink#present}.</p>
 *
 * @see WindowPixelSource
 * @see CompositedWindowSink
 */
public final class CompositedWindowPipeline implements X11Compositor.DamageListener {

    private static final Logger logger =
        Logger.getLogger("lg.x11.compositor.pipeline");

    private final WindowPixelSource source;
    private final CompositedWindowSink sink;

    /**
     * Creates a pipeline bound to one window's source and sink. The
     * {@code windowId} carried by each damage event is not re-checked here: a
     * pipeline is registered per window, and its source is already bound to that
     * window.
     *
     * @param source reads the window's redirected pixels; never null
     * @param sink   presents them; never null
     * @throws IllegalArgumentException if either argument is null
     */
    public CompositedWindowPipeline(WindowPixelSource source,
            CompositedWindowSink sink) {
        if (source == null || sink == null) {
            throw new IllegalArgumentException(
                "source and sink must both be non-null");
        }
        this.source = source;
        this.sink = sink;
    }

    /**
     * Reads the damaged region and presents it. Negative origins are clamped to
     * zero (matching the tile loader's behaviour); an empty region or a failed
     * read is a silent no-op, since the next damage event retries.
     */
    @Override
    public void damageReported(int windowId, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int srcX = Math.max(0, x);
        int srcY = Math.max(0, y);
        BufferedImage region = source.readRegion(srcX, srcY, width, height);
        if (region == null) {
            logger.log(Level.FINE,
                "No pixels for window 0x{0} region [{1},{2},{3},{4}]",
                new Object[] { Integer.toHexString(windowId), srcX, srcY,
                    width, height });
            return;
        }
        sink.present(region, srcX, srcY, width, height);
    }

    /** Forwards a window resize to the sink so it can reallocate its surface. */
    public void resized(int width, int height) {
        sink.resized(width, height);
    }

    /** Disposes the sink; the pipeline holds no other resources. */
    public void dispose() {
        sink.dispose();
    }
}
