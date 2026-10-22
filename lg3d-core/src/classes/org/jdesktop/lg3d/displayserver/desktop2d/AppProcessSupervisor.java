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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the lifecycle of one isolated {@link Desktop2DAppRegistry.Kind#CHILD_PROCESS}
 * application: a single live child process per app, de-duplicated relaunches,
 * drained output, and a bounded restart policy so a crashing app recovers on its
 * own without ever looping forever and without ever taking the desktop down.
 *
 * <p>The restart policy is the interesting part and is kept <em>pure</em> -
 * {@link #decide} maps an exit code, the child's uptime and the attempt budget
 * onto a {@link Decision}, so it is asserted directly rather than through a real
 * process. A child that exits cleanly ({@code 0}) is never restarted; a child
 * that dies is restarted up to {@link Policy#maxAttempts()} times, and a child
 * that ran long enough to be considered stable ({@link Policy#stableMillis()})
 * earns a fresh budget, so an app that works for an hour and then crashes once
 * is restarted rather than abandoned.</p>
 *
 * <p>Everything that touches the operating system is injected - the
 * {@link Starter} that forks the child, the {@link LongSupplier} clock and the
 * {@link Notifier} that surfaces transitions to the desktop - so supervision is
 * exercised headlessly with a stub process.</p>
 */
public final class AppProcessSupervisor {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** What the supervisor does when a child process exits. */
    public enum Decision {
        /** The child exited cleanly; do not restart it. */
        STOP_CLEAN,
        /** The child died within its attempt budget; restart it. */
        RELAUNCH,
        /** The child died after a stable run; restart it on a fresh budget. */
        RELAUNCH_RESET,
        /** The child died and exhausted its budget; give up. */
        STOP_EXHAUSTED
    }

    /** The restart budget and the uptime that resets it. */
    public static final class Policy {

        private final int maxAttempts;
        private final long stableMillis;

        /**
         * @param maxAttempts  consecutive fast crashes tolerated before giving up
         * @param stableMillis uptime after which a crash is treated as a fresh fault
         */
        public Policy(final int maxAttempts, final long stableMillis) {
            this.maxAttempts = maxAttempts;
            this.stableMillis = stableMillis;
        }

        /** Consecutive fast crashes tolerated before giving up. */
        public int maxAttempts() {
            return maxAttempts;
        }

        /** Uptime (ms) after which a crash earns a fresh restart budget. */
        public long stableMillis() {
            return stableMillis;
        }

        /**
         * The Web Browser policy: two fast-crash retries, and a run of fifteen
         * seconds counts as stable.
         *
         * @return the browser restart policy
         */
        public static Policy webBrowser() {
            return new Policy(2, 15_000L);
        }

        /**
         * The plain-Swing child policy (a hosted panel or a Swing frame app): two
         * fast-crash retries, and a run of ten seconds counts as stable. A Swing
         * child starts faster than a JavaFX/WebKit one, so its stable window is
         * correspondingly shorter.
         *
         * @return the Swing child restart policy
         */
        public static Policy swing() {
            return new Policy(2, 10_000L);
        }
    }

    /** Forks a child process; injectable so supervision needs no real JVM. */
    public interface Starter {
        /**
         * Starts the child.
         *
         * @param command the command tokens
         * @param env     environment overrides
         * @return the started process
         * @throws IOException if the process could not be started
         */
        Process start(List<String> command, Map<String, String> env) throws IOException;
    }

    /** Surfaces lifecycle transitions to the user (desktop notifications). */
    public interface Notifier {
        /** The app is already running; no new child was forked. */
        void alreadyOpen();

        /**
         * A crashed child is being restarted.
         *
         * @param attempt the restart number now taken
         */
        void restarting(int attempt);

        /**
         * A child exited cleanly and will not be restarted.
         *
         * @param exitCode the child's exit code
         */
        void stopped(int exitCode);

        /**
         * A child exhausted its restart budget and was abandoned.
         *
         * @param attempts the budget that was exhausted
         */
        void gaveUp(int attempts);

        /** The child could not be started at all. */
        void startFailed();
    }

    private final String label;
    private final List<String> command;
    private final Map<String, String> env;
    private final Policy policy;
    private final Starter starter;
    private final LongSupplier clock;
    private final Notifier notifier;

    private Process current;
    private long startMillis;
    private int attempts;

    /**
     * Set once {@link #shutdown()} is called, so the terminated child is neither
     * restarted (its exit is a deliberate teardown, not a crash) nor surfaced to
     * the user as a stop. Volatile: {@code shutdown()} may run on the EDT while
     * the child-exit callback fires on a ForkJoinPool worker.
     */
    private volatile boolean stopping;

    /**
     * Creates a supervisor for one child application.
     *
     * @param label    human-readable app name (for logs and notifications)
     * @param command  the command tokens to fork
     * @param env      environment overrides for the child
     * @param policy   the restart budget
     * @param starter  the process-forking seam
     * @param clock    wall-clock source (ms) for uptime
     * @param notifier the transition callback
     */
    public AppProcessSupervisor(final String label, final List<String> command,
            final Map<String, String> env, final Policy policy,
            final Starter starter, final LongSupplier clock,
            final Notifier notifier) {
        this.label = label;
        this.command = command;
        this.env = env;
        this.policy = policy;
        this.starter = starter;
        this.clock = clock;
        this.notifier = notifier;
    }

    /**
     * Builds a supervisor wired to the real {@link ChildProcessLauncher#start}
     * and the system clock.
     *
     * @param label    human-readable app name
     * @param command  the command tokens to fork
     * @param env      environment overrides for the child
     * @param policy   the restart budget
     * @param notifier the transition callback
     * @return a production supervisor
     */
    public static AppProcessSupervisor forCommand(final String label,
            final List<String> command, final Map<String, String> env,
            final Policy policy, final Notifier notifier) {
        return new AppProcessSupervisor(label, command, env, policy,
                ChildProcessLauncher::start, System::currentTimeMillis, notifier);
    }

    /**
     * The pure restart decision.
     *
     * @param exitCode      the child's exit code
     * @param livedMillis   how long the child ran
     * @param priorAttempts fast crashes already taken on the current budget
     * @param policy        the restart budget
     * @return what to do about the exit
     */
    public static Decision decide(final int exitCode, final long livedMillis,
            final int priorAttempts, final Policy policy) {
        if (exitCode == 0) {
            return Decision.STOP_CLEAN;
        }
        if (livedMillis >= policy.stableMillis()) {
            return Decision.RELAUNCH_RESET;
        }
        if (priorAttempts < policy.maxAttempts()) {
            return Decision.RELAUNCH;
        }
        return Decision.STOP_EXHAUSTED;
    }

    /** True when a {@link Decision} calls for restarting the child. */
    public static boolean isRelaunch(final Decision decision) {
        return decision == Decision.RELAUNCH || decision == Decision.RELAUNCH_RESET;
    }

    /**
     * Applies an exit to the attempt bookkeeping and returns the decision: a
     * fast crash consumes budget, a crash after a stable run resets it, and any
     * stop clears it so the next manual launch starts fresh.
     */
    Decision onExit(final int exitCode, final long livedMillis) {
        Decision decision = decide(exitCode, livedMillis, attempts, policy);
        switch (decision) {
            case RELAUNCH:
                attempts++;
                break;
            case RELAUNCH_RESET:
                attempts = 1;
                break;
            case STOP_CLEAN:
            case STOP_EXHAUSTED:
            default:
                attempts = 0;
                break;
        }
        return decision;
    }

    /** Fast crashes taken on the current budget (package-visible for tests). */
    int attempts() {
        return attempts;
    }

    /** True while a child process is running. */
    public boolean isAlive() {
        return current != null && current.isAlive();
    }

    /**
     * Launches the app, or reports it already open when a child is live, so
     * clicking the launcher twice never forks a second browser.
     *
     * @return true if a new child process was started
     */
    public boolean launch() {
        if (stopping) {
            return false;   // the desktop is shutting down; do not resurrect
        }
        if (isAlive()) {
            notifier.alreadyOpen();
            return false;
        }
        return spawn();
    }

    private boolean spawn() {
        try {
            Process process = starter.start(command, env);
            current = process;
            startMillis = clock.getAsLong();
            drain(process);
            process.onExit().whenComplete((p, err) -> handleExit(p));
            return true;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not start the child app: " + label, e);
            notifier.startFailed();
            return false;
        }
    }

    /**
     * Terminates the live child (if any) and stops supervising it, so the child
     * process is reaped when the desktop shuts down instead of being orphaned.
     * The terminated child's exit is deliberately not restarted and not reported:
     * it is the desktop going away, not a crash. Idempotent and safe to call
     * when no child was ever launched.
     */
    public void shutdown() {
        stopping = true;
        Process process = current;
        current = null;
        if (process != null && process.isAlive()) {
            logger.log(Level.INFO, "Terminating the child app {0} on desktop exit",
                    label);
            process.destroy();
        }
    }

    void handleExit(final Process process) {
        if (process != current || stopping) {
            return;   // a superseded child, or a deliberate teardown: no restart
        }
        long lived = clock.getAsLong() - startMillis;
        int exitCode = safeExitValue(process);
        current = null;
        Decision decision = onExit(exitCode, lived);
        if (isRelaunch(decision)) {
            logger.log(Level.INFO, "Child app {0} exited with {1}; restarting",
                    new Object[] { label, exitCode });
            notifier.restarting(attempts);
            spawn();
        } else if (decision == Decision.STOP_EXHAUSTED) {
            logger.log(Level.WARNING,
                    "Child app {0} crashed {1} times in a row; giving up",
                    new Object[] { label, policy.maxAttempts() });
            notifier.gaveUp(policy.maxAttempts());
        } else {
            notifier.stopped(exitCode);
        }
    }

    private static int safeExitValue(final Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** Consumes (and logs) the child's merged output so its pipe never fills. */
    private void drain(final Process process) {
        final InputStream in = process.getInputStream();
        if (in == null) {
            return;
        }
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line = reader.readLine();
                    while (line != null) {
                        logger.log(Level.INFO, "Output from {0}: {1}",
                                new Object[] { label, line });
                        line = reader.readLine();
                    }
                } catch (IOException e) {
                    logger.log(Level.FINE, "Error reading output of " + label, e);
                } finally {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        // nothing useful to do
                    }
                }
            }
        }, "child app output: " + label);
        thread.setDaemon(true);
        thread.start();
    }
}
