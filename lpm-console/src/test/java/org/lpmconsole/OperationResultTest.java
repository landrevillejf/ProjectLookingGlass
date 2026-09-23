package org.lpmconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link OperationResult}: the exit-code -> success mapping, stdout /
 * stderr accessors, the newline-split output lines and the {@code verify}
 * integrity-result heuristic.
 */
class OperationResultTest {

    @Test
    @DisplayName("exit code 0 is success, anything else is failure")
    void successFollowsExitCode() {
        assertTrue(new OperationResult(0, "", "").isSuccess());
        assertFalse(new OperationResult(1, "", "").isSuccess());
        assertFalse(new OperationResult(-1, "", "").isSuccess());
        assertEquals(7, new OperationResult(7, "", "").getExitCode());
    }

    @Test
    @DisplayName("stdout and stderr are exposed verbatim")
    void exposesStreams() {
        OperationResult r = new OperationResult(0, "out", "err");
        assertEquals("out", r.getStdout());
        assertEquals("err", r.getStderr());
    }

    @Test
    @DisplayName("output lines split stdout on newlines")
    void parsesOutputLines() {
        List<String> lines =
                new OperationResult(0, "a\nb\nc", "").getOutputLines();
        assertEquals(List.of("a", "b", "c"), lines);
    }

    @Test
    @DisplayName("empty or null stdout yields no output lines")
    void emptyOutputHasNoLines() {
        assertTrue(new OperationResult(0, "", "").getOutputLines().isEmpty());
        assertTrue(new OperationResult(0, null, "").getOutputLines().isEmpty());
    }

    @Test
    @DisplayName("isIntegrityCheck spots 'integrity' on stderr")
    void integrityFromStderr() {
        assertTrue(new OperationResult(1, "", "integrity mismatch")
                .isIntegrityCheck());
    }

    @Test
    @DisplayName("isIntegrityCheck spots 'modified' on stdout")
    void integrityFromStdout() {
        assertTrue(new OperationResult(1, "file modified", "")
                .isIntegrityCheck());
    }

    @Test
    @DisplayName("isIntegrityCheck is false for an ordinary result")
    void notIntegrity() {
        assertFalse(new OperationResult(0, "all good", "").isIntegrityCheck());
        assertFalse(new OperationResult(1, null, null).isIntegrityCheck());
    }
}
