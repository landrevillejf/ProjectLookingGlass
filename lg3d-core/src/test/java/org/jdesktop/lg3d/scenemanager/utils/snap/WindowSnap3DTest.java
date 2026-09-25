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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.jdesktop.lg3d.displayserver.desktop2d.WindowSnap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link WindowSnap3D}: the pure world-space plumbing that turns a
 * dragged {@code Frame3D}'s centre translation + preferred size + uniform scale
 * into a snap zone, target centre/size and aspect-preserving fit scale, on a
 * usable screen region that mirrors the decoration's maximise. The zone
 * vocabulary and half/maximise rule themselves are the shared
 * {@link WindowSnap} model, exercised by {@code WindowSnapTest}; here we pin the
 * 3D coordinate maths. Pure geometry; runs headless.
 */
class WindowSnap3DTest {

    private static final float D = 1e-5f;

    // A 1920x1048 screen is ~0.36 x 0.27946666 world units, with a taskbar
    // reserving a bottom strip.
    private static final float SCREEN_W = 0.36f;
    private static final float SCREEN_H = 0.27946666f;
    private static final float RESERVED_BOTTOM = 0.03f;
    private static final float RESERVED_TOP = 0.0f;

    private static float[] screen() {
        return WindowSnap3D.screenRect(
                SCREEN_W, SCREEN_H, RESERVED_BOTTOM, RESERVED_TOP);
    }

    @Test
    @DisplayName("frameRect maps centre + preferred size + scale to a y-up rect")
    void frameRect() {
        float[] rect = WindowSnap3D.frameRect(0f, 0f, 0.1f, 0.08f, 1f);
        assertEquals(-0.05f, rect[0], D); // left
        assertEquals(0.04f, rect[1], D);  // top
        assertEquals(0.05f, rect[2], D);  // right
        assertEquals(-0.04f, rect[3], D); // bottom

        // Scale multiplies both halves about the centre.
        float[] scaled = WindowSnap3D.frameRect(0.02f, -0.01f, 0.1f, 0.08f, 2f);
        assertEquals(0.02f - 0.1f, scaled[0], D);
        assertEquals(-0.01f + 0.08f, scaled[1], D);
        assertEquals(0.02f + 0.1f, scaled[2], D);
        assertEquals(-0.01f - 0.08f, scaled[3], D);
    }

    @Test
    @DisplayName("screenRect is the screen minus reserved strips and headroom")
    void screenRect() {
        float[] s = screen();
        assertEquals(-SCREEN_W * 0.5f, s[0], D);
        assertEquals(SCREEN_H * 0.5f - WindowSnap3D.HEADROOM, s[1], D);
        assertEquals(SCREEN_W * 0.5f, s[2], D);
        assertEquals(-SCREEN_H * 0.5f + RESERVED_BOTTOM, s[3], D);
    }

    @Test
    @DisplayName("screenRect clamps a negative reserve to zero")
    void screenRectNegativeReserve() {
        float[] s = WindowSnap3D.screenRect(SCREEN_W, SCREEN_H, -0.02f, -0.01f);
        assertEquals(SCREEN_H * 0.5f - WindowSnap3D.HEADROOM, s[1], D);
        assertEquals(-SCREEN_H * 0.5f, s[3], D);
    }

    @Test
    @DisplayName("zone resolves the left / right / maximise / none edges")
    void zoneDetection() {
        float[] s = screen();
        // Left: window's left edge within the threshold of the screen left.
        assertEquals(WindowSnap.Zone.LEFT,
                WindowSnap3D.zone(WindowSnap3D.frameRect(-0.125f, 0f, 0.1f, 0.08f, 1f), s));
        // Right.
        assertEquals(WindowSnap.Zone.RIGHT,
                WindowSnap3D.zone(WindowSnap3D.frameRect(0.125f, 0f, 0.1f, 0.08f, 1f), s));
        // Maximise: window's top edge reaches the usable top.
        assertEquals(WindowSnap.Zone.MAXIMIZE,
                WindowSnap3D.zone(WindowSnap3D.frameRect(0f, 0.08f, 0.1f, 0.08f, 1f), s));
        // None: a window in the middle.
        assertEquals(WindowSnap.Zone.NONE,
                WindowSnap3D.zone(WindowSnap3D.frameRect(0f, 0f, 0.1f, 0.08f, 1f), s));
    }

    @Test
    @DisplayName("zone guards null inputs")
    void zoneNullGuards() {
        assertEquals(WindowSnap.Zone.NONE, WindowSnap3D.zone(null, screen()));
        assertEquals(WindowSnap.Zone.NONE,
                WindowSnap3D.zone(WindowSnap3D.frameRect(0f, 0f, 0.1f, 0.08f, 1f), null));
    }

    @Test
    @DisplayName("targetSize is half the width for a side, whole for maximise")
    void targetSize() {
        float[] s = screen();
        float usableH = s[1] - s[3];
        float[] left = WindowSnap3D.targetSize(WindowSnap.Zone.LEFT, s);
        assertEquals((s[2] - s[0]) * 0.5f, left[0], D);
        assertEquals(usableH, left[1], D);

        float[] max = WindowSnap3D.targetSize(WindowSnap.Zone.MAXIMIZE, s);
        assertEquals(s[2] - s[0], max[0], D);
        assertEquals(usableH, max[1], D);

        assertNull(WindowSnap3D.targetSize(WindowSnap.Zone.NONE, s));
        assertNull(WindowSnap3D.targetSize(WindowSnap.Zone.LEFT, null));
    }

    @Test
    @DisplayName("targetCentre is the centre of the target region, y-up")
    void targetCentre() {
        float[] s = screen();
        float[] left = WindowSnap3D.targetCentre(WindowSnap.Zone.LEFT, s);
        // Centre of the left half: a quarter of the width in from the left edge.
        assertEquals(s[0] + (s[2] - s[0]) * 0.25f, left[0], D);
        assertEquals((s[1] + s[3]) * 0.5f, left[1], D);

        assertNull(WindowSnap3D.targetCentre(WindowSnap.Zone.NONE, s));
        assertNull(WindowSnap3D.targetCentre(WindowSnap.Zone.LEFT, null));
    }

    @Test
    @DisplayName("fitScale is the aspect-preserving min fit, shrunk by the margin")
    void fitScale() {
        // Width-limited: 0.18/0.1 = 1.8 beats 0.24/0.08 = 3.0.
        assertEquals(1.8f * WindowSnap3D.FIT_MARGIN,
                WindowSnap3D.fitScale(0.1f, 0.08f, 0.18f, 0.24f, WindowSnap3D.FIT_MARGIN), D);
        // Height-limited: 0.08/0.08 = 1.0 beats 0.30/0.1 = 3.0.
        assertEquals(1.0f * WindowSnap3D.FIT_MARGIN,
                WindowSnap3D.fitScale(0.1f, 0.08f, 0.30f, 0.08f, WindowSnap3D.FIT_MARGIN), D);
    }

    @Test
    @DisplayName("fitScale returns 0 for any non-positive input")
    void fitScaleDegenerate() {
        assertEquals(0f, WindowSnap3D.fitScale(0f, 0.08f, 0.18f, 0.24f, 0.98f), D);
        assertEquals(0f, WindowSnap3D.fitScale(0.1f, -1f, 0.18f, 0.24f, 0.98f), D);
        assertEquals(0f, WindowSnap3D.fitScale(0.1f, 0.08f, 0f, 0.24f, 0.98f), D);
        assertEquals(0f, WindowSnap3D.fitScale(0.1f, 0.08f, 0.18f, 0f, 0.98f), D);
    }
}
