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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JPanel;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.CompositedWindowHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link CompositedWindowBridge} — the adapter that turns
 * window-manager {@code WindowLifecycleListener} notifications into
 * {@link Desktop2DCompositorHost} calls, so a native X11 client is hosted as an
 * ordinary window inside the 2D desktop. The controller is exercised through the
 * same fake host/opener used by {@code Desktop2DCompositorHostTest}, and the
 * bridge's EDT marshalling is pinned with the synchronous {@code DIRECT} runner
 * plus a counting runner — no {@code gnu.x11.Display}, no Java 3D, no real EDT.
 */
class CompositedWindowBridgeTest {

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

    /** Records each open and hands back a FakeHosted. */
    private static final class FakeHost implements CompositedWindowHost {
        final List<int[]> opened = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        FakeHosted last;

        @Override
        public HostedWindow open(int windowId, String title, int width, int height) {
            opened.add(new int[] { windowId, width, height });
            titles.add(title);
            last = new FakeHosted();
            return last;
        }
    }

    /** Builds a real Desktop2DWindow into a throwaway desktop pane. */
    private static final class FakeOpener implements Desktop2DCompositorHost.WindowOpener {
        final List<Desktop2DWindow> created = new ArrayList<>();
        final javax.swing.JDesktopPane desktop = new javax.swing.JDesktopPane();

        @Override
        public Desktop2DWindow open(String title, JComponent content) {
            Desktop2DWindow win = new Desktop2DWindow(title, null, content, title);
            desktop.add(win);
            created.add(win);
            return win;
        }
    }

    private static CompositedWindowBridge newBridge(FakeHost host, FakeOpener opener,
            Consumer<Runnable> runner) {
        return new CompositedWindowBridge(
            new Desktop2DCompositorHost(host, opener), runner);
    }

    @Test
    @DisplayName("a mapped notification opens a hosted desktop window")
    void mapOpensWindow() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        CompositedWindowBridge bridge =
            newBridge(host, opener, CompositedWindowBridge.DIRECT);

        bridge.windowMapped(0x1234, "Firefox", 640, 480);

        assertEquals(1, host.opened.size());
        assertEquals(0x1234, host.opened.get(0)[0]);
        assertEquals("Firefox", host.titles.get(0));
        assertEquals(1, opener.created.size());
        assertTrue(bridge.getController().isHosted(0x1234));
        // The desktop window embeds the hosted Swing surface.
        assertSame(host.last.component,
            opener.created.get(0).getContentPane().getComponent(0));
    }

    @Test
    @DisplayName("resize, retitle, activate and unmap all reach the controller")
    void lifecycleForwarded() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        CompositedWindowBridge bridge =
            newBridge(host, opener, CompositedWindowBridge.DIRECT);

        bridge.windowMapped(1, "A", 100, 100);
        bridge.windowMapped(2, "B", 100, 100);
        FakeHosted first = host.last;   // last opened is window 2
        bridge.windowResized(2, 800, 600);
        assertEquals(1, first.resizes);

        bridge.windowRetitled(2, "B2");
        assertEquals("B2", opener.created.get(1).getTitle());

        bridge.windowActivated(1);
        assertEquals(Integer.valueOf(1), bridge.getController().focusedWindowId());

        bridge.windowUnmapped(2);
        assertFalse(bridge.getController().isHosted(2));
        assertTrue(bridge.getController().isHosted(1));
    }

    @Test
    @DisplayName("each callback is marshalled through the injected EDT runner")
    void marshalsThroughRunner() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        AtomicInteger marshalled = new AtomicInteger();
        Consumer<Runnable> counting = task -> {
            marshalled.incrementAndGet();
            task.run();
        };
        CompositedWindowBridge bridge = newBridge(host, opener, counting);

        bridge.windowMapped(1, "A", 10, 10);
        bridge.windowResized(1, 20, 20);
        bridge.windowRetitled(1, "A2");
        bridge.windowActivated(1);
        bridge.windowUnmapped(1);

        assertEquals(5, marshalled.get());
    }

    @Test
    @DisplayName("a controller failure is swallowed, never thrown to the caller")
    void isolatesFailures() {
        // A runner whose controller call throws must not propagate: the bridge
        // logs and continues so the X event thread survives.
        Desktop2DCompositorHost throwing = new Desktop2DCompositorHost(
            new CompositedWindowHost() {
                @Override
                public HostedWindow open(int windowId, String title, int w, int h) {
                    throw new IllegalStateException("boom");
                }
            },
            new FakeOpener());
        CompositedWindowBridge bridge =
            new CompositedWindowBridge(throwing, CompositedWindowBridge.DIRECT);

        // Must not throw.
        bridge.windowMapped(1, "A", 10, 10);
        assertEquals(0, throwing.hostedCount());
    }

    @Test
    @DisplayName("a runner that refuses is also swallowed")
    void runnerRefusalSwallowed() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Consumer<Runnable> refusing = task -> {
            throw new IllegalStateException("EDT gone");
        };
        CompositedWindowBridge bridge = newBridge(host, opener, refusing);

        bridge.windowMapped(1, "A", 10, 10);   // must not throw
        assertEquals(0, host.opened.size());   // nothing ran
    }

    @Test
    @DisplayName("the single-arg constructor wires the given controller")
    void defaultCtorWiresController() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = new Desktop2DCompositorHost(host, opener);
        CompositedWindowBridge bridge = new CompositedWindowBridge(controller);
        assertSame(controller, bridge.getController());
    }

    @Test
    @DisplayName("a null controller or runner is rejected")
    void rejectsNulls() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = new Desktop2DCompositorHost(host, opener);
        assertThrows(IllegalArgumentException.class,
            () -> new CompositedWindowBridge(null, CompositedWindowBridge.DIRECT));
        assertThrows(IllegalArgumentException.class,
            () -> new CompositedWindowBridge(controller, null));
    }
}
