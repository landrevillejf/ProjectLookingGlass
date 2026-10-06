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

/**
 * Throttles how often a composited window's accumulated damage is read back and
 * presented, so unchanged regions are not re-read and re-uploaded more often
 * than the target frame interval (Phase E: frame pacing).
 *
 * <p>Pure and clock-injected: every method takes the current time in
 * milliseconds rather than reading a clock itself, so the pacing decision is
 * deterministic and unit-testable headlessly. A {@code minIntervalMillis} of
 * zero means "always due" — the behaviour
 * {@link CompositedWindowPipeline} uses by default, presenting each damage
 * report synchronously.</p>
 *
 * @see CompositedWindowPipeline
 * @see DamageAccumulator
 */
public final class FramePacer {

    /** Sentinel for "nothing has been presented yet". */
    private static final long NEVER = Long.MIN_VALUE;

    private final long minIntervalMillis;
    private long lastPresentMillis = NEVER;

    /**
     * @param minIntervalMillis the minimum spacing between presents; a negative
     *                          value is clamped to zero (always due)
     */
    public FramePacer(long minIntervalMillis) {
        this.minIntervalMillis = Math.max(0L, minIntervalMillis);
    }

    /** The configured minimum present interval, in milliseconds. */
    public long minIntervalMillis() {
        return minIntervalMillis;
    }

    /** True if nothing has been presented yet. */
    public boolean neverPresented() {
        return lastPresentMillis == NEVER;
    }

    /** The time of the last present, or {@link Long#MIN_VALUE} if never. */
    public long lastPresentMillis() {
        return lastPresentMillis;
    }

    /**
     * True if a present is due at {@code nowMillis}: always when the interval is
     * zero, when nothing has been presented yet, or when at least
     * {@link #minIntervalMillis} has elapsed since the last present.
     *
     * @param nowMillis the current time in milliseconds
     * @return whether a present should happen now
     */
    public boolean isDue(long nowMillis) {
        if (minIntervalMillis <= 0L || neverPresented()) {
            return true;
        }
        return nowMillis - lastPresentMillis >= minIntervalMillis;
    }

    /**
     * Records that a present happened at {@code nowMillis}.
     *
     * @param nowMillis the current time in milliseconds
     */
    public void markPresented(long nowMillis) {
        lastPresentMillis = nowMillis;
    }

    /**
     * If a present is due at {@code nowMillis}, marks it as presented and
     * returns true; otherwise returns false and leaves the last-present time
     * unchanged (the caller should defer and keep accumulating damage).
     *
     * @param nowMillis the current time in milliseconds
     * @return whether the caller may present now
     */
    public boolean tryAcquire(long nowMillis) {
        if (isDue(nowMillis)) {
            markPresented(nowMillis);
            return true;
        }
        return false;
    }
}
