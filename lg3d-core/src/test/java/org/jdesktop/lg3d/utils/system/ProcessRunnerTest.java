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

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the {@link ProcessRunner} line-sink seam: every stdout
 * line reaches the sink as it arrives (in order) while the buffered result
 * keeps working exactly as before, and a null sink is a no-op rather than a
 * crash. Uses {@code /bin/sh} so no external tool is required.
 */
class ProcessRunnerTest {

    @Test
    @DisplayName("line sink receives each stdout line in order")
    void lineSinkStreamsEachStdoutLine() {
        Queue<String> seen = new ConcurrentLinkedQueue<>();
        ProcessRunner.Result r = ProcessRunner.run(
                List.of("/bin/sh", "-c", "printf 'alpha\\nbeta\\n'"),
                null, 10, TimeUnit.SECONDS, null, seen::add);
        assertTrue(r.isStarted(), "sh should start");
        assertTrue(r.isSuccess(), "sh should exit 0");
        assertEquals(List.of("alpha", "beta"), List.copyOf(seen), "sink sees every line in order");
        assertTrue(r.getStdout().contains("alpha") && r.getStdout().contains("beta"),
                "buffered stdout still complete");
    }

    @Test
    @DisplayName("null sink keeps the buffered result intact")
    void bufferedResultWithoutSink() {
        ProcessRunner.Result r = ProcessRunner.run(
                List.of("/bin/sh", "-c", "printf 'gamma\\n'"),
                null, 10, TimeUnit.SECONDS, null, null);
        assertTrue(r.isStarted(), "sh should start");
        assertTrue(r.isSuccess(), "sh should exit 0");
        assertEquals("gamma\n", r.getStdout(), "stdout buffered without a sink");
    }

    @Test
    @DisplayName("empty command fails without starting a process")
    void emptyCommandFailsWithoutStarting() {
        Queue<String> seen = new ConcurrentLinkedQueue<>();
        ProcessRunner.Result r = ProcessRunner.run(
                List.of(), null, 10, TimeUnit.SECONDS, null, seen::add);
        assertFalse(r.isStarted(), "nothing should start for an empty command");
        assertTrue(seen.isEmpty(), "the sink must not be called");
    }
}
