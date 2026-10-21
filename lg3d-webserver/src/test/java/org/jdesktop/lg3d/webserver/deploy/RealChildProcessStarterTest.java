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

    @Test
    void stopOfAnAlreadyExitedChildIsANoOp() throws Exception {
        assumeShell();
        ChildProcess child = starter.start(List.of("/bin/sh", "-c", "true"));
        long deadline = System.currentTimeMillis() + 5000;
        while (child.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(child.isAlive()).isFalse();
        child.stop();
        assertThat(child.isAlive()).isFalse();
    }

    @Test
    void interruptDuringStopForciblyKillsTheChild() throws Exception {
        assumeShell();
        ChildProcess child = starter.start(List.of("/bin/sh", "-c", "sleep 30"));
        assertThat(child.isAlive()).isTrue();
        // Interrupt this thread before the graceful wait: waitFor() throws
        // InterruptedException, and stop() escalates straight to a forcible kill.
        Thread.currentThread().interrupt();
        try {
            child.stop();
        } finally {
            Thread.interrupted();
        }
        assertThat(awaitDeath(child, 5000)).isFalse();
    }

    @Test
    void outputWindowIsBoundedToTheLastMaxLines() throws Exception {
        assumeShell();
        ChildProcess child = starter.start(List.of("/bin/sh", "-c",
                "i=0; while [ $i -lt 2500 ]; do echo line$i; i=$((i+1)); done; sleep 5"));
        try {
            // Wait until the gobbler has drained the final line, so the bounded
            // window has necessarily evicted its oldest entries.
            assertThat(awaitOutput(child, "line2499", 15000)).contains("line2499");
            assertThat(child.output().split("\n", -1).length).isLessThanOrEqualTo(2000);
        } finally {
            child.stop();
        }
    }

    @Test
    void stopEscalatesToForcibleKillWhenSigtermIsIgnored() throws Exception {
        assumeShell();
        // The child prints "ready" only after installing the TERM-ignore trap, so
        // stop() cannot race ahead of the trap and hit the default disposition.
        ChildProcess child = starter.start(List.of("/bin/sh", "-c",
                "trap '' TERM; echo ready; sleep 30"));
        assertThat(awaitOutput(child, "ready", 5000)).contains("ready");
        assertThat(child.isAlive()).isTrue();
        child.stop();
        assertThat(awaitDeath(child, 8000)).isFalse();
    }

    private static boolean awaitDeath(ChildProcess child, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (child.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        return child.isAlive();
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
