package org.lpmconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers every {@link LPMExecutionException} constructor: the message-only and
 * message+cause forms default the exit code to -1 with a null stderr, while the
 * exit-code forms carry the supplied values through.
 */
class LPMExecutionExceptionTest {

    @Test
    @DisplayName("message-only defaults to exit code -1 and null stderr")
    void messageOnly() {
        LPMExecutionException e = new LPMExecutionException("boom");
        assertEquals("boom", e.getMessage());
        assertEquals(-1, e.getExitCode());
        assertNull(e.getStderr());
        assertNull(e.getCause());
    }

    @Test
    @DisplayName("message+cause keeps the cause and defaults the code")
    void messageAndCause() {
        Throwable cause = new IllegalStateException("root");
        LPMExecutionException e = new LPMExecutionException("boom", cause);
        assertEquals("boom", e.getMessage());
        assertSame(cause, e.getCause());
        assertEquals(-1, e.getExitCode());
        assertNull(e.getStderr());
    }

    @Test
    @DisplayName("message+exitCode+stderr carries all three")
    void messageCodeStderr() {
        LPMExecutionException e =
                new LPMExecutionException("boom", 3, "bad things");
        assertEquals("boom", e.getMessage());
        assertEquals(3, e.getExitCode());
        assertEquals("bad things", e.getStderr());
        assertNull(e.getCause());
    }

    @Test
    @DisplayName("message+cause+exitCode+stderr carries everything")
    void messageCauseCodeStderr() {
        Throwable cause = new RuntimeException("io");
        LPMExecutionException e =
                new LPMExecutionException("boom", cause, 9, "stderr text");
        assertEquals("boom", e.getMessage());
        assertSame(cause, e.getCause());
        assertEquals(9, e.getExitCode());
        assertEquals("stderr text", e.getStderr());
    }
}
