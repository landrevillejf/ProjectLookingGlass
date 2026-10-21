/*
 * Exercises the real ProcessLauncher with genuine (short-lived, portable) child
 * processes: an empty/null command and a missing executable degrade to a failure
 * CommandResult rather than throwing -- which is exactly how "no docker installed"
 * becomes dockerAvailable=false -- while `java -version` runs to completion with a
 * zero exit and captured output. The timeout branch is proven with a POSIX sleep
 * and skipped where no /bin/sh exists.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RealProcessLauncherTest {

    private final RealProcessLauncher launcher = new RealProcessLauncher();

    @Test
    void emptyCommandIsAFailure() {
        CommandResult result = launcher.launch(List.of(), 1000);
        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isNegative();
        assertThat(result.stderr()).contains("no command");
    }

    @Test
    void nullCommandIsAFailure() {
        CommandResult result = launcher.launch(null, 1000);
        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isNegative();
    }

    @Test
    void missingExecutableDegradesToFailure() {
        CommandResult result = launcher.launch(List.of("definitely-not-on-path-xyz"), 5000);
        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isNegative();
        assertThat(result.stderr()).contains("could not run");
    }

    @Test
    void javaVersionExitsZeroAndCapturesOutput() {
        CommandResult result = launcher.launch(
                List.of(JarProcessBackend.javaExecutable(), "-version"), 30000);
        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isZero();
        // `java -version` writes its banner to stderr on every JDK.
        assertThat(result.stderr() + result.stdout()).contains("version");
    }

    @Test
    void timeoutDestroysProcessAndReportsFailure() {
        assumeTrue(Files.exists(Path.of("/bin/sh")), "a POSIX shell is required");
        CommandResult result = launcher.launch(List.of("/bin/sh", "-c", "sleep 5"), 200);
        assertThat(result.ok()).isFalse();
        assertThat(result.stderr()).contains("timed out");
    }

    @Test
    void interruptDuringTheWaitIsAFailure() {
        assumeTrue(Files.exists(Path.of("/bin/sh")), "a POSIX shell is required");
        // Interrupt this thread before the bounded wait: waitFor() observes the flag
        // and throws InterruptedException, which the launcher turns into a failure
        // CommandResult after forcibly destroying the child.
        Thread.currentThread().interrupt();
        CommandResult result;
        try {
            result = launcher.launch(List.of("/bin/sh", "-c", "sleep 5"), 30000);
        } finally {
            // the launcher re-asserts the interrupt status; clear it so it cannot leak
            Thread.interrupted();
        }
        assertThat(result.ok()).isFalse();
        assertThat(result.stderr()).contains("interrupted");
    }
}
