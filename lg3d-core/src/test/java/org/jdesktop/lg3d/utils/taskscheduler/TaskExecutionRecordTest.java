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
package org.jdesktop.lg3d.utils.taskscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link TaskExecutionRecord} serialisation and output capping. */
class TaskExecutionRecordTest {

    @Test
    @DisplayName("a record round-trips through encode/decode")
    void roundTrip() {
        TaskExecutionRecord r = new TaskExecutionRecord(
                1_700_000_000_000L, 1234L, TaskExecutionRecord.Status.SUCCESS, 0, "all good");
        TaskExecutionRecord d = TaskExecutionRecord.decode(r.encode());
        assertEquals(r.getStartedAtMillis(), d.getStartedAtMillis());
        assertEquals(r.getDurationMillis(), d.getDurationMillis());
        assertEquals(r.getStatus(), d.getStatus());
        assertEquals(r.getExitCode(), d.getExitCode());
        assertEquals("all good", d.getOutput());
        assertTrue(d.isSuccess());
    }

    @Test
    @DisplayName("control characters and delimiters are stripped from output")
    void stripsControlChars() {
        TaskExecutionRecord r = new TaskExecutionRecord(
                1L, 1L, TaskExecutionRecord.Status.FAILURE, 2, "line1\nline2\rx|y;z");
        String out = r.getOutput();
        assertFalse(out.contains("\n"));
        assertFalse(out.contains("\r"));
        assertFalse(out.contains("|"));
        assertFalse(out.contains(";"));
        // The record still round-trips because the delimiters are gone.
        assertEquals(out, TaskExecutionRecord.decode(r.encode()).getOutput());
    }

    @Test
    @DisplayName("output longer than the cap keeps only the trailing characters")
    void capsOutput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TaskExecutionRecord.MAX_OUTPUT_CHARS + 500; i++) {
            sb.append('x');
        }
        TaskExecutionRecord r = new TaskExecutionRecord(
                1L, 1L, TaskExecutionRecord.Status.SUCCESS, 0, sb.toString());
        assertEquals(TaskExecutionRecord.MAX_OUTPUT_CHARS, r.getOutput().length());
    }

    @Test
    @DisplayName("a null output becomes empty and isSuccess reflects the status")
    void nullOutput() {
        TaskExecutionRecord r = new TaskExecutionRecord(
                1L, 1L, TaskExecutionRecord.Status.TIMED_OUT, -1, null);
        assertEquals("", r.getOutput());
        assertFalse(r.isSuccess());
        assertTrue(r.toString().contains("TIMED_OUT"));
    }

    @Test
    @DisplayName("decode tolerates malformed input by returning null")
    void decodeMalformed() {
        assertNull(TaskExecutionRecord.decode(null));
        assertNull(TaskExecutionRecord.decode(""));
        assertNull(TaskExecutionRecord.decode("a|b"));
        assertNull(TaskExecutionRecord.decode("x|y|NOT_A_STATUS|0|out"));
        assertNull(TaskExecutionRecord.decode("1|2|SUCCESS|notanumber|out"));
    }

    @Test
    @DisplayName("a record with no output field decodes to empty output")
    void decodeWithoutOutput() {
        TaskExecutionRecord d = TaskExecutionRecord.decode("5|6|SUCCESS|0");
        assertEquals("", d.getOutput());
        assertEquals(5L, d.getStartedAtMillis());
    }
}
