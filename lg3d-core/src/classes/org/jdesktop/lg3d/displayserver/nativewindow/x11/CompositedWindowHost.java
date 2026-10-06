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

import javax.swing.JComponent;

/**
 * The seam the conventional 2D desktop uses to obtain a Swing surface for a
 * composited (redirected) native X11 window, so that window can be hosted
 * <em>inside</em> a {@code Desktop2DWindow} instead of escaping as a separate
 * top-level window owned by the host window manager.
 *
 * <p>Deliberately free of any Java 3D reference — it speaks only in terms of a
 * {@link JComponent} and a lifecycle — so the 2D shell package
 * ({@code org.jdesktop.lg3d.displayserver.desktop2d}) can depend on it without
 * dragging in the scene graph. The production implementation is
 * {@link X11CompositedWindowHost}, which wires a {@link WindowPixelSource}
 * ({@link CompositeWindowImageLoader}) to a {@link SwingCompositedWindowSink}
 * through a {@link CompositedWindowPipeline}. Tests supply a fake so the desktop
 * controller can be exercised headlessly.</p>
 *
 * @see X11CompositedWindowHost
 * @see SwingCompositedWindowSink
 */
public interface CompositedWindowHost {

    /**
     * Begins hosting a composited window: reads its redirected pixels and keeps
     * them painted into the returned component.
     *
     * @param windowId the X window id of the redirected client
     * @param title    a human-readable title (for the host window's chrome)
     * @param width    the window's current width in pixels
     * @param height   the window's current height in pixels
     * @return a live handle to the hosted surface; never null
     */
    HostedWindow open(int windowId, String title, int width, int height);

    /**
     * A live hosted surface: the Swing component to embed, plus the resize and
     * teardown hooks the desktop controller drives as the client window changes.
     */
    interface HostedWindow {

        /** The Swing component painting the client window's pixels. */
        JComponent getComponent();

        /**
         * Tells the host the client window changed size, so it can reallocate
         * its surface and re-read the full window.
         *
         * @param width  the new width in pixels
         * @param height the new height in pixels
         */
        void resized(int width, int height);

        /** Stops hosting: unregisters the damage listener and frees resources. */
        void dispose();
    }
}
