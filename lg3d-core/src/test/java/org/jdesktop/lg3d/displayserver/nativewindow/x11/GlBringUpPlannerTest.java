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

import org.jdesktop.lg3d.displayserver.nativewindow.x11.GlBringUpPlanner.Probe;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.GlBringUpPlanner.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link GlBringUpPlanner} — the pure Phase F GL/DRI3
 * bring-up decision. It is a static function over a {@link Probe} value with no
 * Display, GL context or reflection, so every verdict branch, the remediation
 * text and the own-window-id resolution are pinned directly.
 */
class GlBringUpPlannerTest {

    @Test
    @DisplayName("hardware GLX + DRI3 with a resolved own window is READY")
    void readyWhenHardwareAndOwnWindow() {
        Probe p = new Probe(true, true, true, 0x1234L);
        assertEquals(Verdict.READY, GlBringUpPlanner.plan(p));
        assertTrue(p.hardwareAccelerated());
        assertEquals("", GlBringUpPlanner.remediation(Verdict.READY));
    }

    @Test
    @DisplayName("no GLX aborts regardless of anything else")
    void abortsWithoutGlx() {
        assertEquals(Verdict.ABORT, GlBringUpPlanner.plan(new Probe(false, true, true, 0x1L)));
        assertEquals(Verdict.ABORT, GlBringUpPlanner.plan(new Probe(false, false, false, -1L)));
        assertEquals(Verdict.ABORT, GlBringUpPlanner.plan(null));
        assertFalse(GlBringUpPlanner.remediation(Verdict.ABORT).isEmpty());
    }

    @Test
    @DisplayName("an unresolved own window id must be pinned, even with good GL")
    void pinOwnWindowIdTakesPrecedence() {
        assertEquals(Verdict.PIN_OWN_WINDOW_ID,
            GlBringUpPlanner.plan(new Probe(true, true, true, 0L)));
        assertEquals(Verdict.PIN_OWN_WINDOW_ID,
            GlBringUpPlanner.plan(new Probe(true, true, true, -1L)));
        assertTrue(GlBringUpPlanner.remediation(Verdict.PIN_OWN_WINDOW_ID)
            .contains("ownwindowid"));
    }

    @Test
    @DisplayName("GLX without direct rendering or DRI3 is SOFTWARE_ONLY")
    void softwareOnlyWithoutAcceleration() {
        assertEquals(Verdict.SOFTWARE_ONLY,
            GlBringUpPlanner.plan(new Probe(true, false, true, 0x1L)));
        assertEquals(Verdict.SOFTWARE_ONLY,
            GlBringUpPlanner.plan(new Probe(true, true, false, 0x1L)));
        assertEquals(Verdict.SOFTWARE_ONLY,
            GlBringUpPlanner.plan(new Probe(true, false, false, 0x1L)));
        assertFalse(GlBringUpPlanner.remediation(Verdict.SOFTWARE_ONLY).isEmpty());
    }

    @Test
    @DisplayName("Probe accessors report the constructor inputs")
    void probeAccessors() {
        Probe p = new Probe(true, false, true, 42L);
        assertTrue(p.glxPresent());
        assertFalse(p.directRendering());
        assertTrue(p.dri3Present());
        assertEquals(42L, p.ownWindowId());
        assertFalse(p.hardwareAccelerated());
    }

    @Test
    @DisplayName("remediation is null-safe and non-null for every verdict")
    void remediationNullSafe() {
        assertEquals("", GlBringUpPlanner.remediation(null));
        for (Verdict v : Verdict.values()) {
            assertTrue(GlBringUpPlanner.remediation(v) != null);
        }
    }

    // ---- resolveOwnWindowId -------------------------------------------

    @Test
    @DisplayName("a valid decimal or hex override wins over the discovered id")
    void overrideWins() {
        assertEquals(1234L, GlBringUpPlanner.resolveOwnWindowId("1234", 99L));
        assertEquals(0x10L, GlBringUpPlanner.resolveOwnWindowId("0x10", 99L));
        assertEquals(7L, GlBringUpPlanner.resolveOwnWindowId("  7  ", 99L));
    }

    @Test
    @DisplayName("a blank/invalid/non-positive override falls back to discovery")
    void invalidOverrideFallsBack() {
        assertEquals(99L, GlBringUpPlanner.resolveOwnWindowId(null, 99L));
        assertEquals(99L, GlBringUpPlanner.resolveOwnWindowId("   ", 99L));
        assertEquals(99L, GlBringUpPlanner.resolveOwnWindowId("not-a-number", 99L));
        assertEquals(99L, GlBringUpPlanner.resolveOwnWindowId("0", 99L));
        assertEquals(99L, GlBringUpPlanner.resolveOwnWindowId("-5", 99L));
    }

    @Test
    @DisplayName("no override and no discovery yields -1")
    void nothingYieldsMinusOne() {
        assertEquals(-1L, GlBringUpPlanner.resolveOwnWindowId(null, 0L));
        assertEquals(-1L, GlBringUpPlanner.resolveOwnWindowId(null, -1L));
        assertEquals(-1L, GlBringUpPlanner.resolveOwnWindowId("bogus", 0L));
    }
}
