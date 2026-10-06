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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.CompositedWindowHost;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.CompositedWindowSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link Desktop2DCompositorHost} — the desktop-side
 * controller that gives each composited native X11 window a
 * {@link Desktop2DWindow}. It reaches X only through the
 * {@link CompositedWindowHost} seam, so a fake host plus a fake opener that
 * builds a real (headless-constructible) {@link Desktop2DWindow} exercise the
 * whole map/resize/retitle/unmap/dispose lifecycle with no {@code gnu.x11.Display}
 * and no Java 3D.
 */
class Desktop2DCompositorHostTest {

    /** Records the resize/dispose calls the controller makes on a hosted window. */
    private static final class FakeHosted implements CompositedWindowHost.HostedWindow {
        final JComponent component = new JPanel();
        int resizedW = Integer.MIN_VALUE;
        int resizedH = Integer.MIN_VALUE;
        int resizes;
        int disposes;

        @Override
        public JComponent getComponent() {
            return component;
        }

        @Override
        public void resized(int width, int height) {
            resizes++;
            resizedW = width;
            resizedH = height;
        }

        @Override
        public void dispose() {
            disposes++;
        }
    }

    /** Records each {@code open} and hands back a {@link FakeHosted}. */
    private static final class FakeHost implements CompositedWindowHost {
        final List<int[]> opened = new ArrayList<>();
        final List<String> openedTitles = new ArrayList<>();
        FakeHosted last;

        @Override
        public HostedWindow open(int windowId, String title, int width, int height) {
            opened.add(new int[] { windowId, width, height });
            openedTitles.add(title);
            last = new FakeHosted();
            return last;
        }
    }

    /** Builds a real Desktop2DWindow and tracks its INTERNAL_FRAME_CLOSED events. */
    private static final class FakeOpener implements Desktop2DCompositorHost.WindowOpener {
        final List<Desktop2DWindow> created = new ArrayList<>();
        final List<Integer> closedCounts = new ArrayList<>();
        final javax.swing.JDesktopPane desktop = new javax.swing.JDesktopPane();

        @Override
        public Desktop2DWindow open(String title, JComponent content) {
            Desktop2DWindow win = new Desktop2DWindow(title, null, content, title);
            final int idx = created.size();
            closedCounts.add(0);
            win.addInternalFrameListener(new InternalFrameAdapter() {
                @Override
                public void internalFrameClosed(InternalFrameEvent e) {
                    closedCounts.set(idx, closedCounts.get(idx) + 1);
                }
            });
            desktop.add(win);
            created.add(win);
            return win;
        }
    }

    private static Desktop2DCompositorHost newController(FakeHost host, FakeOpener opener) {
        return new Desktop2DCompositorHost(host, opener);
    }

