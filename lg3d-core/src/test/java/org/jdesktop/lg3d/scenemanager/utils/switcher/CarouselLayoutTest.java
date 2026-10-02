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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.scenemanager.utils.switcher.CarouselLayout.Pose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link CarouselLayout}: the pure circular positioning
 * behind the 3D desktop's window carousel, lifted out of the scene graph so it
 * can be asserted without Java 3D (which cannot be constructed in the headless
 * test JVM). Verifies the front card's seat, the tangential rotation spacing,
 * the receding depth stack, the focus emphasis, the revolve wrap and the
 * degenerate empty/single-card cases.
 */
class CarouselLayoutTest {

    private static final float DELTA = 1e-4f;
    private static final float RADIUS = 0.16f;

    private final CarouselLayout layout = new CarouselLayout();

    @Test
    @DisplayName("An empty carousel yields the neutral pose")
    void emptyCarousel() {
        Pose pose = layout.pose(0, 0, 0, RADIUS, true);
        assertEquals(0f, pose.x(), DELTA);
        assertEquals(0f, pose.y(), DELTA);
        assertEquals(1f, pose.scale(), DELTA);
        assertFalse(pose.front());
    }

    @Test
    @DisplayName("The front card sits at the circle's bottom, upright and flagged front")
    void frontCardSeat() {
        Pose front = layout.pose(2, 2, 5, RADIUS, false);
        assertTrue(front.front());
        // FRONT_RAD = 3*PI/2 -> cos 0, sin -1: centred horizontally, radius below.
        assertEquals(0f, front.x(), DELTA);
        assertEquals(-RADIUS, front.y(), DELTA);
        assertEquals(0f, front.rotAngle(), DELTA);
    }

    @Test
    @DisplayName("Cards are spaced a full turn apart and rotate tangentially")
    void rotationSpacing() {
        int count = 4;
        float step = (float) (Math.PI * 2 / count);
        for (int slot = 0; slot < count; slot++) {
            Pose pose = layout.pose(slot, 0, count, RADIUS, false);
            int position = CarouselLayout.positionOf(slot, 0, count);
            assertEquals(step * position, pose.rotAngle(), DELTA,
                    "slot " + slot + " tangential rotation");
        }
        // Slot 1 is a quarter turn from the front; slot 2 half; slot 3 three-quarters.
        assertEquals((float) Math.PI / 2, layout.pose(1, 0, 4, RADIUS, false).rotAngle(), DELTA);
        assertEquals((float) Math.PI, layout.pose(2, 0, 4, RADIUS, false).rotAngle(), DELTA);
    }

    @Test
    @DisplayName("The quarter-turn card lands one radius to the side")
    void sideCardPosition() {
        Pose right = layout.pose(1, 0, 4, RADIUS, false);
        assertEquals(RADIUS, right.x(), DELTA);
        assertEquals(0f, right.y(), DELTA);
        assertFalse(right.front());
    }

    @Test
    @DisplayName("Focusing enlarges and pushes the front card toward the viewer only")
    void focusEmphasizesFront() {
        Pose unfocused = layout.pose(0, 0, 4, RADIUS, false);
        Pose focused = layout.pose(0, 0, 4, RADIUS, true);
        assertEquals(1f, unfocused.scale(), DELTA);
        assertTrue(focused.scale() > unfocused.scale(), "front card scales up when focused");
        assertTrue(focused.z() > unfocused.z(), "front card pushed toward the viewer");
        // A non-front card is unaffected by focus.
        Pose sideUnfocused = layout.pose(1, 0, 4, RADIUS, false);
        Pose sideFocused = layout.pose(1, 0, 4, RADIUS, true);
        assertEquals(sideUnfocused.scale(), sideFocused.scale(), DELTA);
        assertEquals(sideUnfocused.z(), sideFocused.z(), DELTA);
    }

    @Test
    @DisplayName("Revolve moves which slot is front, wrapping both ways")
    void revolveWrap() {
        int count = 4;
        // Advancing the front index rotates the ring; each slot takes a turn at front.
        for (int front = 0; front < count; front++) {
            assertTrue(layout.pose(front, front, count, RADIUS, false).front(),
                    "slot " + front + " is front when frontIndex=" + front);
            assertEquals(0, CarouselLayout.positionOf(front, front, count));
        }
        // positionOf wraps into [0, count).
        assertEquals(0, CarouselLayout.positionOf(0, 0, count));
        assertEquals(count - 1, CarouselLayout.positionOf(count - 1, 0, count));
        assertEquals(1, CarouselLayout.positionOf(0, count - 1, count),
                "slot 0 is one step behind front when front wraps to the last slot");
    }

    @Test
    @DisplayName("Rear cards stack progressively deeper so overlaps sort")
    void depthStacking() {
        int count = 5;
        float spacing = CarouselLayout.DEFAULT_STACK_SPACING;
        // position 0 (front) is nearest, positions behind step back.
        Pose p0 = layout.pose(0, 0, count, RADIUS, false);
        Pose p1 = layout.pose(1, 0, count, RADIUS, false);
        Pose p4 = layout.pose(4, 0, count, RADIUS, false);
        assertEquals(-spacing, p0.z(), DELTA);
        assertEquals(-spacing * 2, p1.z(), DELTA);
        // position 4 > half(2): steps back positively by (4-2) spacings.
        assertEquals(spacing * 2, p4.z(), DELTA);
    }

    @Test
    @DisplayName("A single-card carousel seats it at the front")
    void singleCard() {
        Pose only = layout.pose(0, 0, 1, RADIUS, false);
        assertTrue(only.front());
        assertEquals(0f, only.rotAngle(), DELTA);
        assertEquals(-RADIUS, only.y(), DELTA);
    }

    @Test
    @DisplayName("positionOf is safe for a non-positive count")
    void positionOfDegenerate() {
        assertEquals(0, CarouselLayout.positionOf(3, 1, 0));
        assertEquals(0, CarouselLayout.positionOf(3, 1, -1));
    }
}
