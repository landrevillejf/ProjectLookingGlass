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
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.jdesktop.lg3d.displayserver.desktop2d.AppProcessSupervisor.Decision;
import org.jdesktop.lg3d.displayserver.desktop2d.AppProcessSupervisor.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AppProcessSupervisor}: the pure restart policy and the child
 * lifecycle around it (de-duplicated launch, restart-on-crash, budget
 * exhaustion, clean-exit silence, start failure). Every operating-system seam
 * is injected - a stub {@link Process}, a controllable clock and a recording
 * notifier - so supervision is asserted deterministically without ever forking
 * a JVM.
 */
class AppProcessSupervisorTest {

    private static final List<String> CMD = List.of("java", "-cp", "x", "org.foo.Main");
    private static final Map<String, String> ENV = Map.of("DISPLAY", ":0");

    // ------------------------------------------------------------------
    // Pure policy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a clean exit is never restarted")
    void cleanExitStops() {
        assertEquals(Decision.STOP_CLEAN,
                AppProcessSupervisor.decide(0, 10, 0, new Policy(3, 1000)));
    }

    @Test
    @DisplayName("a fast crash within budget is restarted")
    void fastCrashRelaunches() {
        assertEquals(Decision.RELAUNCH,
                AppProcessSupervisor.decide(1, 10, 0, new Policy(3, 1000)));
        assertEquals(Decision.RELAUNCH,
                AppProcessSupervisor.decide(137, 999, 2, new Policy(3, 1000)));
    }

    @Test
    @DisplayName("a crash after a stable run earns a fresh budget")
    void stableCrashResets() {
        assertEquals(Decision.RELAUNCH_RESET,
                AppProcessSupervisor.decide(1, 1000, 5, new Policy(3, 1000)));
    }

    @Test
    @DisplayName("a fast crash past the budget is abandoned")
    void exhaustedBudgetStops() {
        assertEquals(Decision.STOP_EXHAUSTED,
                AppProcessSupervisor.decide(1, 10, 3, new Policy(3, 1000)));
    }

    @Test
    @DisplayName("isRelaunch is true only for the two restart decisions")
    void relaunchPredicate() {
        assertTrue(AppProcessSupervisor.isRelaunch(Decision.RELAUNCH));
        assertTrue(AppProcessSupervisor.isRelaunch(Decision.RELAUNCH_RESET));
        assertFalse(AppProcessSupervisor.isRelaunch(Decision.STOP_CLEAN));
        assertFalse(AppProcessSupervisor.isRelaunch(Decision.STOP_EXHAUSTED));
    }

    @Test
    @DisplayName("the Web Browser policy retries twice over a 15s stable window")
    void webBrowserPolicy() {
        Policy p = Policy.webBrowser();
        assertEquals(2, p.maxAttempts());
        assertEquals(15_000L, p.stableMillis());
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("launch forks one child and a second launch is de-duplicated")
    void launchDeduplicates() {
        FakeStarter starter = new FakeStarter();
        RecordingNotifier notifier = new RecordingNotifier();
        AtomicLong clock = new AtomicLong(1_000);
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(2, 1000), starter, clock::get, notifier);

        assertTrue(sup.launch(), "the first launch forks a child");
        assertTrue(sup.isAlive());
        assertEquals(1, starter.all.size());

        assertFalse(sup.launch(), "a live child is raised, not re-forked");
        assertEquals(1, starter.all.size(), "no second process was started");
        assertEquals(1, notifier.alreadyOpen);
    }

    @Test
    @DisplayName("a crashed child is restarted")
    void crashRestarts() {
        FakeStarter starter = new FakeStarter();
        RecordingNotifier notifier = new RecordingNotifier();
        AtomicLong clock = new AtomicLong(0);
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(2, 1000), starter, clock::get, notifier);

        assertTrue(sup.launch());
        clock.set(10);                       // a fast crash (lived 10ms < 1000)
        starter.all.get(0).exitWith(1);

