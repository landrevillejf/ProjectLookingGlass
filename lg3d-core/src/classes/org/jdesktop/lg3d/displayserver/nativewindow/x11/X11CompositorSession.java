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
 * The discovery seam that lets the conventional 2D desktop find a live X11
 * compositor session running in the <em>same</em> JVM, so it can host native X11
 * clients as ordinary MDI windows (Phase G of the compositor roadmap).
 *
 * <p>When lg3d runs as the X session — the window manager plus the
 * Composite/Damage compositor on a bare Xorg — {@code X11IntegrationModule}
 * {@linkplain #publish publishes} the session here once redirection succeeds. The
 * 2D shell then reads {@link #current()} and, if a session is live, wires a
 * {@code CompositedWindowBridge} to it. In every other topology (dev mode, the
 * {@code *_nox} configs, or compositing disabled) nothing is published and
 * {@link #current()} returns null, so the desktop is unchanged.</p>
 *
 * <p>The published {@link Session} deliberately exposes <em>only</em> the
 * Java-3D-free interface types — a {@link CompositedWindowHost} and a
 * {@link WindowLifecycleRegistrar} — never the concrete {@link X11Compositor} or
 * the package-private {@code X11WindowManager}. So referencing this class from
 * the {@code desktop2d} package drags in neither the scene graph nor
 * {@code gnu.x11}; the concrete host is built here, on the X11 side, at publish
 * time.</p>
 *
 * @see CompositedWindowHost
 * @see WindowLifecycleRegistrar
 */
public final class X11CompositorSession {

    /** The live session handle the 2D desktop consumes. */
    public interface Session {

        /**
         * Produces the Swing surface for a redirected native client window. Never
         * null on a published session.
         */
        CompositedWindowHost host();

        /**
         * Installs/clears the window-manager lifecycle listener. Never null on a
         * published session.
         */
        WindowLifecycleRegistrar registrar();
    }

    private static final class SessionImpl implements Session {
        private final CompositedWindowHost host;
        private final WindowLifecycleRegistrar registrar;

        SessionImpl(CompositedWindowHost host, WindowLifecycleRegistrar registrar) {
            this.host = host;
            this.registrar = registrar;
        }

        @Override
        public CompositedWindowHost host() {
            return host;
        }

        @Override
        public WindowLifecycleRegistrar registrar() {
            return registrar;
        }
    }

    private static volatile Session current;

    private X11CompositorSession() {
        // no instances
    }

    /**
     * Publishes the live session, building the production
     * {@link X11CompositedWindowHost} over the active compositor. A null argument
     * is treated as "no session" and {@linkplain #clear clears} it, so a failed
     * bring-up cannot leave a half-published session behind.
     *
     * @param compositor the active compositor (its display is already claimed)
     * @param registrar  the window manager that fires lifecycle notifications
     */
    public static void publish(X11Compositor compositor,
            WindowLifecycleRegistrar registrar) {
        if (compositor == null || registrar == null) {
            clear();
            return;
        }
        current = new SessionImpl(new X11CompositedWindowHost(compositor), registrar);
    }

    /**
     * Publishes a session from an already-built host. Package-visible seam used by
     * tests (which cannot construct a live {@link X11Compositor}); a null argument
     * clears the session.
     *
     * @param host      the composited-window host
     * @param registrar the window-manager lifecycle registrar
     */
    static void publish(CompositedWindowHost host, WindowLifecycleRegistrar registrar) {
        if (host == null || registrar == null) {
            clear();
            return;
        }
        current = new SessionImpl(host, registrar);
    }

    /** The live session, or null if lg3d is not running as an X compositor. */
    public static Session current() {
        return current;
    }

    /** True if a live session is published. */
    public static boolean isLive() {
        return current != null;
    }

    /** Drops any published session (called on compositor shutdown). */
    public static void clear() {
        current = null;
    }
}
