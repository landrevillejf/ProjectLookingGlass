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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.displayserver.desktop2d.Notification.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link DoNotDisturb}: the on/off and timed-deadline state machine, the
 * suppression matrix (errors always pass), the toggle, and the persisted-value
 * accessors. Time is injected, so the suite is deterministic and headless.
 */
class DoNotDisturbTest {

    private static final long NOW = 1_000_000L;

    @Test
    @DisplayName("a fresh DND is off and suppresses nothing")
    void startsOff() {
        DoNotDisturb dnd = new DoNotDisturb();
        assertFalse(dnd.active(NOW));
        assertFalse(dnd.isEnabled());
        assertEquals(0L, dnd.untilMillis());
        assertFalse(dnd.shouldSuppress(Kind.INFO, NOW));
    }

    @Test
    @DisplayName("enable() suppresses info/warning but never errors")
    void enableSuppressesAllButErrors() {
        DoNotDisturb dnd = new DoNotDisturb();
        dnd.enable();
        assertTrue(dnd.active(NOW));
        assertTrue(dnd.shouldSuppress(Kind.INFO, NOW));
        assertTrue(dnd.shouldSuppress(Kind.WARNING, NOW));
        assertFalse(dnd.shouldSuppress(Kind.ERROR, NOW), "errors always surface");
    }

    @Test
    @DisplayName("disable() stops suppression")
    void disableStopsSuppression() {
        DoNotDisturb dnd = new DoNotDisturb();
        dnd.enable();
        dnd.disable();
        assertFalse(dnd.active(NOW));
        assertFalse(dnd.shouldSuppress(Kind.INFO, NOW));
        assertEquals(0L, dnd.untilMillis(), "disabling clears the deadline");
    }

    @Test
    @DisplayName("a timed DND is active before the deadline and lapses after")
    void timedDeadlineLapses() {
        DoNotDisturb dnd = new DoNotDisturb();
        dnd.enableFor(DoNotDisturb.ONE_HOUR_MILLIS, NOW);
        assertTrue(dnd.active(NOW));
        assertTrue(dnd.active(NOW + DoNotDisturb.ONE_HOUR_MILLIS - 1));
        assertFalse(dnd.active(NOW + DoNotDisturb.ONE_HOUR_MILLIS), "expired at the deadline");
        assertFalse(dnd.shouldSuppress(Kind.INFO, NOW + DoNotDisturb.ONE_HOUR_MILLIS));
        assertEquals(NOW + DoNotDisturb.ONE_HOUR_MILLIS, dnd.untilMillis());
    }

    @Test
    @DisplayName("a non-positive duration falls back to indefinite")
    void nonPositiveDurationIsIndefinite() {
        DoNotDisturb dnd = new DoNotDisturb();
        dnd.enableFor(0L, NOW);
        assertTrue(dnd.active(NOW));
        assertEquals(0L, dnd.untilMillis(), "indefinite carries no deadline");
        assertTrue(dnd.active(NOW + 999_999_999L));
    }

    @Test
    @DisplayName("toggle flips on then off")
    void toggleFlips() {
        DoNotDisturb dnd = new DoNotDisturb();
        dnd.toggle(NOW);
        assertTrue(dnd.active(NOW));
        dnd.toggle(NOW);
        assertFalse(dnd.active(NOW));
    }

    @Test
    @DisplayName("a restored deadline already past comes back inactive")
    void restoredExpiredDeadlineIsInactive() {
        DoNotDisturb dnd = new DoNotDisturb(true, NOW - 10L);
        assertFalse(dnd.active(NOW), "an expired persisted deadline must not stick");
        // The raw flag still reports what was persisted, for the config write-back.
        assertTrue(dnd.isEnabled());
    }

    @Test
    @DisplayName("a restored indefinite state is active")
    void restoredIndefiniteIsActive() {
        DoNotDisturb dnd = new DoNotDisturb(true, 0L);
        assertTrue(dnd.active(NOW));
        assertTrue(dnd.shouldSuppress(Kind.WARNING, NOW));
    }

    @Test
    @DisplayName("a restore constructed off carries no deadline")
    void restoredOffIsClean() {
        DoNotDisturb dnd = new DoNotDisturb(false, 12345L);
        assertFalse(dnd.active(NOW));
        assertEquals(0L, dnd.untilMillis(), "an off state ignores any leftover deadline");
    }

    @Test
    @DisplayName("isSuppressible exempts only ERROR")
    void isSuppressibleMatrix() {
        assertTrue(DoNotDisturb.isSuppressible(Kind.INFO));
        assertTrue(DoNotDisturb.isSuppressible(Kind.WARNING));
        assertFalse(DoNotDisturb.isSuppressible(Kind.ERROR));
        assertTrue(DoNotDisturb.isSuppressible(null), "a null kind is treated as ordinary");
    }

    @Test
    @DisplayName("listeners fire on a real change but not on a no-op")
    void listenersFireOnChange() {
        DoNotDisturb dnd = new DoNotDisturb();
        int[] count = {0};
        Runnable listener = () -> count[0]++;
        dnd.addListener(listener);
        dnd.enable();
        assertEquals(1, count[0]);
        dnd.enable();               // no state change
        assertEquals(1, count[0], "a redundant enable must not re-fire");
        dnd.disable();
        assertEquals(2, count[0]);
        dnd.removeListener(listener);
        dnd.enable();
        assertEquals(2, count[0], "a removed listener no longer fires");
    }
}
