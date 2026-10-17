/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.screencapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.JPanel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the capture engine's window discovery. The desktop pane
 * search and the visible-internal-frame filter are pure component-tree walks
 * over lightweight Swing containers, which construct fine under
 * {@code java.awt.headless}; only the paint calls that need a live desktop are
 * left to the runtime probe.
 */
class DesktopScreenshotTest {

    @Test
    @DisplayName("findDesktopPane returns null when there is none")
    void findDesktopPaneNone() {
        assertNull(DesktopScreenshot.findDesktopPane(null));
        assertNull(DesktopScreenshot.findDesktopPane(new JPanel()));
        JPanel nested = new JPanel();
        nested.add(new JPanel());
        assertNull(DesktopScreenshot.findDesktopPane(nested));
    }

    @Test
    @DisplayName("findDesktopPane finds the pane itself or a nested one")
    void findDesktopPaneNested() {
        JDesktopPane pane = new JDesktopPane();
        assertSame(pane, DesktopScreenshot.findDesktopPane(pane));

        JPanel root = new JPanel();
        JPanel middle = new JPanel();
        middle.add(pane);
        root.add(middle);
        assertSame(pane, DesktopScreenshot.findDesktopPane(root));
    }

    @Test
    @DisplayName("visibleInternalFrames keeps only the visible frames")
    void visibleInternalFramesFilters() {
        assertEquals(0, DesktopScreenshot.visibleInternalFrames(null).size());

        JDesktopPane pane = new JDesktopPane();
        JInternalFrame shown1 = new JInternalFrame("one");
        JInternalFrame shown2 = new JInternalFrame("two");
        JInternalFrame hidden = new JInternalFrame("hidden");
        pane.add(shown1);
        pane.add(shown2);
        pane.add(hidden);
        shown1.setVisible(true);
        shown2.setVisible(true);
        hidden.setVisible(false);

        List<JInternalFrame> visible =
                DesktopScreenshot.visibleInternalFrames(pane);
        assertEquals(2, visible.size());
        assertTrue(visible.contains(shown1));
        assertTrue(visible.contains(shown2));
        assertTrue(!visible.contains(hidden));
    }

    @Test
    @DisplayName("with no top-level frames showing, nothing is captured")
    void noFramesHeadless() {
        assertTrue(DesktopScreenshot.visibleFrames().isEmpty());
        assertTrue(DesktopScreenshot.captureWindows().isEmpty());
    }

    @Test
    @DisplayName("screenBounds of no frames degrades to an empty rect headless")
    void screenBoundsEmpty() {
        Rectangle bounds = DesktopScreenshot.screenBounds(new ArrayList<>());
        assertTrue(bounds.width <= 0 || bounds.height <= 0,
                "expected an empty fallback, got " + bounds);
    }
}
