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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.CompositedWindowHost;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.WindowLifecycleListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link CompositedDesktopWiring} — the turnkey Phase G
 * assembly that connects a live X11 compositor session to the 2D desktop so
 * native X11 clients are hosted as ordinary MDI windows. The install decision,
 * null guards, default opener and full map&rarr;window path are all exercised
 * with fakes and a real (unrealized) {@link JDesktopPane}: no
 * {@code gnu.x11.Display}, no Java 3D, no live desktop.
 *
 * <p>{@link CompositedDesktopWiring#install} builds its bridge with the
 * {@code SWING_EDT} runner, so lifecycle events fired through the registered
 * listener are marshalled onto the Swing event dispatch thread; the end-to-end
 * case {@linkplain #flushEdt() flushes} the EDT to observe the result.</p>
 */
class CompositedDesktopWiringTest {

    /** Records resize/dispose on a hosted surface. */
    private static final class FakeHosted implements CompositedWindowHost.HostedWindow {
        final JComponent component = new JPanel();
        int resizes;
        int disposes;

        @Override
        public JComponent getComponent() {
            return component;
        }

        @Override
        public void resized(int width, int height) {
            resizes++;
        }

        @Override
        public void dispose() {
            disposes++;
        }
    }

    /** Hands back a FakeHosted per open, remembering each. */
    private static final class FakeHost implements CompositedWindowHost {
        final List<FakeHosted> hosted = new ArrayList<>();

        @Override
        public HostedWindow open(int windowId, String title, int width, int height) {
            FakeHosted h = new FakeHosted();
            hosted.add(h);
            return h;
        }
    }

    /** Captures the listener the wiring registers, as install() expects. */
    private static final class CapturingRegistrar
            implements Consumer<WindowLifecycleListener> {
        final AtomicReference<WindowLifecycleListener> captured =
            new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void accept(WindowLifecycleListener listener) {
            captured.set(listener);
            calls.incrementAndGet();
        }
    }

    /** Runs the EDT to completion so invokeLater'd bridge work is observable. */
    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            // ordering barrier only
        });
    }

    @Test
    @DisplayName("shouldInstall is true only when opted in, live and not headless")
    void shouldInstallTruthTable() {
        assertTrue(CompositedDesktopWiring.shouldInstall(true, true, false));
        assertFalse(CompositedDesktopWiring.shouldInstall(false, true, false));
        assertFalse(CompositedDesktopWiring.shouldInstall(true, false, false));
        assertFalse(CompositedDesktopWiring.shouldInstall(true, true, true));
        assertFalse(CompositedDesktopWiring.shouldInstall(false, false, true));
    }

    @Test
    @DisplayName("the opt-in property name is the documented one")
    void optInProperty() {
        assertEquals("lg3d.x11.composite2d", CompositedDesktopWiring.OPT_IN_PROPERTY);
    }

    @Test
    @DisplayName("desktopOpener adds a visible Desktop2DWindow to the pane")
    void desktopOpenerAddsWindow() {
        JDesktopPane pane = new JDesktopPane();
        Desktop2DCompositorHost.WindowOpener opener =
            CompositedDesktopWiring.desktopOpener(pane);
        JPanel content = new JPanel();

        Desktop2DWindow window = opener.open("Firefox", content);

        assertNotNull(window);
        assertEquals("Firefox", window.getTitle());
        assertTrue(window.isVisible());
        assertSame(pane, window.getParent());
        assertSame(content, window.getContentPane().getComponent(0));
    }

    @Test
    @DisplayName("desktopOpener rejects a null pane")
    void desktopOpenerNullPane() {
        assertThrows(IllegalArgumentException.class,
            () -> CompositedDesktopWiring.desktopOpener(null));
    }

    @Test
    @DisplayName("install rejects any null collaborator")
    void installNullChecks() {
        JDesktopPane pane = new JDesktopPane();
        Desktop2DCompositorHost.WindowOpener opener =
            CompositedDesktopWiring.desktopOpener(pane);
        CapturingRegistrar registrar = new CapturingRegistrar();
        FakeHost host = new FakeHost();

        assertThrows(IllegalArgumentException.class,
            () -> CompositedDesktopWiring.install(null, opener, registrar));
        assertThrows(IllegalArgumentException.class,
            () -> CompositedDesktopWiring.install(host, null, registrar));
        assertThrows(IllegalArgumentException.class,
            () -> CompositedDesktopWiring.install(host, opener, null));
    }

    @Test
    @DisplayName("install registers a bridge and exposes it on the handle")
    void installRegistersBridge() {
        JDesktopPane pane = new JDesktopPane();
        CapturingRegistrar registrar = new CapturingRegistrar();

        CompositedDesktopWiring.Handle handle = CompositedDesktopWiring.install(
            new FakeHost(), CompositedDesktopWiring.desktopOpener(pane), registrar);

        assertEquals(1, registrar.calls.get());
        assertSame(handle.bridge(), registrar.captured.get());
        assertNotNull(handle.controller());
        assertTrue(registrar.captured.get() instanceof CompositedWindowBridge);
    }

    @Test
    @DisplayName("a mapped event through the wiring opens a window in the pane")
    void endToEndMapOpensWindow() throws Exception {
        JDesktopPane pane = new JDesktopPane();
        FakeHost host = new FakeHost();
        CapturingRegistrar registrar = new CapturingRegistrar();

        CompositedDesktopWiring.Handle handle = CompositedDesktopWiring.install(
            host, CompositedDesktopWiring.desktopOpener(pane), registrar);

        // Fire through the registered listener exactly as the window manager
        // would; the bridge marshals onto the EDT, so flush before asserting.
        registrar.captured.get().windowMapped(0x2400, "Firefox", 640, 480);
        flushEdt();

        assertTrue(handle.controller().isHosted(0x2400));
        assertEquals(1, host.hosted.size());
        assertEquals(1, pane.getComponentCount());
        Desktop2DWindow window = (Desktop2DWindow) pane.getComponent(0);
        assertEquals("Firefox", window.getTitle());
        assertSame(host.hosted.get(0).component,
            window.getContentPane().getComponent(0));
    }

    @Test
    @DisplayName("dispose unregisters the listener and releases hosted windows")
    void disposeUnregistersAndReleases() throws Exception {
        JDesktopPane pane = new JDesktopPane();
        FakeHost host = new FakeHost();
        CapturingRegistrar registrar = new CapturingRegistrar();
        AtomicInteger unregisters = new AtomicInteger();

        CompositedDesktopWiring.Handle handle = CompositedDesktopWiring.install(
            host, CompositedDesktopWiring.desktopOpener(pane), registrar);
        registrar.captured.get().windowMapped(1, "A", 100, 100);
        flushEdt();
        assertTrue(handle.controller().isHosted(1));

        handle.dispose(unregisters::incrementAndGet);

        assertEquals(1, unregisters.get());
        assertFalse(handle.controller().isHosted(1));
        assertEquals(0, handle.controller().hostedCount());
        assertEquals(1, host.hosted.get(0).disposes);
    }

    @Test
    @DisplayName("dispose with no unregister hook still releases windows")
    void disposeNullUnregister() throws Exception {
        JDesktopPane pane = new JDesktopPane();
        FakeHost host = new FakeHost();
        CapturingRegistrar registrar = new CapturingRegistrar();

        CompositedDesktopWiring.Handle handle = CompositedDesktopWiring.install(
            host, CompositedDesktopWiring.desktopOpener(pane), registrar);
        registrar.captured.get().windowMapped(7, "G", 50, 50);
        flushEdt();

        handle.dispose(null);   // must not throw

        assertEquals(0, handle.controller().hostedCount());
        assertEquals(1, host.hosted.get(0).disposes);
    }
}