        assertEquals(2, starter.all.size(), "a replacement child was forked");
        assertEquals(1, notifier.restarting);
        assertTrue(sup.isAlive(), "the replacement is running");
        assertEquals(1, sup.attempts());
    }

    @Test
    @DisplayName("a clean exit is silent and forks nothing")
    void cleanExitIsSilent() {
        FakeStarter starter = new FakeStarter();
        RecordingNotifier notifier = new RecordingNotifier();
        AtomicLong clock = new AtomicLong(0);
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(2, 1000), starter, clock::get, notifier);

        assertTrue(sup.launch());
        starter.all.get(0).exitWith(0);

        assertEquals(1, starter.all.size(), "no restart after a clean exit");
        assertEquals(1, notifier.stopped);
        assertEquals(0, notifier.restarting);
        assertFalse(sup.isAlive());
    }

    @Test
    @DisplayName("a child that keeps crashing is abandoned after the budget")
    void givesUpAfterBudget() {
        FakeStarter starter = new FakeStarter();
        RecordingNotifier notifier = new RecordingNotifier();
        AtomicLong clock = new AtomicLong(0);
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(1, 1000), starter, clock::get, notifier);

        assertTrue(sup.launch());            // child 0
        starter.all.get(0).exitWith(1);      // crash 1 -> within budget, restart
        assertEquals(2, starter.all.size()); // child 1
        starter.all.get(1).exitWith(1);      // crash 2 -> budget (1) exhausted

        assertEquals(2, starter.all.size(), "no third child was forked");
        assertEquals(1, notifier.gaveUp);
        assertEquals(0, sup.attempts(), "the budget resets once it gives up");
        assertFalse(sup.isAlive());
    }

    @Test
    @DisplayName("a crash after a stable run restarts on a fresh budget")
    void stableRunResetsBudget() {
        FakeStarter starter = new FakeStarter();
        RecordingNotifier notifier = new RecordingNotifier();
        AtomicLong clock = new AtomicLong(0);
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(1, 1000), starter, clock::get, notifier);

        assertTrue(sup.launch());
        clock.set(5_000);                    // lived 5s >= 1s stable window
        starter.all.get(0).exitWith(1);

        assertEquals(2, starter.all.size(), "a stable-then-crashed app restarts");
        assertEquals(1, notifier.restarting);
        assertEquals(1, sup.attempts(), "the fresh budget counts this restart");
    }

    @Test
    @DisplayName("a start failure is reported and forks nothing")
    void startFailureReported() {
        FakeStarter starter = new FakeStarter();
        starter.fail = true;
        RecordingNotifier notifier = new RecordingNotifier();
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(2, 1000), starter, () -> 0L, notifier);

        assertFalse(sup.launch());
        assertEquals(1, notifier.startFailed);
        assertFalse(sup.isAlive());
    }

    @Test
    @DisplayName("onExit bookkeeping: fast crash consumes, clean exit clears")
    void onExitBookkeeping() {
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(2, 1000), new FakeStarter(), () -> 0L,
                new RecordingNotifier());
        assertEquals(Decision.RELAUNCH, sup.onExit(1, 10));
        assertEquals(1, sup.attempts());
        assertEquals(Decision.RELAUNCH, sup.onExit(1, 10));
        assertEquals(2, sup.attempts());
        assertEquals(Decision.STOP_EXHAUSTED, sup.onExit(1, 10));
        assertEquals(0, sup.attempts(), "giving up clears the counter");
        assertEquals(Decision.STOP_CLEAN, sup.onExit(0, 10));
        assertEquals(0, sup.attempts());
    }

    // ------------------------------------------------------------------
    // Graceful shutdown (desktop exit)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("shutdown terminates the live child without restarting it")
    void shutdownTerminatesAndSuppressesRestart() {
        FakeStarter starter = new FakeStarter();
        RecordingNotifier notifier = new RecordingNotifier();
        AtomicLong clock = new AtomicLong(0);
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(2, 1000), starter, clock::get, notifier);

        assertTrue(sup.launch());
        assertTrue(sup.isAlive());

        sup.shutdown();

        // destroy() exits the child with 143; a fast crash would otherwise be
        // restarted, so the supervisor must swallow it as a deliberate teardown.
        assertFalse(sup.isAlive(), "the child was destroyed");
        assertEquals(1, starter.all.size(), "no replacement child was forked");
        assertEquals(0, notifier.restarting);
        assertEquals(0, notifier.stopped, "a teardown is not reported as a stop");
        assertEquals(0, notifier.gaveUp);
    }

    @Test
    @DisplayName("shutdown with no child is a harmless no-op")
    void shutdownWithoutChildIsSafe() {
        FakeStarter starter = new FakeStarter();
        RecordingNotifier notifier = new RecordingNotifier();
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(2, 1000), starter, () -> 0L, notifier);

        sup.shutdown();
        sup.shutdown();                       // idempotent

        assertFalse(sup.isAlive());
        assertTrue(starter.all.isEmpty(), "nothing was ever forked");
        assertEquals(0, notifier.stopped);
    }

    @Test
    @DisplayName("a launch after shutdown is refused and forks nothing")
    void launchAfterShutdownIsRefused() {
        FakeStarter starter = new FakeStarter();
        RecordingNotifier notifier = new RecordingNotifier();
        AppProcessSupervisor sup = new AppProcessSupervisor("App", CMD, ENV,
                new Policy(2, 1000), starter, () -> 0L, notifier);

        sup.shutdown();
        assertFalse(sup.launch(), "a stopped supervisor does not resurrect");
        assertTrue(starter.all.isEmpty(), "no child was forked after shutdown");
    }

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    /** A {@link Process} whose exit the test triggers synchronously. */
    private static final class FakeProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private final int pid;
        private boolean alive = true;
        private int code;

        FakeProcess(final int pid) {
            this.pid = pid;
        }

        void exitWith(final int exitCode) {
            this.alive = false;
            this.code = exitCode;
            exit.complete(this);
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            exit.join();
            return code;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return code;
        }

        @Override
        public void destroy() {
            exitWith(143);
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }
    }

    /** Hands out a fresh {@link FakeProcess} per start, or fails on demand. */
    private static final class FakeStarter implements AppProcessSupervisor.Starter {
        final List<FakeProcess> all = new ArrayList<>();
        boolean fail;

        @Override
        public Process start(final List<String> command,
                final Map<String, String> env) throws IOException {
            if (fail) {
                throw new IOException("start refused");
            }
            FakeProcess process = new FakeProcess(all.size() + 1);
            all.add(process);
            return process;
        }
    }

    /** Counts each notifier transition. */
    private static final class RecordingNotifier
            implements AppProcessSupervisor.Notifier {
        int alreadyOpen;
        int restarting;
        int stopped;
        int gaveUp;
        int startFailed;

        @Override
        public void alreadyOpen() {
            alreadyOpen++;
        }

        @Override
        public void restarting(final int attempt) {
            restarting++;
        }

        @Override
        public void stopped(final int exitCode) {
            stopped++;
        }

        @Override
        public void gaveUp(final int attempts) {
            gaveUp++;
        }

        @Override
        public void startFailed() {
            startFailed++;
        }
    }
}
