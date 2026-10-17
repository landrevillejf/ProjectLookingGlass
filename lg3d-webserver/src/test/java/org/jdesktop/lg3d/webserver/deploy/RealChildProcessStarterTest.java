/*
 * Exercises the real ChildProcessStarter with genuine (short-lived) child
 * processes: an empty/null command and a missing executable throw IOException (the
 * JAR backend turns that into a readable DeploymentException), while a forked shell
 * child reports a live pid, merges stdout+stderr into its bounded output window and
 * is killed by stop(). Guarded by an assumption so it skips cleanly where no POSIX
 * shell is present.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RealChildProcessStarterTest {

    private final RealChildProcessStarter starter = new RealChildProcessStarter();

    private static void assumeShell() {
        assumeTrue(Files.exists(Path.of("/bin/sh")), "a POSIX shell is required");
    }

    @Test
    void emptyCommandThrowsIOException() {
        assertThatThrownBy(() -> starter.start(List.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no command");
    }

    @Test
    void nullCommandThrowsIOException() {
        assertThatThrownBy(() -> starter.start(null)).isInstanceOf(IOException.class);
    }

    @Test
    void missingExecutableThrowsIOException() {
        assertThatThrownBy(() -> starter.start(List.of("definitely-not-on-path-xyz")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void forksLiveChildCapturesOutputAndStops() throws Exception {
        assumeShell();
        ChildProcess child = starter.start(List.of("/bin/sh", "-c", "echo hello; sleep 10"));
        try {
            assertThat(child.pid()).isPositive();
            assertThat(child.isAlive()).isTrue();
            assertThat(awaitOutput(child, "hello", 5000)).contains("hello");
        } finally {
            child.stop();
        }
        assertThat(child.isAlive()).isFalse();
    }

    @Test
    void mergesStderrIntoOutput() throws Exception {
        assumeShell();
        ChildProcess child =
                starter.start(List.of("/bin/sh", "-c", "echo out; echo err 1>&2; sleep 10"));
        try {
            assertThat(awaitOutput(child, "err", 5000)).contains("out").contains("err");
        } finally {
            child.stop();
        }
    }

    @Test
    void exitedChildIsNotAlive() throws Exception {
        assumeShell();
        ChildProcess child = starter.start(List.of("/bin/sh", "-c", "true"));
        long deadline = System.currentTimeMillis() + 5000;
        while (child.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(child.isAlive()).isFalse();
    }

    private static String awaitOutput(ChildProcess child, String needle, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String output = "";
        while (System.currentTimeMillis() < deadline) {
            output = child.output();
            if (output.contains(needle)) {
                return output;
            }
            Thread.sleep(50);
        }
        return output;
    }
}
