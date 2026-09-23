package org.lpmconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the process-facing surface of {@link LPMExecutor} that is safe to
 * exercise without a real LPM install: the availability probe, the concurrency
 * guard, and the failure paths taken when {@code /usr/bin/lpm} is absent (the
 * case on every CI/dev host). Tests that depend on the binary being missing are
 * skipped via an assumption if it happens to be installed. Spawning a real
 * privileged process is deliberately not tested here.
 */
class LPMExecutorTest {

    private static boolean lpmBinaryAbsent() {
        return !LPMExecutor.isLPMAvailable();
    }

    @Test
    @DisplayName("isLPMAvailable mirrors the state of /usr/bin/lpm")
    void availabilityMirrorsBinary() {
        File bin = new File("/usr/bin/lpm");
        assertEquals(bin.exists() && bin.canExecute(),
                LPMExecutor.isLPMAvailable());
    }

    @Test
    @DisplayName("no operation is in progress between executions")
    void idleBetweenExecutions() {
        // Every execute() path releases the guard in its finally block, and the
        // concurrency-guard test resets it explicitly, so the observable state
        // between tests must be idle.
        assertFalse(LPMExecutor.isOperationInProgress());
    }

    @Test
    @DisplayName("execute throws LPMExecutionException when lpm is absent")
    void executeFailsWithoutBinary() {
        Assumptions.assumeTrue(lpmBinaryAbsent(),
                "/usr/bin/lpm is installed; skipping the absence path");
        LPMExecutor executor = new LPMExecutor(null, null, false);
        assertThrows(LPMExecutionException.class,
                () -> executor.execute(LPMCommand.LIST, List.of(), false));
        // The finally block must have released the concurrency guard.
        assertFalse(LPMExecutor.isOperationInProgress());
    }

    @Test
    @DisplayName("execute builds dry-run and argument forms without hanging")
    void executeCoversArgBuilding() {
        Assumptions.assumeTrue(lpmBinaryAbsent(),
                "/usr/bin/lpm is installed; skipping the absence path");
        LPMExecutor executor = new LPMExecutor(line -> { }, line -> { }, false);
        // dryRun=true and a non-null arg list exercise buildCommandArgs; the
        // missing binary still makes start() fail fast (no process is spawned).
        assertThrows(LPMExecutionException.class, () -> executor.execute(
                LPMCommand.SEARCH, List.of("firefox"), true));
        assertFalse(LPMExecutor.isOperationInProgress());
    }

    @Test
    @DisplayName("a second operation is rejected while one is in progress")
    void rejectsConcurrentOperation() throws Exception {
        Field flag = LPMExecutor.class.getDeclaredField("operationInProgress");
        flag.setAccessible(true);
        flag.setBoolean(null, true);
        try {
            LPMExecutor executor = new LPMExecutor(null, null, false);
            LPMExecutionException ex = assertThrows(LPMExecutionException.class,
                    () -> executor.execute(LPMCommand.LIST, List.of(), false));
            assertTrue(ex.getMessage().contains("already in progress"));
        } finally {
            // The guard throws before the try/finally, so reset it explicitly.
            flag.setBoolean(null, false);
        }
    }

    @Test
    @DisplayName("getLPMVersion throws when lpm is absent")
    void versionFailsWithoutBinary() {
        Assumptions.assumeTrue(lpmBinaryAbsent(),
                "/usr/bin/lpm is installed; skipping the absence path");
        assertThrows(LPMExecutionException.class, LPMExecutor::getLPMVersion);
    }
}
