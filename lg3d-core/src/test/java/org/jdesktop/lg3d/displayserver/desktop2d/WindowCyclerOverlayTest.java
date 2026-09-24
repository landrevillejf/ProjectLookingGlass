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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JDesktopPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link WindowCyclerOverlay}: the trigger key specs, that install adds
 * the overlay to the desktop pane's popup layer and uninstall removes it, that
 * a trigger with fewer than two windows stays idle, that a trigger opens and
 * steps the selection, that commit raises the highlighted window through the
 * {@link WindowCyclerOverlay.WindowSource} seam, that cancel abandons the
 * selection, and that painting is safe (and a no-op while idle) headless.
 */
class WindowCyclerOverlayTest {

    private static Desktop2DWindow window(String name) {
        return new Desktop2DWindow(name, null, new JPanel(), name);
    }

    /** A fake source that serves a fixed window list and records focus calls. */
    private static final class FakeSource
            implements WindowCyclerOverlay.WindowSource {
        final List<Desktop2DWindow> present = new ArrayList<>();
        final List<Desktop2DWindow> focused = new ArrayList<>();

        @Override
        public List<Desktop2DWindow> presentWindows() {
            return new ArrayList<>(present);
        }

        @Override
        public void focus(Desktop2DWindow window) {
            focused.add(window);
        }
    }

    @Test
    @DisplayName("the trigger specs are Alt+` and Alt+Shift+`, not Alt+Tab")
    void triggerSpecs() {
        assertNotNull(KeyStroke.getKeyStroke(WindowCyclerOverlay.NEXT_SPEC));
        assertNotNull(KeyStroke.getKeyStroke(WindowCyclerOverlay.PREV_SPEC));
        assertEquals(KeyEvent.VK_BACK_QUOTE, WindowCyclerOverlay.triggerKeyCode());
        assertFalse(WindowCyclerOverlay.NEXT_SPEC.contains("TAB"));
    }

    @Test
    @DisplayName("install adds the overlay to the popup layer, uninstall removes it")
    void installAndUninstall() {
        JDesktopPane pane = new JDesktopPane();
        FakeSource source = new FakeSource();
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.install(pane);
        assertTrue(pane.isAncestorOf(overlay));
        assertNotNull(pane.getActionMap().get("lg.windowCycler.next"));
        overlay.uninstall();
        assertFalse(pane.isAncestorOf(overlay));
        assertNull(pane.getActionMap().get("lg.windowCycler.next"));
    }

    @Test
    @DisplayName("a trigger with fewer than two windows stays idle")
    void triggerNeedsTwoWindows() {
        FakeSource source = new FakeSource();
        source.present.add(window("solo"));
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.trigger(true);
        assertFalse(overlay.cycler().isActive());
        assertFalse(overlay.isVisible());
        overlay.uninstall();
    }

    @Test
    @DisplayName("a trigger opens the switcher and steps to the next window")
    void triggerOpensAndAdvances() {
        FakeSource source = new FakeSource();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        source.present.add(a);
        source.present.add(b);
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.trigger(true);
        assertTrue(overlay.cycler().isActive());
        assertTrue(overlay.isVisible());
        // open highlights index 0, the first trigger advances to index 1.
        assertEquals(1, overlay.cycler().selectedIndex());
        assertSame(b, overlay.cycler().selected());
        overlay.cancelSelection();
        overlay.uninstall();
    }

    @Test
    @DisplayName("a backward trigger steps to the previous window")
    void triggerBackSteps() {
        FakeSource source = new FakeSource();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        Desktop2DWindow c = window("c");
        source.present.add(a);
        source.present.add(b);
        source.present.add(c);
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.trigger(false);
        assertEquals(2, overlay.cycler().selectedIndex());
        assertSame(c, overlay.cycler().selected());
        overlay.cancelSelection();
        overlay.uninstall();
    }

    @Test
    @DisplayName("commit raises the highlighted window through the source")
    void commitFocusesSelected() {
        FakeSource source = new FakeSource();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        source.present.add(a);
        source.present.add(b);
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.trigger(true);
        overlay.commitSelection();
        assertEquals(List.of(b), source.focused);
        assertFalse(overlay.cycler().isActive());
        assertFalse(overlay.isVisible());
        overlay.uninstall();
    }

    @Test
    @DisplayName("commit while idle raises nothing")
    void commitIdleNoop() {
        FakeSource source = new FakeSource();
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.commitSelection();
        assertTrue(source.focused.isEmpty());
        overlay.uninstall();
    }

    @Test
    @DisplayName("cancel abandons the selection without raising a window")
    void cancelAbandons() {
        FakeSource source = new FakeSource();
        source.present.add(window("a"));
        source.present.add(window("b"));
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.trigger(true);
        overlay.cancelSelection();
        assertTrue(source.focused.isEmpty());
        assertFalse(overlay.cycler().isActive());
        assertFalse(overlay.isVisible());
        overlay.uninstall();
    }

    @Test
    @DisplayName("painting an idle overlay leaves the canvas untouched")
    void paintIdleNoop() {
        FakeSource source = new FakeSource();
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.setBounds(0, 0, 200, 120);
        BufferedImage image = new BufferedImage(200, 120,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            overlay.paint(g);
        } finally {
            g.dispose();
        }
        assertTrue(allTransparent(image));
        overlay.uninstall();
    }

    @Test
    @DisplayName("painting an active overlay draws the switcher card")
    void paintActiveDraws() {
        FakeSource source = new FakeSource();
        Desktop2DWindow a = window("Terminal");
        Desktop2DWindow b = window("File Manager");
        source.present.add(a);
        source.present.add(b);
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(source);
        overlay.setBounds(0, 0, 400, 300);
        overlay.trigger(true);
        BufferedImage image = new BufferedImage(400, 300,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            overlay.paint(g);
        } finally {
            g.dispose();
        }
        assertFalse(allTransparent(image));
        overlay.commitSelection();
        overlay.uninstall();
    }

    private static boolean allTransparent(BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) & 0xFF000000) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    @DisplayName("uninstall before install is safe")
    void uninstallWithoutInstall() {
        WindowCyclerOverlay overlay = new WindowCyclerOverlay(new FakeSource());
        overlay.uninstall();
        assertFalse(overlay.cycler().isActive());
    }
}
