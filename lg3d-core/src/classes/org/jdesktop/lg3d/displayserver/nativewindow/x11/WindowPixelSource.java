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
 * The source end of the compositor's shared pixel pipeline: something that can
 * read a rectangular region of a redirected client window's pixels into a
 * {@link BufferedImage}.
 *
 * <p>Introduced so the <em>one</em> readback path (a Composite
 * {@code NameWindowPixmap} decoded by
 * {@link CompositeWindowImageLoader#decodeZPixmap}) can feed <em>two</em>
 * independent presentation sinks — the 3D {@code NativeWindow3D} texture and the
 * 2D Swing host — without either sink knowing how the pixels were obtained. The
 * production implementation is {@link CompositeWindowImageLoader}; tests supply a
 * fake so {@link CompositedWindowPipeline} can be exercised headlessly, with no
 * live {@code gnu.x11.Display} and no Java 3D.</p>
 *
 * @see CompositedWindowSink
 * @see CompositedWindowPipeline
 * @see CompositeWindowImageLoader
 */
public interface WindowPixelSource {

    /**
     * Reads a rectangular region of the window's pixels.
     *
     * @param x      region left, in window pixels
     * @param y      region top, in window pixels
     * @param width  region width, in pixels
     * @param height region height, in pixels
     * @return the decoded region, or {@code null} if the window is not (yet)
     *         redirected, the geometry is empty, or the read failed (the next
     *         damage event retries)
     */
    BufferedImage readRegion(int x, int y, int width, int height);
}
