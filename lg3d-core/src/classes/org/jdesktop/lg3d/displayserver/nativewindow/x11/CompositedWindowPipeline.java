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
    private final FramePacer pacer;
    private final DamageAccumulator damage = new DamageAccumulator();

    /**
     * Creates a pipeline bound to one window's source and sink, presenting each
     * damage report synchronously (an always-due {@link FramePacer}). The
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
        this(source, sink, new FramePacer(0L));
    }

    /**
     * Creates a pipeline that coalesces damage and paces presents. Damage
     * reports accumulate into a single bounding region
     * ({@link DamageAccumulator}); a present happens only when {@code pacer}
     * says one is due, otherwise the damage waits for the next due report or an
     * explicit {@link #flush(int)}. An always-due pacer (interval 0) makes this
     * behave exactly like the two-argument constructor.
     *
     * @param source reads the window's redirected pixels; never null
     * @param sink   presents them; never null
     * @param pacer  throttles presents; null defaults to always-due
     * @throws IllegalArgumentException if source or sink is null
     */
    public CompositedWindowPipeline(WindowPixelSource source,
            CompositedWindowSink sink, FramePacer pacer) {
        if (source == null || sink == null) {
            throw new IllegalArgumentException(
                "source and sink must both be non-null");
        }
        this.source = source;
        this.sink = sink;
        this.pacer = (pacer != null) ? pacer : new FramePacer(0L);
    }

    /**
     * Accumulates the damaged region and, when a present is due, reads the
     * coalesced bounding region once and presents it. Negative origins are
     * clamped to zero and empty regions ignored (matching the tile loader's
     * behaviour); a failed read is a silent no-op, since the next damage event
     * retries. With the default always-due pacer this presents each damage
     * report immediately, exactly as before coalescing was added.
     */
    @Override
    public void damageReported(int windowId, int x, int y, int width, int height) {
        damage.add(x, y, width, height);
        if (pacer.tryAcquire(System.currentTimeMillis())) {
            presentAccumulated(windowId);
        }
    }

    /**
     * Presents any damage accumulated since the last present, ignoring pacing.
     * A live frame loop calls this once per rendered frame so paced-out damage is
     * not dropped; it is a no-op when nothing is pending.
     *
     * @param windowId the X window id (for diagnostics only)
     */
    public void flush(int windowId) {
        if (!damage.isEmpty()) {
            pacer.markPresented(System.currentTimeMillis());
            presentAccumulated(windowId);
        }
    }

    /** True if damage has been accumulated but not yet presented (paced out). */
    public boolean hasPendingDamage() {
        return !damage.isEmpty();
    }

    /** Drains the accumulated region, reads it once and presents it. */
    private void presentAccumulated(int windowId) {
        DamageAccumulator.Region r = damage.drain();
        if (r == null) {
            return;
        }
        BufferedImage region = source.readRegion(r.x(), r.y(), r.width(), r.height());
        if (region == null) {
            logger.log(Level.FINE,
                "No pixels for window 0x{0} region [{1},{2},{3},{4}]",
                new Object[] { Integer.toHexString(windowId), r.x(), r.y(),
                    r.width(), r.height() });
            return;
        }
        sink.present(region, r.x(), r.y(), r.width(), r.height());
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
