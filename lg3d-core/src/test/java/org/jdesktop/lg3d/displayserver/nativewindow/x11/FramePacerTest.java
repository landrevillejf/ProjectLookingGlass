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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link FramePacer} — the pure present throttle (Phase E).
 * Every method takes an explicit {@code nowMillis}, so the pacing decision is
 * fully deterministic with no clock, X or Java 3D state.
 */
class FramePacerTest {

    @Test
    @DisplayName("a negative interval is clamped to zero")
    void clampsNegativeInterval() {
        assertEquals(0L, new FramePacer(-100L).minIntervalMillis());
    }

    @Test
    @DisplayName("a fresh pacer has never presented")
    void freshNeverPresented() {
        FramePacer p = new FramePacer(16L);
        assertTrue(p.neverPresented());
        assertEquals(Long.MIN_VALUE, p.lastPresentMillis());
    }

    @Test
    @DisplayName("a zero interval is always due")
    void zeroIntervalAlwaysDue() {
        FramePacer p = new FramePacer(0L);
        assertTrue(p.isDue(0L));
        p.markPresented(0L);
        assertTrue(p.isDue(0L));
        assertTrue(p.isDue(1L));
    }

    @Test
    @DisplayName("the first present is always due regardless of interval")
    void firstPresentAlwaysDue() {
        FramePacer p = new FramePacer(1000L);
        assertTrue(p.isDue(0L));
        assertTrue(p.tryAcquire(0L));
        assertFalse(p.neverPresented());
    }

    @Test
    @DisplayName("a present before the interval elapses is not due")
    void throttlesWithinInterval() {
        FramePacer p = new FramePacer(16L);
        assertTrue(p.tryAcquire(100L));   // first, due
        assertFalse(p.isDue(110L));       // only 10ms elapsed
        assertFalse(p.tryAcquire(110L));  // refused
        assertEquals(100L, p.lastPresentMillis()); // unchanged by a refusal
    }

    @Test
    @DisplayName("a present at or after the interval is due")
    void dueAtInterval() {
        FramePacer p = new FramePacer(16L);
        assertTrue(p.tryAcquire(100L));
        assertFalse(p.isDue(115L));   // 15ms < 16ms
        assertTrue(p.isDue(116L));    // exactly 16ms
        assertTrue(p.tryAcquire(116L));
        assertEquals(116L, p.lastPresentMillis());
    }

    @Test
    @DisplayName("markPresented resets the throttle window")
    void markPresentedResets() {
        FramePacer p = new FramePacer(10L);
        p.markPresented(50L);
        assertFalse(p.isDue(55L));
        assertTrue(p.isDue(60L));
    }

    @Test
    @DisplayName("tryAcquire returns false and does not advance the timestamp when refused")
    void tryAcquireRefusalIsSideEffectFree() {
        FramePacer p = new FramePacer(100L);
        assertTrue(p.tryAcquire(0L));
        assertFalse(p.tryAcquire(50L));
        assertFalse(p.tryAcquire(99L));
        assertTrue(p.tryAcquire(100L));
        assertEquals(100L, p.lastPresentMillis());
    }
}
