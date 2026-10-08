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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ReconnectPolicy}: the pure exponential backoff, the ceiling clamp,
 * the retry cap and the constructor's normalisation of nonsense inputs. No clock,
 * thread or tunnel is involved, so this runs headless.
 */
class ReconnectPolicyTest {

    @Test
    @DisplayName("the default policy is 2s initial, 60s ceiling, 5 attempts")
    void defaultPolicy() {
        ReconnectPolicy p = ReconnectPolicy.defaultPolicy();
        assertEquals(2_000L, p.initialDelayMs());
        assertEquals(60_000L, p.maxDelayMs());
        assertEquals(5, p.maxAttempts());
        assertEquals(ReconnectPolicy.DEFAULT_INITIAL_DELAY_MS, p.initialDelayMs());
        assertEquals(ReconnectPolicy.DEFAULT_MAX_DELAY_MS, p.maxDelayMs());
        assertEquals(ReconnectPolicy.DEFAULT_MAX_ATTEMPTS, p.maxAttempts());
    }

    @Test
    @DisplayName("the backoff doubles each attempt and clamps at the ceiling")
    void exponentialBackoff() {
        ReconnectPolicy p = ReconnectPolicy.defaultPolicy();
        assertEquals(2_000L, p.delayForAttempt(1));
        assertEquals(4_000L, p.delayForAttempt(2));
        assertEquals(8_000L, p.delayForAttempt(3));
        assertEquals(16_000L, p.delayForAttempt(4));
        assertEquals(32_000L, p.delayForAttempt(5));
        assertEquals(60_000L, p.delayForAttempt(6), "64s would exceed the ceiling");
        assertEquals(60_000L, p.delayForAttempt(20), "stays clamped");
    }

    @Test
    @DisplayName("an attempt below 1 is treated as the first")
    void nonPositiveAttemptIsFirst() {
        ReconnectPolicy p = ReconnectPolicy.defaultPolicy();
        assertEquals(2_000L, p.delayForAttempt(0));
        assertEquals(2_000L, p.delayForAttempt(-5));
    }

    @Test
    @DisplayName("shouldRetry is true below the cap and false at or above it")
    void shouldRetryRespectsCap() {
        ReconnectPolicy p = ReconnectPolicy.defaultPolicy();
        assertTrue(p.shouldRetry(0));
        assertTrue(p.shouldRetry(4));
        assertFalse(p.shouldRetry(5), "the cap is 5 attempts (0..4)");
        assertFalse(p.shouldRetry(6));
        assertFalse(p.shouldRetry(-1), "a corrupted negative counter never retries");
    }

    @Test
    @DisplayName("the constructor clamps negative and inverted inputs")
    void constructorNormalises() {
        ReconnectPolicy zero = new ReconnectPolicy(-5L, -10L, -3);
        assertEquals(0L, zero.initialDelayMs());
        assertEquals(0L, zero.maxDelayMs(), "the ceiling is at least the initial delay");
        assertEquals(0, zero.maxAttempts());
        assertEquals(0L, zero.delayForAttempt(1));
        assertFalse(zero.shouldRetry(0), "a zero cap never retries");

        ReconnectPolicy inverted = new ReconnectPolicy(1_000L, 500L, 3);
        assertEquals(1_000L, inverted.maxDelayMs(), "a ceiling below the initial is raised to it");
        assertEquals(1_000L, inverted.delayForAttempt(2), "clamped to the ceiling");
        assertTrue(inverted.shouldRetry(2));
        assertFalse(inverted.shouldRetry(3));
    }

    @Test
    @DisplayName("a huge initial delay cannot overflow the doubling")
    void backoffIsOverflowSafe() {
        long huge = Long.MAX_VALUE / 2 + 1;
        ReconnectPolicy p = new ReconnectPolicy(huge, Long.MAX_VALUE, 4);
        assertEquals(huge, p.delayForAttempt(1));
        assertEquals(Long.MAX_VALUE, p.delayForAttempt(3), "doubling saturates, never wraps negative");
        assertTrue(p.delayForAttempt(50) >= 0L);
    }
}
