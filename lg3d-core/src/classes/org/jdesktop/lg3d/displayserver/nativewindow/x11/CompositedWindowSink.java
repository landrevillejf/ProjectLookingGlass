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

/**
 * The presentation end of the compositor's shared pixel pipeline: something that
 * shows a freshly-read region of a redirected client window.
 *
 * <p>Two sinks consume the one {@link WindowPixelSource} readback:</p>
 * <ul>
 *   <li>the <b>3D</b> sink, which uploads the pixels into a
 *       {@code NativeWindow3D} texture (the existing
 *       {@link CompositeWindowImageLoader#updateRegion} tile path);</li>
 *   <li>the <b>2D</b> sink, which paints the pixels into a Swing component
 *       hosted inside the 2D desktop, so a native X11 application appears
 *       <em>inside</em> a {@code Desktop2DWindow} rather than escaping as a
 *       separate top-level window.</li>
 * </ul>
 *
 * <p>A sink never performs X round-trips itself; {@link CompositedWindowPipeline}
 * reads the pixels on the window-manager event thread and hands the decoded
 * region here. Implementations that must marshal to another thread (e.g. the
 * Swing EDT) do so inside {@link #present}.</p>
 *
 * @see WindowPixelSource
 * @see CompositedWindowPipeline
 */
public interface CompositedWindowSink {

    /**
     * Presents a freshly-read region of the window.
     *
     * @param region the decoded pixels, exactly {@code width}&times;{@code height}
     * @param x      region left within the window, in pixels
     * @param y      region top within the window, in pixels
     * @param width  region width, in pixels
     * @param height region height, in pixels
     */
    void present(BufferedImage region, int x, int y, int width, int height);

    /**
     * Notifies the sink that the window's full size changed, so it can resize
     * its backing surface before the next {@link #present}. The default is a
     * no-op for sinks that infer size from each presented region.
     *
     * @param width  the new window width, in pixels
     * @param height the new window height, in pixels
     */
    default void resized(int width, int height) {
        // no-op by default
    }

    /**
     * Releases any resources held by the sink (e.g. removes a Swing component or
     * frees a texture). Safe to call once when the window is unmapped or the
     * compositor shuts down. The default is a no-op.
     */
    default void dispose() {
        // no-op by default
    }
}