    @Test
    @DisplayName("mapping a window opens a host surface and a desktop window")
    void mapHostsWindow() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);

        controller.windowMapped(0x1234, "Firefox", 640, 480);

        assertTrue(controller.isHosted(0x1234));
        assertEquals(1, controller.hostedCount());
        assertEquals(1, host.opened.size());
        assertArrayEquals0x(host.opened.get(0), 0x1234, 640, 480);
        assertEquals("Firefox", host.openedTitles.get(0));
        assertEquals(1, opener.created.size());
        assertEquals("Firefox", opener.created.get(0).getTitle());
        // The desktop window embeds exactly the hosted component.
        assertSame(host.last.component,
            opener.created.get(0).getContentPane().getComponent(0));
    }

    private static void assertArrayEquals0x(int[] actual, int id, int w, int h) {
        assertEquals(id, actual[0]);
        assertEquals(w, actual[1]);
        assertEquals(h, actual[2]);
    }

    @Test
    @DisplayName("a retitle updates the desktop window's title")
    void retitleUpdatesWindow() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);
        controller.windowMapped(0x1, "Old", 100, 100);

        controller.windowRetitled(0x1, "New");
        assertEquals("New", opener.created.get(0).getTitle());

        // A null title or an unknown window is ignored (no throw, no change).
        controller.windowRetitled(0x1, null);
        assertEquals("New", opener.created.get(0).getTitle());
        controller.windowRetitled(0x999, "Ignored");
        assertEquals("New", opener.created.get(0).getTitle());
    }

    @Test
    @DisplayName("a resize is forwarded to the hosted surface")
    void resizeForwarded() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);
        controller.windowMapped(0x1, "T", 100, 100);
        FakeHosted hosted = host.last;

        controller.windowResized(0x1, 800, 600);

        assertEquals(1, hosted.resizes);
        assertEquals(800, hosted.resizedW);
        assertEquals(600, hosted.resizedH);
        // Unknown window: no-op.
        controller.windowResized(0x999, 1, 1);
        assertEquals(1, hosted.resizes);
    }

    @Test
    @DisplayName("re-mapping a hosted window folds into a resize, not a duplicate")
    void remapFoldsToResize() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);
        controller.windowMapped(0x1, "T", 100, 100);
        FakeHosted hosted = host.last;

        controller.windowMapped(0x1, "T2", 200, 150);

        assertEquals(1, host.opened.size());       // not opened again
        assertEquals(1, controller.hostedCount());  // not duplicated
        assertEquals(1, opener.created.size());
        assertEquals(1, hosted.resizes);            // folded into a resize
        assertEquals(200, hosted.resizedW);
        assertEquals("T2", opener.created.get(0).getTitle()); // retitled
    }

    @Test
    @DisplayName("unmapping disposes the surface and closes the desktop window")
    void unmapDisposesAndCloses() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);
        controller.windowMapped(0x1, "T", 100, 100);
        FakeHosted hosted = host.last;

        controller.windowUnmapped(0x1);

        assertEquals(1, hosted.disposes);
        assertEquals(1, opener.closedCounts.get(0).intValue());
        assertFalse(controller.isHosted(0x1));
        assertEquals(0, controller.hostedCount());

        // A second unmap of the same window is a no-op (not double-disposed).
        controller.windowUnmapped(0x1);
        assertEquals(1, hosted.disposes);
        // An unknown window is a no-op.
        controller.windowUnmapped(0x999);
    }

    @Test
    @DisplayName("disposeAll disposes every hosted window and clears the map")
    void disposeAllClearsEverything() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);
        controller.windowMapped(0x1, "A", 10, 10);
        FakeHosted first = host.last;
        controller.windowMapped(0x2, "B", 10, 10);
        FakeHosted second = host.last;

        controller.disposeAll();

        assertEquals(1, first.disposes);
        assertEquals(1, second.disposes);
        assertEquals(1, opener.closedCounts.get(0).intValue());
        assertEquals(1, opener.closedCounts.get(1).intValue());
        assertEquals(0, controller.hostedCount());
        assertFalse(controller.isHosted(0x1));
    }

    @Test
    @DisplayName("a null host or opener is rejected by the constructor")
    void rejectsNulls() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        assertThrows(IllegalArgumentException.class,
            () -> new Desktop2DCompositorHost(null, opener));
        assertThrows(IllegalArgumentException.class,
            () -> new Desktop2DCompositorHost(host, null));
    }

    @Test
    @DisplayName("mapped windows are tracked in the focus/stacking model")
    void focusModelTracksWindows() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);
        assertEquals(CompositedWindowSet.FocusPolicy.POINTER,
            controller.getFocusPolicy());

        controller.windowMapped(1, "A", 10, 10);
        controller.windowMapped(2, "B", 10, 10);
        controller.windowMapped(3, "C", 10, 10);

        assertEquals(Integer.valueOf(3), controller.focusedWindowId());
        assertEquals(java.util.Arrays.asList(3, 2, 1), controller.stackTopDown());
    }

    @Test
    @DisplayName("focusWindow activates and raises the window in the model")
    void focusWindowRaises() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);
        controller.windowMapped(1, "A", 10, 10);
        controller.windowMapped(2, "B", 10, 10);
        controller.windowMapped(3, "C", 10, 10);

        controller.focusWindow(1);

        assertEquals(Integer.valueOf(1), controller.focusedWindowId());
        assertEquals(java.util.Arrays.asList(1, 3, 2), controller.stackTopDown());
        // Unknown window: no-op.
        controller.focusWindow(999);
        assertEquals(Integer.valueOf(1), controller.focusedWindowId());
    }

    @Test
    @DisplayName("pointerEnter moves focus under POINTER but not under CLICK")
    void pointerEnterRespectsPolicy() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost pointerCtl = newController(host, opener);
        pointerCtl.windowMapped(1, "A", 10, 10);
        pointerCtl.windowMapped(2, "B", 10, 10);
        pointerCtl.pointerEnter(1);
        assertEquals(Integer.valueOf(1), pointerCtl.focusedWindowId());

        FakeHost host2 = new FakeHost();
        FakeOpener opener2 = new FakeOpener();
        Desktop2DCompositorHost clickCtl = new Desktop2DCompositorHost(
            host2, opener2, CompositedWindowSet.FocusPolicy.CLICK);
        assertEquals(CompositedWindowSet.FocusPolicy.CLICK, clickCtl.getFocusPolicy());
        clickCtl.windowMapped(1, "A", 10, 10);
        clickCtl.windowMapped(2, "B", 10, 10);
        clickCtl.pointerEnter(1);
        assertEquals(Integer.valueOf(2), clickCtl.focusedWindowId()); // unchanged
    }

    @Test
    @DisplayName("unmap and retitle keep the focus model in sync")
    void unmapAndRetitleSyncModel() {
        FakeHost host = new FakeHost();
        FakeOpener opener = new FakeOpener();
        Desktop2DCompositorHost controller = newController(host, opener);
        controller.windowMapped(1, "A", 10, 10);
        controller.windowMapped(2, "B", 10, 10);

        controller.windowRetitled(2, "B2");
        controller.windowUnmapped(2);

        assertEquals(Integer.valueOf(1), controller.focusedWindowId());
        assertEquals(java.util.Arrays.asList(1), controller.stackTopDown());

        controller.disposeAll();
        assertTrue(controller.stackTopDown().isEmpty());
        assertNull(controller.focusedWindowId());
    }
}
