/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import org.jdesktop.lg3d.displayserver.desktop2d.WindowSnap.Zone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SnappingDesktopManager}'s snap logic: dragging a window to a
 * desktop edge maps to that half (or maximise at the top), a mid-desktop drag
 * maps to no snap, the preview tracks the pending zone and clears on release,
 * the minimise-still-hides-the-desktop-icon behaviour is preserved, the
 * threshold is configurable, and a component with no desktop pane is a harmless
 * no-op.
 *
 * <p>Runs headless by driving the extracted {@code updateSnap} /
 * {@code takePendingZone} / {@code applyPendingSnap} methods rather than the
 * {@code dragFrame} / {@code endDraggingFrame} overrides: those call
 * {@code super}, which renders through {@code Graphics.copyArea} /
 * {@code setXORMode} and needs a realized pane. With no live pointer the
 * manager falls back to the dragged-to coordinate.</p>
 */
class SnappingDesktopManagerTest {

    private static final int W = 800;
    private static final int H = 600;
    private static final Rectangle START = new Rectangle(100, 100, 200, 150);

    private static JDesktopPane pane() {
        JDesktopPane pane = new JDesktopPane();
        pane.setSize(W, H);
        return pane;
    }

    private static JInternalFrame frameIn(JDesktopPane pane) {
        JInternalFrame frame =
                new JInternalFrame("app", true, true, true, true);
        frame.setBounds(START);
        pane.add(frame);
        return frame;
    }

    /** Simulates a full drag-and-release to {@code (x, y)}, minus super's render. */
    private static Rectangle drag(SnappingDesktopManager manager,
                                  JInternalFrame frame, int x, int y) {
        manager.updateSnap(frame, x, y);
        manager.applyPendingSnap(frame, manager.takePendingZone());
        return frame.getBounds();
    }

    @Test
    @DisplayName("dragging to the left edge snaps to the left half")
    void snapLeft() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager();
        pane.setDesktopManager(manager);
        JInternalFrame frame = frameIn(pane);
        assertEquals(new Rectangle(0, 0, 400, 600), drag(manager, frame, 5, 200));
    }

    @Test
    @DisplayName("dragging to the right edge snaps to the right half")
    void snapRight() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager();
        pane.setDesktopManager(manager);
        JInternalFrame frame = frameIn(pane);
        assertEquals(new Rectangle(400, 0, 400, 600), drag(manager, frame, 795, 200));
    }

    @Test
    @DisplayName("dragging to the top edge maximises")
    void snapMaximise() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager();
        pane.setDesktopManager(manager);
        JInternalFrame frame = frameIn(pane);
        assertEquals(new Rectangle(0, 0, 800, 600), drag(manager, frame, 300, 3));
    }

    @Test
    @DisplayName("dropping mid-desktop leaves the window untouched")
    void noSnapInCentre() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager();
        pane.setDesktopManager(manager);
        JInternalFrame frame = frameIn(pane);
        assertEquals(Zone.NONE, manager.updateSnap(frame, 300, 300));
        assertEquals(START, drag(manager, frame, 300, 300));
    }

    @Test
    @DisplayName("the configured threshold narrows the snap region")
    void customThreshold() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager(10);
        pane.setDesktopManager(manager);
        assertEquals(10, manager.threshold());
        JInternalFrame frame = frameIn(pane);
        // 30px from the left edge is outside a 10px threshold: no snap.
        assertEquals(Zone.NONE, manager.updateSnap(frame, 30, 200));
        assertEquals(START, frame.getBounds());
        // 5px from the left edge is inside it: snap.
        assertEquals(Zone.LEFT, manager.updateSnap(frame, 5, 200));
    }

    @Test
    @DisplayName("the default threshold is 48px")
    void defaultThreshold() {
        assertEquals(WindowSnap.DEFAULT_EDGE_THRESHOLD_PX,
                new SnappingDesktopManager().threshold());
        assertEquals(48, new SnappingDesktopManager().threshold());
    }

    @Test
    @DisplayName("the preview shows the target during a drag and hides on release")
    void previewLifecycle() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager();
        pane.setDesktopManager(manager);
        JInternalFrame frame = frameIn(pane);
        assertNull(manager.preview());
        manager.updateSnap(frame, 5, 200);
        assertNotNull(manager.preview());
        assertTrue(manager.preview().isVisible());
        assertEquals(new Rectangle(0, 0, 400, 600), manager.preview().getTarget());
        manager.applyPendingSnap(frame, manager.takePendingZone());
        assertFalse(manager.preview().isVisible());
        assertNull(manager.preview().getTarget());
    }

    @Test
    @DisplayName("a mid-desktop drag clears the preview target")
    void previewClearedWhenNoZone() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager();
        pane.setDesktopManager(manager);
        JInternalFrame frame = frameIn(pane);
        manager.updateSnap(frame, 400, 300);
        assertNotNull(manager.preview());
        assertNull(manager.preview().getTarget());
        assertFalse(manager.preview().isVisible());
    }

    @Test
    @DisplayName("takePendingZone resets the pending zone to NONE")
    void takePendingZoneResets() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager();
        pane.setDesktopManager(manager);
        JInternalFrame frame = frameIn(pane);
        manager.updateSnap(frame, 5, 200);
        assertEquals(Zone.LEFT, manager.takePendingZone());
        assertEquals(Zone.NONE, manager.takePendingZone());
    }

    @Test
    @DisplayName("minimising still hides the desktop icon (single taskbar button)")
    void iconifyHidesDesktopIcon() {
        JDesktopPane pane = pane();
        SnappingDesktopManager manager = new SnappingDesktopManager();
        pane.setDesktopManager(manager);
        JInternalFrame frame = frameIn(pane);
        frame.setVisible(true);
        manager.iconifyFrame(frame);
        assertFalse(frame.getDesktopIcon().isVisible());
    }

    @Test
    @DisplayName("a component with no desktop pane is a harmless no-op")
    void orphanFrameNoThrow() {
        SnappingDesktopManager manager = new SnappingDesktopManager();
        JInternalFrame orphan =
                new JInternalFrame("orphan", true, true, true, true);
        orphan.setBounds(50, 50, 120, 90);
        assertEquals(Zone.NONE, manager.updateSnap(orphan, 5, 200));
        manager.applyPendingSnap(orphan, manager.takePendingZone());
        assertNull(manager.preview());
        assertEquals(new Rectangle(50, 50, 120, 90), orphan.getBounds());
    }
}
