/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JComponent;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.CompositedWindowHost;

/**
 * Hosts composited native X11 windows <em>inside</em> the conventional 2D
 * desktop: when lg3d runs as the X11 window manager + compositor, each redirected
 * client window is given a {@link Desktop2DWindow} whose content is the Swing
 * surface painted by a {@link CompositedWindowHost}, so a real external
 * application (a browser, a terminal, ...) appears as an ordinary MDI window in
 * the 2D desktop rather than escaping as a separate top-level window owned by the
 * host window manager.
 *
 * <p>This controller is the desktop-side bookkeeping only — it owns the
 * {@code windowId -> Desktop2DWindow} map and forwards the compositor's
 * map/resize/retitle/unmap notifications. It reaches the X server exclusively
 * through the {@link CompositedWindowHost} seam, which keeps this package free of
 * Java 3D and of {@code gnu.x11}, so the whole map/dispatch lifecycle is
 * unit-testable headlessly with a fake host and a fake opener.</p>
 *
 * <p>All methods are expected to run on the EDT (the compositor's window-manager
 * notifications are marshalled here by the desktop).</p>
 *
 * @see CompositedWindowHost
 * @see Desktop2DWindow
 */
public final class Desktop2DCompositorHost {

    private static final Logger logger =
        Logger.getLogger("lg.desktop2d.compositor");

    /**
     * Creates and shows the {@link Desktop2DWindow} that will embed a composited
     * surface. Separated so the controller can be tested without a live desktop
     * pane, and so the desktop controls placement/taskbar/session bookkeeping.
     */
    public interface WindowOpener {
        /**
         * @param title   the window title
         * @param content the composited Swing surface to embed
         * @return the created (and typically shown) desktop window
         */
        Desktop2DWindow open(String title, JComponent content);
    }

    /** The per-window pairing of the hosted surface and its desktop window. */
    private static final class Entry {
        final CompositedWindowHost.HostedWindow hosted;
        final Desktop2DWindow window;

        Entry(CompositedWindowHost.HostedWindow hosted, Desktop2DWindow window) {
            this.hosted = hosted;
            this.window = window;
        }
    }

    private final CompositedWindowHost host;
    private final WindowOpener opener;

    /** Insertion-ordered so {@link #disposeAll} tears down in map order. */
    private final Map<Integer, Entry> windows = new LinkedHashMap<>();

    /**
     * @param host   produces the composited Swing surface for a window
     * @param opener creates the {@link Desktop2DWindow} that embeds it
     * @throws IllegalArgumentException if either argument is null
     */
    public Desktop2DCompositorHost(CompositedWindowHost host, WindowOpener opener) {
        if (host == null || opener == null) {
            throw new IllegalArgumentException("host and opener must both be non-null");
        }
        this.host = host;
        this.opener = opener;
    }

    /** True if {@code windowId} is currently hosted in a desktop window. */
    public boolean isHosted(int windowId) {
        return windows.containsKey(windowId);
    }

    /** The number of composited windows currently hosted. */
    public int hostedCount() {
        return windows.size();
    }

    /**
     * Handles a client window becoming viewable: opens a hosted surface and a
     * desktop window for it. A re-map of an already-hosted window is folded into
     * a resize + retitle rather than a duplicate.
     *
     * @param windowId the X window id
     * @param title    the window title
     * @param width    the window width in pixels
     * @param height   the window height in pixels
     */
    public void windowMapped(int windowId, String title, int width, int height) {
        Entry existing = windows.get(windowId);
        if (existing != null) {
            existing.hosted.resized(width, height);
            if (title != null) {
                existing.window.setTitle(title);
            }
            return;
        }
        CompositedWindowHost.HostedWindow hosted =
            host.open(windowId, title, width, height);
        Desktop2DWindow window = opener.open(title, hosted.getComponent());
        windows.put(windowId, new Entry(hosted, window));
        logger.log(Level.FINE, "Hosted composited window 0x{0} (''{1}'')",
            new Object[] { Integer.toHexString(windowId), title });
    }

    /**
     * Handles a client window resize. No-op for a window that is not hosted.
     *
     * @param windowId the X window id
     * @param width    the new width in pixels
     * @param height   the new height in pixels
     */
    public void windowResized(int windowId, int width, int height) {
        Entry e = windows.get(windowId);
        if (e != null) {
            e.hosted.resized(width, height);
        }
    }

    /**
     * Handles a client window title change. No-op for a window that is not
     * hosted or when {@code title} is null.
     *
     * @param windowId the X window id
     * @param title    the new title
     */
    public void windowRetitled(int windowId, String title) {
        Entry e = windows.get(windowId);
        if (e != null && title != null) {
            e.window.setTitle(title);
        }
    }

    /**
     * Handles a client window becoming unviewable or being destroyed: disposes
     * its hosted surface and its desktop window and forgets it. No-op for a
     * window that is not hosted.
     *
     * @param windowId the X window id
     */
    public void windowUnmapped(int windowId) {
        Entry e = windows.remove(windowId);
        if (e == null) {
            return;
        }
        e.hosted.dispose();
        e.window.dispose();
        logger.log(Level.FINE, "Released composited window 0x{0}",
            Integer.toHexString(windowId));
    }

    /** Disposes every hosted window; used on desktop shutdown. */
    public void disposeAll() {
        for (Entry e : windows.values()) {
            e.hosted.dispose();
            e.window.dispose();
        }
        windows.clear();
    }
}
