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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The pure state machine for lg3d's life as the sole X session client on a
 * bare-Xorg/LFS host (Phase G): the {@code xinit}/systemd hand-off starts it,
 * it acquires the WM + compositor and runs, a clean logout tears it down in a
 * fixed order, and a crash may be recovered up to a bounded number of times.
 *
 * <p>It holds no {@code gnu.x11.Display}, no process and no thread — just a
 * state, a crash counter and a recovery budget — so the whole transition table
 * (including illegal transitions, which are refused rather than throwing) is
 * unit-testable headlessly. {@link X11Compositor} drives one: it advances to
 * {@link State#RUNNING} once redirection succeeds and through
 * {@link State#SHUTTING_DOWN} to {@link State#STOPPED} on {@code shutdown()}.</p>
 *
 * <p>This models the contract in {@code docs/lfs-x11-contract.md} §1/§3.6 (lg3d
 * as the session, clean shutdown, autostart) and the roadmap's Phase G.</p>
 *
 * @see X11Compositor#shutdown()
 */
public final class SessionLifecycle {

    /** The session states. */
    public enum State {
        /** Constructed, not yet started. */
        INIT,
        /** Start requested; acquiring the WM/compositor. */
        STARTING,
        /** WM + compositor acquired and serving. */
        RUNNING,
        /** Clean shutdown in progress. */
        SHUTTING_DOWN,
        /** Cleanly stopped (terminal unless restarted). */
        STOPPED,
        /** Failed; may be recoverable. */
        CRASHED,
        /** A recovery attempt is re-starting the session. */
        RECOVERING
    }

    /** The events that drive transitions. */
    public enum Event {
        /** Begin acquiring the WM/compositor. */
        START,
        /** Acquisition succeeded; the session is live. */
        READY,
        /** A clean shutdown was requested (logout/systemd stop). */
        SHUTDOWN_REQUEST,
        /** The ordered teardown finished. */
        SHUTDOWN_COMPLETE,
        /** An unrecoverable error occurred in the current state. */
        FAILURE,
        /** Attempt a crash recovery. */
        RECOVER,
        /** Restart a cleanly stopped session. */
        RESTART
    }

    /** The ordered teardown steps a clean shutdown performs. */
    private static final List<String> SHUTDOWN_STEPS =
        Collections.unmodifiableList(Arrays.asList(
            "destroy-damage", "clear-maps", "unredirect-subwindows",
            "flush-display"));

    private final int maxRecoveries;
    private State state = State.INIT;
    private int crashCount;

    /** Creates a lifecycle allowing up to three crash recoveries. */
    public SessionLifecycle() {
        this(3);
    }

    /**
     * @param maxRecoveries the number of crash recoveries to allow before
     *                      {@link #canRecover()} refuses; negative clamps to 0
     */
    public SessionLifecycle(int maxRecoveries) {
        this.maxRecoveries = Math.max(0, maxRecoveries);
    }

    /** The current state. */
    public State state() {
        return state;
    }

    /** How many times the session has crashed. */
    public int crashCount() {
        return crashCount;
    }

    /** The configured recovery budget. */
    public int maxRecoveries() {
        return maxRecoveries;
    }

    /** True when the session is live and serving. */
    public boolean isRunning() {
        return state == State.RUNNING;
    }

    /** True when the session has cleanly stopped. */
    public boolean isStopped() {
        return state == State.STOPPED;
    }

    /** True when another crash recovery is allowed from {@link State#CRASHED}. */
    public boolean canRecover() {
        return state == State.CRASHED && crashCount <= maxRecoveries;
    }

    /**
     * Applies an event. Legal transitions move the state and return true;
     * illegal ones (and a null event) are refused, leaving the state unchanged
     * and returning false. A {@link Event#FAILURE} always moves to
     * {@link State#CRASHED} and increments the crash count.
     *
     * @param event the event to apply
     * @return whether the transition was accepted
     */
    public boolean onEvent(Event event) {
        if (event == null) {
            return false;
        }
        switch (state) {
            case INIT:
                if (event == Event.START) {
                    state = State.STARTING;
                    return true;
                }
                return fail(event);
            case STARTING:
                if (event == Event.READY) {
                    state = State.RUNNING;
                    return true;
                }
                if (event == Event.SHUTDOWN_REQUEST) {
                    state = State.SHUTTING_DOWN;
                    return true;
                }
                return fail(event);
            case RUNNING:
                if (event == Event.SHUTDOWN_REQUEST) {
                    state = State.SHUTTING_DOWN;
                    return true;
                }
                return fail(event);
            case SHUTTING_DOWN:
                if (event == Event.SHUTDOWN_COMPLETE) {
                    state = State.STOPPED;
                    return true;
                }
                return fail(event);
            case CRASHED:
                if (event == Event.RECOVER && canRecover()) {
                    state = State.RECOVERING;
                    return true;
                }
                if (event == Event.SHUTDOWN_REQUEST) {
                    state = State.SHUTTING_DOWN;
                    return true;
                }
                return false;
            case RECOVERING:
                if (event == Event.START) {
                    state = State.STARTING;
                    return true;
                }
                if (event == Event.READY) {
                    state = State.RUNNING;
                    return true;
                }
                if (event == Event.SHUTDOWN_REQUEST) {
                    state = State.SHUTTING_DOWN;
                    return true;
                }
                return fail(event);
            case STOPPED:
                if (event == Event.RESTART) {
                    state = State.STARTING;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * The ordered teardown steps a clean shutdown must perform, matching
     * {@link X11Compositor#shutdown()}. An unmodifiable list.
     *
     * @return the shutdown step names, in order
     */
    public static List<String> shutdownSteps() {
        return SHUTDOWN_STEPS;
    }

    /** Applies a FAILURE event: crash and count it. Returns false only if null. */
    private boolean fail(Event event) {
        if (event == Event.FAILURE) {
            state = State.CRASHED;
            crashCount++;
            return true;
        }
        return false;
    }
}
