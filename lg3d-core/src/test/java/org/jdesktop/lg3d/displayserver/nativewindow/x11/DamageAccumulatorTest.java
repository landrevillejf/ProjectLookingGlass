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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.DamageAccumulator.Region;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link DamageAccumulator} — the pure damage-rectangle
 * coalescer (Phase E). It is plain geometry with no X, clock or Java 3D state,
 * so every union/clamp/drain decision is pinned directly.
 */
class DamageAccumulatorTest {

    @Test
    @DisplayName("a fresh accumulator is empty and drains to null")
    void freshIsEmpty() {
        DamageAccumulator acc = new DamageAccumulator();
        assertTrue(acc.isEmpty());
        assertEquals(0, acc.rectCount());
        assertEquals(0, acc.width());
        assertEquals(0, acc.height());
        assertEquals(0L, acc.area());
        assertNull(acc.drain());
    }

    @Test
    @DisplayName("a single rectangle becomes its own bounding region")
    void singleRect() {
        DamageAccumulator acc = new DamageAccumulator();
        acc.add(10, 20, 30, 40);

        assertFalse(acc.isEmpty());
        assertEquals(1, acc.rectCount());
        assertEquals(10, acc.x());
        assertEquals(20, acc.y());
        assertEquals(30, acc.width());
        assertEquals(40, acc.height());
        assertEquals(30L * 40L, acc.area());
    }

    @Test
    @DisplayName("a negative origin is clamped to zero, size preserved")
    void clampsNegativeOrigin() {
        DamageAccumulator acc = new DamageAccumulator();
        acc.add(-5, -7, 8, 9);
        assertEquals(0, acc.x());
        assertEquals(0, acc.y());
        assertEquals(8, acc.width());
        assertEquals(9, acc.height());
    }

    @Test
    @DisplayName("disjoint rectangles union into one bounding box")
    void unionsDisjoint() {
        DamageAccumulator acc = new DamageAccumulator();
        acc.add(0, 0, 10, 10);     // covers [0..9, 0..9]
        acc.add(50, 60, 5, 5);     // covers [50..54, 60..64]

        assertEquals(2, acc.rectCount());
        assertEquals(0, acc.x());
        assertEquals(0, acc.y());
        assertEquals(55, acc.width());   // 0..54
        assertEquals(65, acc.height());  // 0..64
    }

    @Test
    @DisplayName("overlapping rectangles union tightly")
    void unionsOverlapping() {
        DamageAccumulator acc = new DamageAccumulator();
        acc.add(4, 4, 8, 8);   // [4..11, 4..11]
        acc.add(8, 8, 8, 8);   // [8..15, 8..15]

        assertEquals(4, acc.x());
        assertEquals(4, acc.y());
        assertEquals(12, acc.width());   // 4..15
        assertEquals(12, acc.height());
    }

    @Test
    @DisplayName("empty and negative-area rectangles are ignored")
    void ignoresEmpty() {
        DamageAccumulator acc = new DamageAccumulator();
        acc.add(0, 0, 0, 10);
        acc.add(0, 0, 10, 0);
        acc.add(0, 0, -1, 10);
        acc.add(0, 0, 10, -1);

        assertTrue(acc.isEmpty());
        assertEquals(0, acc.rectCount());
        assertNull(acc.drain());
    }

    @Test
    @DisplayName("drain returns the region, its rect count, and clears the accumulator")
    void drainClears() {
        DamageAccumulator acc = new DamageAccumulator();
        acc.add(1, 2, 3, 4);
        acc.add(10, 10, 1, 1);

        Region r = acc.drain();
        assertEquals(1, r.x());
        assertEquals(2, r.y());
        assertEquals(10, r.width());   // 1..10
        assertEquals(9, r.height());   // 2..10
        assertEquals(2, r.rectCount());
        assertEquals(10L * 9L, r.area());

        assertTrue(acc.isEmpty());
        assertEquals(0, acc.rectCount());
        assertNull(acc.drain());
    }

    @Test
    @DisplayName("reset clears the region and the rect count")
    void resetClears() {
        DamageAccumulator acc = new DamageAccumulator();
        acc.add(1, 2, 3, 4);
        acc.reset();
        assertTrue(acc.isEmpty());
        assertEquals(0, acc.rectCount());
        assertNull(acc.drain());
    }

    @Test
    @DisplayName("Region equals/hashCode/toString behave by value")
    void regionValueSemantics() {
        DamageAccumulator a = new DamageAccumulator();
        a.add(0, 0, 4, 4);
        DamageAccumulator b = new DamageAccumulator();
        b.add(0, 0, 4, 4);

        Region ra = a.drain();
        Region rb = b.drain();
        assertEquals(ra, rb);
        assertEquals(ra.hashCode(), rb.hashCode());
        assertEquals(ra, ra);
        assertNotEquals(ra, null);
        assertNotEquals(ra, "not a region");
        assertTrue(ra.toString().contains("Region"));
    }
}
