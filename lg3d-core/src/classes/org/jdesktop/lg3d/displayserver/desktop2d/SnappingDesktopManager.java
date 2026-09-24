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

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.beans.PropertyVetoException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DefaultDesktopManager;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.SwingUtilities;

/**
 * The 2D desktop's MDI desktop manager: it keeps a minimised window's icon in
 * exactly one place (the taskbar button, not a desktop icon) and adds
 * snap-to-edge window placement, which the stock {@code JDesktopPane} lacks.
 *
 * <p>While a window's title bar is dragged, the pointer's proximity to a desktop
 * edge is turned into a {@link WindowSnap.Zone} and the matching target region
 * is highlighted by a {@link SnapPreview} on the pane's palette layer. On
 * release the window is resized to that region: left/right half or maximised.
 * Away from any edge the drag behaves exactly as stock MDI.</p>
 *
 * <p>The snap decision is delegated entirely to the pure, headless-testable
 * {@link WindowSnap}; this class is only the Swing drag plumbing.</p>
 */
final class SnappingDesktopManager extends DefaultDesktopManager {

    private static final Logger logger =
            Logger.getLogger("lg.desktop2d");

    private final int threshold;
    private SnapPreview preview;

    /** The zone highlighted by the most recent {@link #dragFrame}, applied on release. */
    private WindowSnap.Zone pendingZone = WindowSnap.Zone.NONE;

    SnappingDesktopManager() {
        this(WindowSnap.DEFAULT_EDGE_THRESHOLD_PX);
    }

    SnappingDesktopManager(int threshold) {
        this.threshold = threshold;
    }

    /**
     * Hides the desktop icon a minimised window would otherwise drop on the
     * pane: the taskbar button already represents the minimised window, so a
     * second icon reads as clutter (and as a stray row above the taskbar).
     */
    @Override
    public void iconifyFrame(JInternalFrame f) {
        super.iconifyFrame(f);
        f.getDesktopIcon().setVisible(false);
    }

    @Override
    public void dragFrame(JComponent f, int newX, int newY) {
        super.dragFrame(f, newX, newY);
        updateSnap(f, newX, newY);
    }

    @Override
    public void endDraggingFrame(JComponent f) {
        WindowSnap.Zone zone = takePendingZone();
        super.endDraggingFrame(f);
        applyPendingSnap(f, zone);
    }

    /**
     * Recomputes the snap zone for a drag to {@code (newX, newY)} and updates
     * the preview highlight. Split out from {@link #dragFrame} so the snap
     * decision is unit-testable headless: {@code super.dragFrame} renders via
     * {@code Graphics.copyArea}/{@code setXORMode}, which needs a realized pane.
     *
     * @return the zone the drag currently maps to
     */
    WindowSnap.Zone updateSnap(JComponent f, int newX, int newY) {
        JDesktopPane pane = desktopPaneOf(f);
        if (pane == null) {
            pendingZone = WindowSnap.Zone.NONE;
            return pendingZone;
        }
        Rectangle desktopBounds = new Rectangle(pane.getSize());
        Point pointer = pointerIn(pane);
        if (pointer == null) {
            // Headless, or the pointer cannot be read: fall back to the frame's
            // new top-left corner, which tracks the drag closely enough.
            pointer = new Point(newX, newY);
        }
        pendingZone = WindowSnap.zoneFor(pointer, desktopBounds, threshold);
        previewFor(pane).setTarget(WindowSnap.boundsFor(pendingZone, desktopBounds));
        return pendingZone;
    }

    /** Returns the pending zone and resets it to {@code NONE}. */
    WindowSnap.Zone takePendingZone() {
        WindowSnap.Zone zone = pendingZone;
        pendingZone = WindowSnap.Zone.NONE;
        return zone;
    }

    /**
     * Clears the preview and resizes {@code f} to {@code zone}'s region. Split
     * out from {@link #endDraggingFrame} for the same headless reason as
     * {@link #updateSnap}.
     */
    void applyPendingSnap(JComponent f, WindowSnap.Zone zone) {
        JDesktopPane pane = desktopPaneOf(f);
        if (pane != null) {
            previewFor(pane).setTarget(null);
        }
        applySnap(f, pane, zone);
    }

    /** The pane a dragged component lives in, or null. */
    private static JDesktopPane desktopPaneOf(JComponent f) {
        if (f instanceof JInternalFrame) {
            return ((JInternalFrame) f).getDesktopPane();
        }
        if (f != null && f.getParent() instanceof JDesktopPane) {
            return (JDesktopPane) f.getParent();
        }
        return null;
    }

    /** The pointer in the pane's coordinate space, or null if unavailable. */
    private static Point pointerIn(JDesktopPane pane) {
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info == null) {
                return null;
            }
            Point screen = info.getLocation();
            return (pane.isShowing())
                    ? panePointFromScreen(pane, screen)
                    : null;
        } catch (RuntimeException | LinkageError e) {
            // Headless, or no pointer: let the caller fall back to the frame.
            return null;
        }
    }

    private static Point panePointFromScreen(JDesktopPane pane, Point screen) {
        Point local = new Point(screen);
        SwingUtilities.convertPointFromScreen(local, pane);
        return local;
    }

    /** Resizes {@code f} to the snapped region for {@code zone}, if any. */
    private void applySnap(JComponent f, JDesktopPane pane, WindowSnap.Zone zone) {
        if (pane == null || zone == WindowSnap.Zone.NONE
                || !(f instanceof JInternalFrame)) {
            return;
        }
        Rectangle bounds =
                WindowSnap.boundsFor(zone, new Rectangle(pane.getSize()));
        if (bounds == null) {
            return;
        }
        JInternalFrame frame = (JInternalFrame) f;
        try {
            if (frame.isIcon()) {
                frame.setIcon(false);
            }
            if (frame.isMaximum()) {
                frame.setMaximum(false);
            }
        } catch (PropertyVetoException pve) {
            logger.log(Level.FINE, "Could not reset window state before snapping", pve);
        }
        frame.setBounds(bounds);
        frame.revalidate();
        frame.repaint();
    }

    /** The pane's snap-preview overlay, created and layered on first use. */
    private SnapPreview previewFor(JDesktopPane pane) {
        if (preview == null) {
            preview = new SnapPreview();
            pane.add(preview, JDesktopPane.PALETTE_LAYER);
        }
        preview.setBounds(0, 0, pane.getWidth(), pane.getHeight());
        return preview;
    }

    /** The preview overlay, or null before the first drag. Test seam. */
    SnapPreview preview() {
        return preview;
    }

    /** The configured snap threshold, in pixels. Test seam. */
    int threshold() {
        return threshold;
    }
}
