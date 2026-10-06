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

/**
 * The window-manager lifecycle seam the conventional 2D desktop subscribes to so
 * that <em>native X11 applications appear as ordinary windows inside the 2D
 * desktop</em>.
 *
 * <p>{@link X11WindowManager} already drives the 3D texture path on
 * Map/Unmap/Configure/PropertyNotify. This listener is the parallel, presentation-
 * agnostic notification: whenever a redirected client window is mapped, resized,
 * retitled, activated or unmapped, the WM fires the matching callback so a
 * desktop-side controller can open/adjust/close the hosting window. The 2D shell
 * adapts it to {@code Desktop2DCompositorHost} (see {@code CompositedWindowBridge}),
 * which in turn obtains each window's Swing surface from a
 * {@link CompositedWindowHost}.</p>
 *
 * <p>Callbacks are invoked on the X event-dispatch thread. Implementations that
 * touch Swing must marshal to the EDT themselves (the 2D bridge does). The WM
 * isolates each callback in a try/catch, so a desktop-side failure is logged and
 * never kills the X event loop.</p>
 *
 * <p>Deliberately free of {@code gnu.x11} and Java 3D types — it speaks only in
 * window ids, titles and pixel sizes — so the desktop2d package can implement it
 * without dragging in the scene graph or the X protocol objects.</p>
 *
 * @see CompositedWindowHost
 */
public interface WindowLifecycleListener {

    /**
     * A redirected client window became viewable and should be hosted.
     *
     * @param windowId the X window id
     * @param title    the window's current title (may be null)
     * @param width    the window width in pixels
     * @param height   the window height in pixels
     */
    void windowMapped(int windowId, String title, int width, int height);

    /**
     * A hosted client window changed size.
     *
     * @param windowId the X window id
     * @param width    the new width in pixels
     * @param height   the new height in pixels
     */
    void windowResized(int windowId, int width, int height);

    /**
     * A hosted client window's title changed (WM_NAME property).
     *
     * @param windowId the X window id
     * @param title    the new title (may be null)
     */
    void windowRetitled(int windowId, String title);

    /**
     * A client window became the active/focused window.
     *
     * @param windowId the X window id
     */
    void windowActivated(int windowId);

    /**
     * A hosted client window became unviewable or was destroyed and should be
     * released.
     *
     * @param windowId the X window id
     */
    void windowUnmapped(int windowId);
}
