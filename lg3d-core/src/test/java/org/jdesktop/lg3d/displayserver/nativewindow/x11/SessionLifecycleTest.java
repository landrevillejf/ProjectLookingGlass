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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.SessionLifecycle.Event;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.SessionLifecycle.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link SessionLifecycle} — the pure Phase G session state
 * machine. It holds no Display, process or thread, so every legal transition,
 * every refused illegal transition, the crash counter and the recovery budget
 * are pinned directly.
 */
class SessionLifecycleTest {

    @Test
    @DisplayName("a fresh lifecycle starts in INIT and is not running")
    void freshIsInit() {
        SessionLifecycle s = new SessionLifecycle();
        assertEquals(State.INIT, s.state());
        assertFalse(s.isRunning());
        assertFalse(s.isStopped());
        assertEquals(0, s.crashCount());
        assertEquals(3, s.maxRecoveries());
    }

    @Test
    @DisplayName("the happy path runs INIT -> STARTING -> RUNNING -> SHUTTING_DOWN -> STOPPED")
    void happyPath() {
        SessionLifecycle s = new SessionLifecycle();
        assertTrue(s.onEvent(Event.START));
        assertEquals(State.STARTING, s.state());
        assertTrue(s.onEvent(Event.READY));
        assertEquals(State.RUNNING, s.state());
        assertTrue(s.isRunning());
        assertTrue(s.onEvent(Event.SHUTDOWN_REQUEST));
        assertEquals(State.SHUTTING_DOWN, s.state());
        assertTrue(s.onEvent(Event.SHUTDOWN_COMPLETE));
        assertEquals(State.STOPPED, s.state());
        assertTrue(s.isStopped());
    }

    @Test
    @DisplayName("a null event is refused")
    void nullEventRefused() {
        SessionLifecycle s = new SessionLifecycle();
        assertFalse(s.onEvent(null));
        assertEquals(State.INIT, s.state());
    }

    @Test
    @DisplayName("illegal transitions are refused and leave the state unchanged")
    void illegalTransitionsRefused() {
        SessionLifecycle s = new SessionLifecycle();
        assertFalse(s.onEvent(Event.READY));   // INIT cannot go READY
        assertEquals(State.INIT, s.state());
        assertFalse(s.onEvent(Event.SHUTDOWN_COMPLETE));
        assertEquals(State.INIT, s.state());

        s.onEvent(Event.START);
        assertFalse(s.onEvent(Event.START));   // STARTING cannot re-START
        assertEquals(State.STARTING, s.state());

        s.onEvent(Event.READY);
        assertFalse(s.onEvent(Event.READY));   // RUNNING cannot re-READY
        assertFalse(s.onEvent(Event.START));
        assertEquals(State.RUNNING, s.state());
    }

    @Test
    @DisplayName("a stopped session only restarts")
    void stoppedOnlyRestarts() {
        SessionLifecycle s = new SessionLifecycle();
        s.onEvent(Event.START);
        s.onEvent(Event.READY);
        s.onEvent(Event.SHUTDOWN_REQUEST);
        s.onEvent(Event.SHUTDOWN_COMPLETE);
        assertEquals(State.STOPPED, s.state());

        assertFalse(s.onEvent(Event.START));   // refused
        assertEquals(State.STOPPED, s.state());
        assertTrue(s.onEvent(Event.RESTART));
        assertEquals(State.STARTING, s.state());
    }

    @Test
    @DisplayName("FAILURE from any live state crashes and increments the crash count")
    void failureCrashes() {
        SessionLifecycle s = new SessionLifecycle();
        assertTrue(s.onEvent(Event.FAILURE));  // INIT -> CRASHED
        assertEquals(State.CRASHED, s.state());
        assertEquals(1, s.crashCount());

        SessionLifecycle r = new SessionLifecycle();
        r.onEvent(Event.START);
        r.onEvent(Event.READY);
        assertTrue(r.onEvent(Event.FAILURE));  // RUNNING -> CRASHED
        assertEquals(State.CRASHED, r.state());
        assertEquals(1, r.crashCount());
    }

    @Test
    @DisplayName("recovery is allowed up to the budget, then refused")
    void recoveryBudget() {
        SessionLifecycle s = new SessionLifecycle(2);
        assertEquals(2, s.maxRecoveries());

        // Crash 1 -> recover -> ready.
        s.onEvent(Event.FAILURE);
        assertTrue(s.canRecover());
        assertTrue(s.onEvent(Event.RECOVER));
        assertEquals(State.RECOVERING, s.state());
        assertTrue(s.onEvent(Event.READY));
        assertEquals(State.RUNNING, s.state());

        // Crash 2 -> recover -> ready.
        s.onEvent(Event.FAILURE);
        assertEquals(2, s.crashCount());
        assertTrue(s.canRecover());
        assertTrue(s.onEvent(Event.RECOVER));
        s.onEvent(Event.READY);

        // Crash 3 -> budget exhausted (crashCount 3 > maxRecoveries 2).
        s.onEvent(Event.FAILURE);
        assertEquals(3, s.crashCount());
        assertFalse(s.canRecover());
        assertFalse(s.onEvent(Event.RECOVER));
        assertEquals(State.CRASHED, s.state());
        // A crashed session can still be shut down cleanly.
        assertTrue(s.onEvent(Event.SHUTDOWN_REQUEST));
        assertEquals(State.SHUTTING_DOWN, s.state());
    }

    @Test
    @DisplayName("a zero recovery budget refuses the first recovery")
    void zeroBudgetNoRecovery() {
        SessionLifecycle s = new SessionLifecycle(0);
        s.onEvent(Event.FAILURE);
        assertFalse(s.canRecover());
        assertFalse(s.onEvent(Event.RECOVER));
        assertEquals(State.CRASHED, s.state());
    }

    @Test
    @DisplayName("a negative recovery budget is clamped to zero")
    void negativeBudgetClamped() {
        assertEquals(0, new SessionLifecycle(-5).maxRecoveries());
    }

    @Test
    @DisplayName("RECOVERING can START, go READY, or be shut down")
    void recoveringTransitions() {
        SessionLifecycle s = new SessionLifecycle();
        s.onEvent(Event.FAILURE);
        s.onEvent(Event.RECOVER);
        assertEquals(State.RECOVERING, s.state());
        assertTrue(s.onEvent(Event.START));
        assertEquals(State.STARTING, s.state());

        SessionLifecycle t = new SessionLifecycle();
        t.onEvent(Event.FAILURE);
        t.onEvent(Event.RECOVER);
        assertTrue(t.onEvent(Event.SHUTDOWN_REQUEST));
        assertEquals(State.SHUTTING_DOWN, t.state());
    }

    @Test
    @DisplayName("STARTING can be shut down before it is ready")
    void startingCanShutdown() {
        SessionLifecycle s = new SessionLifecycle();
        s.onEvent(Event.START);
        assertTrue(s.onEvent(Event.SHUTDOWN_REQUEST));
        assertEquals(State.SHUTTING_DOWN, s.state());
    }

    @Test
    @DisplayName("shutdownSteps lists the ordered teardown and is unmodifiable")
    void shutdownStepsOrdered() {
        List<String> steps = SessionLifecycle.shutdownSteps();
        assertEquals(List.of("destroy-damage", "clear-maps",
            "unredirect-subwindows", "flush-display"), steps);
        assertThrows(UnsupportedOperationException.class, () -> steps.add("x"));
    }
}
