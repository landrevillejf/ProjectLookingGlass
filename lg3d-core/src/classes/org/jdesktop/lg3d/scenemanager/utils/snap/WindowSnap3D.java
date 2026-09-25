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
package org.jdesktop.lg3d.scenemanager.utils.snap;

import org.jdesktop.lg3d.displayserver.desktop2d.WindowSnap;

/**
 * The pure world-space geometry of window snapping on the native 3D desktop:
 * the {@code Frame3D} counterpart of the 2D/Swing desktop's
 * {@code SnappingDesktopManager}, reduced to float arithmetic so it is
 * headless-testable.
 *
 * <p>The zone vocabulary and the half/maximise target rule are <em>not</em>
 * re-invented here: they are the shared {@link WindowSnap} model the 2D desktop
 * already uses, reached through its float {@code zoneForRect}/{@code
 * boundsForRect} overloads. What is 3D-specific is only the coordinate
 * plumbing — a dragged window is a centre translation plus a preferred size
 * times a uniform scale in a y-up world space whose origin is the screen
 * centre, and the usable region is the screen minus the taskbar's reserved
 * strips and the title-bar headroom the decoration's maximise already leaves.</p>
 *
 * <p>Snapping is edge-based rather than pointer-based: a window snaps when its
 * own left/right/top edge comes within {@link #DEFAULT_EDGE_THRESHOLD} of the
 * matching screen edge while it is being dragged, which is the natural gesture
 * when the whole window (not just a title bar) follows the pointer.</p>
 */
public final class WindowSnap3D {

    /**
     * How close, in world units, a dragged window's edge must come to a screen
     * edge to snap. The screen is ~0.36 world units wide for a 1920 px display,
     * so this is on the order of sixty pixels: easy to hit by dragging to the
     * edge without snapping on ordinary moves.
     */
    public static final float DEFAULT_EDGE_THRESHOLD = 0.012f;

    /**
     * Shrink factor applied to the aspect-preserving fit of a pure-3D window
     * into its snap target, so a snapped window keeps a visible margin instead
     * of touching its neighbours. Matches the decoration's maximise margin.
     */
    public static final float FIT_MARGIN = 0.98f;

    /** Headroom left above a maximised window so its title bar stays clickable. */
    public static final float HEADROOM = 0.012f;

    private WindowSnap3D() {
        // Pure static geometry; not instantiable.
    }

    /**
     * A window's bounding rectangle {@code {left, top, right, bottom}} (y-up)
     * from its centre translation, preferred size and uniform scale.
     */
    public static float[] frameRect(float cx, float cy,
            float prefWidth, float prefHeight, float scale) {
        float halfW = prefWidth * scale * 0.5f;
        float halfH = prefHeight * scale * 0.5f;
        return new float[] { cx - halfW, cy + halfH, cx + halfW, cy - halfH };
    }

    /**
     * The usable screen rectangle {@code {left, top, right, bottom}} (y-up):
     * the full screen minus the taskbar's reserved bottom/top strips and the
     * title-bar headroom, exactly the region the decoration's maximise fills.
     */
    public static float[] screenRect(float screenWidth, float screenHeight,
            float reservedBottom, float reservedTop) {
        return new float[] {
            -screenWidth * 0.5f,
            screenHeight * 0.5f - Math.max(0f, reservedTop) - HEADROOM,
            screenWidth * 0.5f,
            -screenHeight * 0.5f + Math.max(0f, reservedBottom),
        };
    }

    /** The snap zone a dragged window's rectangle currently maps to. */
    public static WindowSnap.Zone zone(float[] frame, float[] screen, float threshold) {
        if (frame == null || screen == null) {
            return WindowSnap.Zone.NONE;
        }
        return WindowSnap.zoneForRect(frame[0], frame[1], frame[2], frame[3],
                screen[0], screen[1], screen[2], screen[3], threshold);
    }

    /** Convenience overload using {@link #DEFAULT_EDGE_THRESHOLD}. */
    public static WindowSnap.Zone zone(float[] frame, float[] screen) {
        return zone(frame, screen, DEFAULT_EDGE_THRESHOLD);
    }

    /** The centre {@code {cx, cy}} a window snapped to {@code zone} moves to. */
    public static float[] targetCentre(WindowSnap.Zone zone, float[] screen) {
        if (screen == null) {
            return null;
        }
        return WindowSnap.centreOf(
                WindowSnap.boundsForRect(zone, screen[0], screen[1], screen[2], screen[3]));
    }

    /** The size {@code {width, height}} a window snapped to {@code zone} fills. */
    public static float[] targetSize(WindowSnap.Zone zone, float[] screen) {
        if (screen == null) {
            return null;
        }
        float[] bounds = WindowSnap.boundsForRect(
                zone, screen[0], screen[1], screen[2], screen[3]);
        return (bounds == null) ? null : new float[] { bounds[2], bounds[3] };
    }

    /**
     * The uniform (aspect-preserving) scale that fits a window of preferred
     * size {@code (prefWidth, prefHeight)} inside a {@code (targetWidth,
     * targetHeight)} region, shrunk by {@code margin}. Returns 0 for any
     * non-positive input so a caller can treat it as "no fit".
     */
    public static float fitScale(float prefWidth, float prefHeight,
            float targetWidth, float targetHeight, float margin) {
        if (prefWidth <= 0f || prefHeight <= 0f
                || targetWidth <= 0f || targetHeight <= 0f) {
            return 0f;
        }
        return Math.min(targetWidth / prefWidth, targetHeight / prefHeight) * margin;
    }
}
