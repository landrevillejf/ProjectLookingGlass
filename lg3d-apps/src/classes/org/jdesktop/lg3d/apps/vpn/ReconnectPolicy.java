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
package org.jdesktop.lg3d.apps.vpn;

/**
 * The pure auto-reconnect backoff policy. When a tunnel that was up drops
 * unexpectedly and its profile is marked auto-connect, {@link VpnPanel} retries the
 * connection on a schedule derived here: an exponential backoff (each attempt waits
 * twice the previous) clamped to a ceiling, with a hard cap on the number of
 * attempts so a permanently-down gateway cannot spin forever.
 *
 * <p>Every method is pure and side-effect free - the panel owns the timer and the
 * actual reconnect, this class only decides <em>whether</em> to retry and
 * <em>how long</em> to wait - so the whole schedule is unit-testable headless with
 * no clock, no thread and no tunnel.</p>
 */
public final class ReconnectPolicy {

    /** Default number of reconnect attempts before giving up. */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    /** Default delay before the first retry, in milliseconds. */
    public static final long DEFAULT_INITIAL_DELAY_MS = 2_000L;
    /** Default ceiling a backed-off delay never exceeds, in milliseconds. */
    public static final long DEFAULT_MAX_DELAY_MS = 60_000L;

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final int maxAttempts;

    /**
     * Builds a policy, normalising nonsense inputs so a hand-tuned policy can never
     * produce a negative delay or an infinite retry loop.
     *
     * @param initialDelayMs the delay before the first retry (negatives clamp to 0)
     * @param maxDelayMs     the delay ceiling (clamped up to at least the initial)
     * @param maxAttempts    the retry cap (negatives clamp to 0 - never retry)
     */
    public ReconnectPolicy(long initialDelayMs, long maxDelayMs, int maxAttempts) {
        this.initialDelayMs = Math.max(0L, initialDelayMs);
        this.maxDelayMs = Math.max(this.initialDelayMs, maxDelayMs);
        this.maxAttempts = Math.max(0, maxAttempts);
    }

    /** The default policy: 2 s initial, doubling to a 60 s ceiling, 5 attempts. */
    public static ReconnectPolicy defaultPolicy() {
        return new ReconnectPolicy(DEFAULT_INITIAL_DELAY_MS, DEFAULT_MAX_DELAY_MS,
                DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * The delay before the given (1-based) attempt: the initial delay for the first,
     * doubling each attempt thereafter and never exceeding the ceiling. Overflow-safe
     * - once the doubling would pass the ceiling (or wrap) it returns the ceiling.
     *
     * @param attempt the 1-based attempt number (values &lt; 1 are treated as 1)
     * @return the delay in milliseconds, never negative
     */
    public long delayForAttempt(int attempt) {
        if (attempt <= 1) {
            return initialDelayMs;
        }
        long delay = initialDelayMs;
        for (int i = 1; i < attempt && delay < maxDelayMs; i++) {
            long next = delay * 2;
            if (next <= delay) {
                // Overflow: the ceiling is the only sensible answer.
                return maxDelayMs;
            }
            delay = next;
        }
        return Math.min(delay, maxDelayMs);
    }

    /**
     * Whether another attempt is allowed once {@code attemptsMade} have already been
     * made. A negative count is treated as "no attempts made" but never retried, so a
     * corrupted counter cannot loop.
     *
     * @param attemptsMade the number of attempts already made (0-based)
     * @return true while {@code 0 <= attemptsMade < maxAttempts}
     */
    public boolean shouldRetry(int attemptsMade) {
        return attemptsMade >= 0 && attemptsMade < maxAttempts;
    }

    /** The delay before the first retry, in milliseconds. */
    public long initialDelayMs() {
        return initialDelayMs;
    }

    /** The delay ceiling, in milliseconds. */
    public long maxDelayMs() {
        return maxDelayMs;
    }

    /** The maximum number of reconnect attempts. */
    public int maxAttempts() {
        return maxAttempts;
    }
}
