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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the fails-closed {@link NetworkCut} seam: the flag
 * flips, the idempotence of re-setting the same value, the listener fan-out
 * (only on actual flips, isolated against a throwing listener) and the labels.
 */
class NetworkCutTest {

    @AfterEach
    void restore() {
        // The flag is global JVM state; leave it lowered for other suites.
        NetworkCut.restore();
    }

    @Test
    @DisplayName("cut raises the flag and restore lowers it")
    void cutAndRestore() {
        NetworkCut.restore();
        assertFalse(NetworkCut.isCut());
        NetworkCut.cut();
        assertTrue(NetworkCut.isCut(), "a cut network must be visible to clients");
        NetworkCut.restore();
        assertFalse(NetworkCut.isCut());
    }

    @Test
    @DisplayName("re-cutting or re-restoring is idempotent")
    void idempotentFlips() {
        NetworkCut.restore();
        NetworkCut.cut();
        NetworkCut.cut();
        assertTrue(NetworkCut.isCut());
        NetworkCut.restore();
        NetworkCut.restore();
        assertFalse(NetworkCut.isCut());
    }

    @Test
    @DisplayName("listeners fire once per actual flip, never on idempotent re-sets")
    void listenerFiresOnFlipOnly() {
        NetworkCut.restore();
        AtomicInteger flips = new AtomicInteger();
        Runnable listener = flips::incrementAndGet;
        NetworkCut.addListener(listener);
        try {
            NetworkCut.cut();
            NetworkCut.cut();
            assertEquals(1, flips.get(), "the second cut() is a no-op");
            NetworkCut.restore();
            NetworkCut.restore();
            assertEquals(2, flips.get(), "the second restore() is a no-op");
        } finally {
            NetworkCut.removeListener(listener);
        }
        NetworkCut.cut();
        assertEquals(2, flips.get(), "a removed listener is no longer notified");
    }

    @Test
    @DisplayName("a throwing listener neither masks the flip nor starves the others")
    void throwingListenerIsolated() {
        NetworkCut.restore();
        AtomicInteger reached = new AtomicInteger();
        Runnable bad = () -> {
            throw new IllegalStateException("boom");
        };
        NetworkCut.addListener(bad);
        NetworkCut.addListener(reached::incrementAndGet);
        try {
            NetworkCut.cut();
            assertEquals(1, reached.get(),
                    "the healthy listener still runs after the throwing one");
            assertTrue(NetworkCut.isCut(), "the flag flip is not masked");
        } finally {
            NetworkCut.removeListener(bad);
            NetworkCut.restore();
            NetworkCut.restore();
        }
    }

    @Test
    @DisplayName("null listeners are ignored and describe labels both states")
    void nullListenerAndLabels() {
        NetworkCut.addListener(null);
        NetworkCut.removeListener(null);
        assertTrue(NetworkCut.describe(true).toLowerCase().contains("cut"));
        assertFalse(NetworkCut.describe(false).isBlank());
    }
}
