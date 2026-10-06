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

import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JDesktopPane;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.CompositedWindowHost;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.WindowLifecycleListener;

/**
 * The turnkey Phase G assembly that connects a live X11 compositor session to the
 * conventional 2D desktop, so <em>native X11 applications are hosted as ordinary
 * MDI windows inside the 2D desktop</em>.
 *
 * <p>It is the single place that knows how to build the whole object graph and
 * register it: a {@link Desktop2DCompositorHost} controller (over a
 * {@link CompositedWindowHost} that produces each window's Swing surface and a
 * {@link Desktop2DCompositorHost.WindowOpener} that creates the
 * {@link Desktop2DWindow}s), wrapped in a {@link CompositedWindowBridge} and
 * handed to a {@code WindowLifecycleRegistrar} so the window manager starts
 * forwarding map/resize/retitle/activate/unmap into it.</p>
 *
 * <p>Every collaborator is an interface or a plain Swing type, and the wiring is
 * driven through an explicit {@code Consumer<WindowLifecycleListener>} registrar,
 * so the whole assembly — including the decision to install at all — is
 * unit-testable headlessly with fakes and a real (unrealized) {@link JDesktopPane}.
 * The live glue that supplies the real host and registrar lives in
 * {@code Desktop2D}, which reads the published
 * {@code X11CompositorSession}.</p>
 *
 * @see CompositedWindowBridge
 * @see Desktop2DCompositorHost
 */
public final class CompositedDesktopWiring {

    /**
     * Opt-in system property that enables hosting native X11 clients inside the
     * 2D desktop. Off by default so every existing 2D/Swing topology (which has
     * no X compositor anyway) is unchanged.
     */
    public static final String OPT_IN_PROPERTY = "lg3d.x11.composite2d";

    private CompositedDesktopWiring() {
        // no instances
    }

    /**
     * The pure install decision: host native X11 windows in the 2D desktop only
     * when the operator opted in, a live compositor session was discovered, and
     * we are not headless (there is no desktop pane to host into).
     *
     * @param optIn       {@code lg3d.x11.composite2d} is set true
     * @param sessionLive a compositor session is published and active
     * @param headless    the JVM is headless
     * @return true if {@link #install} should be called
     */
    public static boolean shouldInstall(boolean optIn, boolean sessionLive,
            boolean headless) {
        return optIn && sessionLive && !headless;
    }

    /**
     * The default {@link Desktop2DCompositorHost.WindowOpener}: creates a plain
     * {@link Desktop2DWindow} for the composited surface and adds it to
     * {@code pane}. {@code Desktop2D} supplies a richer opener (taskbar,
     * workspace, cascading placement); this minimal one is what a standalone or
     * test harness uses.
     *
     * @param pane the MDI desktop pane to add windows to
     * @return an opener bound to {@code pane}
     * @throws IllegalArgumentException if {@code pane} is null
     */
    public static Desktop2DCompositorHost.WindowOpener desktopOpener(
            final JDesktopPane pane) {
        if (pane == null) {
            throw new IllegalArgumentException("pane must be non-null");
        }
        return new Desktop2DCompositorHost.WindowOpener() {
            @Override
            public Desktop2DWindow open(String title, JComponent content) {
                Desktop2DWindow window =
                    new Desktop2DWindow(title, null, content, title);
                pane.add(window);
                window.setVisible(true);
                return window;
            }
        };
    }

    /**
     * Assembles and registers the wiring.
     *
     * @param host      produces the Swing surface for each redirected client
     * @param opener    creates the {@link Desktop2DWindow} that embeds it
     * @param registrar installs the bridge as the WM lifecycle listener
     * @return a live handle used to {@linkplain Handle#dispose tear down}
     * @throws IllegalArgumentException if any argument is null
     */
    public static Handle install(CompositedWindowHost host,
            Desktop2DCompositorHost.WindowOpener opener,
            Consumer<WindowLifecycleListener> registrar) {
        if (host == null || opener == null || registrar == null) {
            throw new IllegalArgumentException(
                "host, opener and registrar must all be non-null");
        }
        Desktop2DCompositorHost controller =
            new Desktop2DCompositorHost(host, opener);
        CompositedWindowBridge bridge = new CompositedWindowBridge(controller);
        registrar.accept(bridge);
        return new Handle(controller, bridge);
    }

    /** The live wiring returned by {@link #install}. */
    public static final class Handle {

        private final Desktop2DCompositorHost controller;
        private final CompositedWindowBridge bridge;

        Handle(Desktop2DCompositorHost controller, CompositedWindowBridge bridge) {
            this.controller = controller;
            this.bridge = bridge;
        }

        /** The desktop-side controller hosting the native windows. */
        public Desktop2DCompositorHost controller() {
            return controller;
        }

        /** The bridge registered as the WM lifecycle listener. */
        public CompositedWindowBridge bridge() {
            return bridge;
        }

        /**
         * Tears the wiring down: unregisters the listener (via {@code unregister},
         * typically {@code registrar::setWindowLifecycleListener(null)}) and
         * disposes every hosted window. Safe to call more than once.
         *
         * @param unregister clears the WM listener, or null to skip
         */
        public void dispose(Runnable unregister) {
            if (unregister != null) {
                unregister.run();
            }
            controller.disposeAll();
        }
    }
}
