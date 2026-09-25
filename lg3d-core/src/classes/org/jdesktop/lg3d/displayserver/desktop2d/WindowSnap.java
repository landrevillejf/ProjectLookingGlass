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

import java.awt.Point;
import java.awt.Rectangle;

/**
 * The pure geometry of window snapping on the 2D/Swing desktop: given the
 * pointer position while a window is being dragged and the desktop bounds, it
 * decides which edge (if any) the window should snap to and the rectangle it
 * should occupy there.
 *
 * <p>Dragging a window's title bar against the left or right edge snaps it to
 * that half of the desktop; dragging it against the top edge maximises it. This
 * mirrors the snap-to-edge behaviour of a conventional desktop window manager,
 * which the MDI {@code JDesktopPane} does not provide on its own.</p>
 *
 * <p>Because it is desktop-agnostic, the native 3D desktop reuses the same zone
 * vocabulary and the same half/maximise target rule through the float
 * ({@code zoneForRect}/{@code boundsForRect}) overloads below, which work in
 * world units on a dragged {@code Frame3D}'s bounding rectangle instead of a
 * pointer in pixels; {@code org.jdesktop.lg3d.scenemanager.utils.snap} is the
 * 3D glue that feeds them.</p>
 *
 * <p>This class is pure geometry with no Swing, painting or Java 3D, so the
 * snap decision is unit-testable headless; {@link SnappingDesktopManager} and
 * {@link SnapPreview} are the thin Swing glue that consume it.</p>
 */
public final class WindowSnap {

    /** Which edge of the desktop a dragged window should snap to. */
    public enum Zone {
        /** Not near an edge: leave the window where the user dropped it. */
        NONE,
        /** Near the left edge: occupy the left half. */
        LEFT,
        /** Near the right edge: occupy the right half. */
        RIGHT,
        /** Near the top edge: occupy the whole desktop. */
        MAXIMIZE
    }

    /**
     * How close, in pixels, the pointer must be to an edge to trigger a snap.
     * Chosen wide enough to be easy to hit by dragging to the screen edge
     * without snapping on ordinary moves near the border.
     */
    static final int DEFAULT_EDGE_THRESHOLD_PX = 48;

    private WindowSnap() {
        // no instances
    }

    /**
     * The snap zone for a dragged window's bounding rectangle, in any
     * consistent unit (the 3D desktop passes world units). The rectangle is
     * given as {@code (left, top, right, bottom)} with {@code top > bottom}
     * (a y-up space) and the screen the same way; the top edge wins over the
     * side edges so dragging into the top corner maximises rather than
     * half-snaps. Returns {@link Zone#NONE} for degenerate inputs or a
     * rectangle away from every edge.
     *
     * @param threshold the edge proximity, in the same unit, that triggers a snap
     */
    public static Zone zoneForRect(float left, float top, float right, float bottom,
            float screenLeft, float screenTop, float screenRight, float screenBottom,
            float threshold) {
        if (!(right > left) || !(top > bottom) || threshold <= 0f
                || !(screenRight > screenLeft) || !(screenTop > screenBottom)) {
            return Zone.NONE;
        }
        if (screenTop - top <= threshold) {
            return Zone.MAXIMIZE;
        }
        if (left - screenLeft <= threshold) {
            return Zone.LEFT;
        }
        if (screenRight - right <= threshold) {
            return Zone.RIGHT;
        }
        return Zone.NONE;
    }

    /**
     * The rectangle a window occupies when snapped to {@code zone}, as
     * {@code {left, top, width, height}} in the same unit and orientation as
     * {@link #zoneForRect}. The left/right halves split the screen width
     * exactly; {@code MAXIMIZE} returns the whole screen. Returns null for
     * {@link Zone#NONE} or a degenerate screen.
     */
    public static float[] boundsForRect(Zone zone, float screenLeft, float screenTop,
            float screenRight, float screenBottom) {
        if (zone == null || !(screenRight > screenLeft) || !(screenTop > screenBottom)) {
            return null;
        }
        float width = screenRight - screenLeft;
        float height = screenTop - screenBottom;
        switch (zone) {
            case LEFT:
                return new float[] { screenLeft, screenTop, width * 0.5f, height };
            case RIGHT:
                return new float[] { screenLeft + width * 0.5f, screenTop,
                        width * 0.5f, height };
            case MAXIMIZE:
                return new float[] { screenLeft, screenTop, width, height };
            case NONE:
            default:
                return null;
        }
    }

    /** The centre {@code {cx, cy}} of a {@link #boundsForRect} result, or null. */
    public static float[] centreOf(float[] bounds) {
        if (bounds == null) {
            return null;
        }
        return new float[] { bounds[0] + bounds[2] * 0.5f, bounds[1] - bounds[3] * 0.5f };
    }

    /**
     * The snap zone for a {@code pointer} (in desktop-pane coordinates) within
     * {@code desktop}. The top edge wins over the side edges so dragging into
     * the top corner maximises rather than half-snaps. Returns
     * {@link Zone#NONE} for null/empty inputs or a pointer away from any edge.
     *
     * @param pointer   the drag position in the desktop pane's coordinate space
     * @param desktop   the desktop pane bounds (origin need not be 0,0)
     * @param threshold the edge proximity, in pixels, that triggers a snap
     */
    static Zone zoneFor(Point pointer, Rectangle desktop, int threshold) {
        if (pointer == null || desktop == null
                || desktop.width <= 0 || desktop.height <= 0 || threshold <= 0) {
            return Zone.NONE;
        }
        if (pointer.y - desktop.y <= threshold) {
            return Zone.MAXIMIZE;
        }
        if (pointer.x - desktop.x <= threshold) {
            return Zone.LEFT;
        }
        if (desktop.x + desktop.width - pointer.x <= threshold) {
            return Zone.RIGHT;
        }
        return Zone.NONE;
    }

    /** Convenience overload using {@link #DEFAULT_EDGE_THRESHOLD_PX}. */
    static Zone zoneFor(Point pointer, Rectangle desktop) {
        return zoneFor(pointer, desktop, DEFAULT_EDGE_THRESHOLD_PX);
    }

    /**
     * The rectangle a window occupies when snapped to {@code zone} within
     * {@code desktop}. The left/right halves split the desktop width, with the
     * odd pixel going to the left half so the two never overlap; {@code MAXIMIZE}
     * returns the whole desktop. Returns null for {@link Zone#NONE} or null
     * inputs.
     */
    static Rectangle boundsFor(Zone zone, Rectangle desktop) {
        if (zone == null || desktop == null
                || desktop.width <= 0 || desktop.height <= 0) {
            return null;
        }
        switch (zone) {
            case LEFT:
                return new Rectangle(desktop.x, desktop.y,
                        halfWidth(desktop.width), desktop.height);
            case RIGHT: {
                int left = halfWidth(desktop.width);
                return new Rectangle(desktop.x + left, desktop.y,
                        desktop.width - left, desktop.height);
            }
            case MAXIMIZE:
                return new Rectangle(desktop);
            case NONE:
            default:
                return null;
        }
    }

    /** The left-hand share of a width, rounding the odd pixel to the left. */
    private static int halfWidth(int width) {
        return (width + 1) / 2;
    }
}
