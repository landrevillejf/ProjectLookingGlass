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
 * The public registration seam for a {@link WindowLifecycleListener}.
 *
 * <p>{@code X11WindowManager} is package-private (it extends the Escher
 * {@code Application} and is an implementation detail of the native-X11 display
 * server), so the conventional 2D desktop cannot reference its concrete type.
 * This interface exposes exactly the one method the 2D shell needs — install (or,
 * with {@code null}, clear) the listener that routes native client lifecycle
 * events into the desktop — without leaking the window-manager class or any
 * {@code gnu.x11} / Java 3D type.</p>
 *
 * @see WindowLifecycleListener
 * @see X11CompositorSession
 */
public interface WindowLifecycleRegistrar {

    /**
     * Installs the listener that receives this window manager's native client
     * lifecycle notifications, or clears it when {@code listener} is null.
     *
     * @param listener the desktop-side listener, or null to unregister
     */
    void setWindowLifecycleListener(WindowLifecycleListener listener);
}
