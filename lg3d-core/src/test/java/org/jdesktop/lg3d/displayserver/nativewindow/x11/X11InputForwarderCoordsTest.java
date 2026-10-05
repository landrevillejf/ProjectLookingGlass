/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link X11InputForwarder#mapLocalToPixel}, the pure
 * mapping from a 3D picking intersection (window-body local coordinates) to an
 * absolute root-relative X pointer position. This is the correctness core of
 * pointer forwarding into a composited window; it needs no {@code Display},
 * {@code NativeWindow3D} or {@code XTest}.
 *
 * <p>Body-local space has its origin at the body centre, +X to the right and
 * +Y <em>up</em>; window pixel space has its origin at the top-left with +Y
 * <em>down</em>. The mapping therefore flips Y.
 */
class X11InputForwarderCoordsTest {

    private final int[] out = new int[2];

    @Test
    void bodyCentreMapsToPixelCentre() {
        assertTrue(X11InputForwarder.mapLocalToPixel(
            0f, 0f, 2f, 2f, 101, 101, 0, 0, out));
        assertArrayEquals(new int[] {50, 50}, out);
    }

    @Test
    void topLeftCornerMapsToOriginPixel() {
        // lx = -bodyW/2 -> u = 0 (left); ly = +bodyH/2 -> v = 0 (top).
        assertTrue(X11InputForwarder.mapLocalToPixel(
            -1f, 1f, 2f, 2f, 101, 101, 0, 0, out));
        assertArrayEquals(new int[] {0, 0}, out);
    }

    @Test
    void bottomRightCornerMapsToLastPixel() {
        // lx = +bodyW/2 -> u = 1 (right); ly = -bodyH/2 -> v = 1 (bottom).
        assertTrue(X11InputForwarder.mapLocalToPixel(
            1f, -1f, 2f, 2f, 101, 101, 0, 0, out));
        assertArrayEquals(new int[] {100, 100}, out);
    }

    @Test
    void positiveLyMovesTowardTopBecauseYIsFlipped() {
        // ly = +0.5 of a height-2 body -> v = 0.5 - 0.25 = 0.25 (upper quarter).
        assertTrue(X11InputForwarder.mapLocalToPixel(
            0f, 0.5f, 2f, 2f, 101, 101, 0, 0, out));
        assertEquals(50, out[0]);
        assertEquals(25, out[1]);
    }

    @Test
    void intersectionOutsideTheBodyIsClampedToEdges() {
        // Far right of the body clamps to u = 1.
        assertTrue(X11InputForwarder.mapLocalToPixel(
            99f, 0f, 2f, 2f, 101, 101, 0, 0, out));
        assertEquals(100, out[0]);
        // Far below the body clamps to v = 1.
        assertTrue(X11InputForwarder.mapLocalToPixel(
            0f, -99f, 2f, 2f, 101, 101, 0, 0, out));
        assertEquals(100, out[1]);
    }

    @Test
    void windowOriginIsAddedToThePixelOffset() {
        assertTrue(X11InputForwarder.mapLocalToPixel(
            0f, 0f, 2f, 2f, 101, 101, 300, 400, out));
        assertArrayEquals(new int[] {350, 450}, out);
    }

    @Test
    void nanIntersectionReturnsFalse() {
        assertFalse(X11InputForwarder.mapLocalToPixel(
            Float.NaN, 0f, 2f, 2f, 101, 101, 0, 0, out));
        assertFalse(X11InputForwarder.mapLocalToPixel(
            0f, Float.NaN, 2f, 2f, 101, 101, 0, 0, out));
    }

    @Test
    void nonPositiveGeometryReturnsFalse() {
        assertFalse(X11InputForwarder.mapLocalToPixel(0f, 0f, 0f, 2f, 101, 101, 0, 0, out));
        assertFalse(X11InputForwarder.mapLocalToPixel(0f, 0f, 2f, -1f, 101, 101, 0, 0, out));
        assertFalse(X11InputForwarder.mapLocalToPixel(0f, 0f, 2f, 2f, 0, 101, 0, 0, out));
        assertFalse(X11InputForwarder.mapLocalToPixel(0f, 0f, 2f, 2f, 101, 0, 0, 0, out));
    }

    @Test
    void singlePixelWindowAlwaysMapsToItsOrigin() {
        // pixelW = pixelH = 1 -> px = round(u * 0) = 0, so the result is the origin.
        assertTrue(X11InputForwarder.mapLocalToPixel(
            0.3f, -0.7f, 2f, 2f, 1, 1, 7, 9, out));
        assertArrayEquals(new int[] {7, 9}, out);
    }
}
