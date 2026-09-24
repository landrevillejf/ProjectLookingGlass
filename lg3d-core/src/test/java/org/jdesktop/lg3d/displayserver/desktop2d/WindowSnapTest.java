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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Point;
import java.awt.Rectangle;
import org.jdesktop.lg3d.displayserver.desktop2d.WindowSnap.Zone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link WindowSnap}: the edge-proximity zone detection (including the
 * top-edge priority over the corners and the degenerate-input guards) and the
 * snap rectangles for each zone (left/right halves that never overlap, full
 * maximise, and null for {@code NONE}). Pure geometry; runs headless.
 */
class WindowSnapTest {

    private static final Rectangle DESKTOP = new Rectangle(0, 0, 800, 600);

    @Test
    @DisplayName("the top edge maximises, even in a top corner")
    void topEdgeMaximises() {
        assertEquals(Zone.MAXIMIZE, WindowSnap.zoneFor(new Point(400, 3), DESKTOP, 48));
        assertEquals(Zone.MAXIMIZE, WindowSnap.zoneFor(new Point(2, 2), DESKTOP, 48));
        assertEquals(Zone.MAXIMIZE, WindowSnap.zoneFor(new Point(798, 2), DESKTOP, 48));
    }

    @Test
    @DisplayName("the left and right edges half-snap below the top threshold")
    void sideEdges() {
        assertEquals(Zone.LEFT, WindowSnap.zoneFor(new Point(5, 300), DESKTOP, 48));
        assertEquals(Zone.RIGHT, WindowSnap.zoneFor(new Point(795, 300), DESKTOP, 48));
    }

    @Test
    @DisplayName("a pointer away from every edge does not snap")
    void centreNoSnap() {
        assertEquals(Zone.NONE, WindowSnap.zoneFor(new Point(400, 300), DESKTOP, 48));
    }

    @Test
    @DisplayName("the threshold boundary is inclusive")
    void thresholdInclusive() {
        assertEquals(Zone.LEFT, WindowSnap.zoneFor(new Point(48, 300), DESKTOP, 48));
        assertEquals(Zone.NONE, WindowSnap.zoneFor(new Point(49, 300), DESKTOP, 48));
    }

    @Test
    @DisplayName("the default-threshold overload uses 48px")
    void defaultThreshold() {
        assertEquals(48, WindowSnap.DEFAULT_EDGE_THRESHOLD_PX);
        assertEquals(Zone.LEFT, WindowSnap.zoneFor(new Point(40, 300), DESKTOP));
        assertEquals(Zone.NONE, WindowSnap.zoneFor(new Point(400, 300), DESKTOP));
    }

    @Test
    @DisplayName("degenerate inputs never snap")
    void degenerateInputs() {
        assertEquals(Zone.NONE, WindowSnap.zoneFor(null, DESKTOP, 48));
        assertEquals(Zone.NONE, WindowSnap.zoneFor(new Point(0, 0), null, 48));
        assertEquals(Zone.NONE,
                WindowSnap.zoneFor(new Point(0, 0), new Rectangle(0, 0, 0, 600), 48));
        assertEquals(Zone.NONE,
                WindowSnap.zoneFor(new Point(0, 0), new Rectangle(0, 0, 800, 0), 48));
        assertEquals(Zone.NONE, WindowSnap.zoneFor(new Point(0, 0), DESKTOP, 0));
    }

    @Test
    @DisplayName("a desktop with a non-zero origin is handled relative to it")
    void offsetDesktop() {
        Rectangle offset = new Rectangle(100, 50, 800, 600);
        assertEquals(Zone.LEFT, WindowSnap.zoneFor(new Point(105, 300), offset, 48));
        assertEquals(Zone.RIGHT, WindowSnap.zoneFor(new Point(895, 300), offset, 48));
        assertEquals(new Rectangle(100, 50, 400, 600),
                WindowSnap.boundsFor(Zone.LEFT, offset));
    }

    @Test
    @DisplayName("the left and right halves split an even width evenly")
    void evenSplit() {
        assertEquals(new Rectangle(0, 0, 400, 600),
                WindowSnap.boundsFor(Zone.LEFT, DESKTOP));
        assertEquals(new Rectangle(400, 0, 400, 600),
                WindowSnap.boundsFor(Zone.RIGHT, DESKTOP));
    }

    @Test
    @DisplayName("an odd width gives the extra pixel to the left, never overlapping")
    void oddSplit() {
        Rectangle odd = new Rectangle(0, 0, 801, 600);
        Rectangle left = WindowSnap.boundsFor(Zone.LEFT, odd);
        Rectangle right = WindowSnap.boundsFor(Zone.RIGHT, odd);
        assertEquals(new Rectangle(0, 0, 401, 600), left);
        assertEquals(new Rectangle(401, 0, 400, 600), right);
        assertEquals(801, left.width + right.width);
    }

    @Test
    @DisplayName("maximise covers the whole desktop")
    void maximiseBounds() {
        assertEquals(new Rectangle(DESKTOP), WindowSnap.boundsFor(Zone.MAXIMIZE, DESKTOP));
    }

    @Test
    @DisplayName("NONE and degenerate inputs have no bounds")
    void noBounds() {
        assertNull(WindowSnap.boundsFor(Zone.NONE, DESKTOP));
        assertNull(WindowSnap.boundsFor(null, DESKTOP));
        assertNull(WindowSnap.boundsFor(Zone.LEFT, null));
        assertNull(WindowSnap.boundsFor(Zone.LEFT, new Rectangle(0, 0, 0, 0)));
    }
}
