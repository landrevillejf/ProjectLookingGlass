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
package org.jdesktop.lg3d.displayserver.crash;

import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The hook that turns a thread dying silently into a bug report.
 *
 * <p>{@link LogHandler} sees only what the desktop chooses to log. The failures
 * that matter most are the ones nobody logged: a {@code RuntimeException} that
 * escapes an application's thread, a {@code StackOverflowError} from a layout
 * recursion, a null pointer on a Java 3D scene-graph thread. Before this hook those
 * died with a stack trace on a console the user is not looking at, and the bug
 * never reached the tracker. Installing a default uncaught-exception handler is the
 * only place in a JVM that sees all of them.</p>
 *
 * <h2>Three decisions worth their documentation</h2>
 *
 * <ul>
 *   <li><b>It chains.</b> The handler keeps whatever default handler was installed
 *   before and calls it last. A host JVM, a test runner or an application server
 *   that installed its own reporter would otherwise lose it the moment the desktop
 *   started - and a silently dropped handler is the kind of breakage nobody
 *   debugs.</li>
 *   <li><b>It prints first.</b> Setting a default handler <em>replaces</em> the
 *   JVM's own stack-trace dump, so a crash would stop appearing in the log file.
 *   The trace is written to {@code stderr} explicitly to preserve twenty years of
 *   behaviour that scripts and users read.</li>
 *   <li><b>It does not raise the error dialog.</b> That is {@link LogHandler}'s
 *   surface, and one exception reaching both would show the user two dialogs for
 *   one bug. The reporter's own delivery - the prefilled issue form opening in the
 *   browser - is how the user learns about a crash on a worker thread, and the
 *   desktop keeps running.</li>
 * </ul>
 *
 * <p>Nothing here may throw. The handler runs on a thread that is already unwinding
 * and often on the last thread the desktop owns; {@code handle} catches
 * {@code Throwable} for exactly that reason.</p>
 */
public final class CrashWatcher {

    /**
     * The logger the watcher writes its own diagnostic to. Deliberately not
     * {@code SEVERE}: {@link LogHandler} opens the crash dialog on a severe record,
     * and the watcher reporting through that logger would raise the dialog a second
     * time for the same exception.
     */
    public static final String LOGGER_NAME = "lg.crashwatch";

    /** Names the catching component in the report footer. */
    public static final String SOURCE = "CrashWatcher";

    private static final Logger LOG = Logger.getLogger(LOGGER_NAME);

    /** Guard so a re-install does not stack the same handler twice. */
    private static volatile CrashReporter installed;

    /** The handler object, kept so {@link #isInstalled} can recognise itself. */
    private static volatile Thread.UncaughtExceptionHandler handler;

    /**
     * The handler that was in place before the first install, kept so a second
     * install chains to it rather than to our own watcher.
     */
    private static volatile Thread.UncaughtExceptionHandler previousHandler;

    private CrashWatcher() {
    }

    /**
     * Starts watching uncaught exceptions, using the shared reporter.
     *
     * @return the installed handler, or the pre-existing one when watching is off
     */
    public static Thread.UncaughtExceptionHandler install() {
        return install(CrashReporter.shared());
    }

    /**
     * Starts watching uncaught exceptions with an explicit reporter - the seam a
     * test or an embedded host uses, since a test cannot reach {@link #shared()}
     * without a spool under the real home directory.
     *
     * @param reporter where a caught exception goes; {@code null} uninstalls
     * @return the handler now installed as the JVM default
     */
    public static synchronized Thread.UncaughtExceptionHandler install(CrashReporter reporter) {
        if (reporter == null || !CrashReporter.isEnabled()) {
            LOG.log(Level.FINE, "uncaught-exception watching is not installed");
            return Thread.getDefaultUncaughtExceptionHandler();
        }
        if (installed == reporter && isInstalled()) {
            return handler;
        }
        // Read before setting: the previous default is what a host installed, and it
        // has to survive being outranked here, not be replaced by us. When our own
        // watcher is already in place, the chain target stays the one remembered at
        // the first install - otherwise a host that installs a second reporter would
        // stack two watchers and file every crash twice, once per install.
        final Thread.UncaughtExceptionHandler previous = isInstalled()
                ? previousHandler : Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler watcher = (thread, thrown) -> {
            handle(thread, thrown, reporter, previous, System.err);
        };
        Thread.setDefaultUncaughtExceptionHandler(watcher);
        previousHandler = previous;
        installed = reporter;
        handler = watcher;
        return watcher;
    }

    /**
     * Stops watching and restores the handler that was installed before
     * {@link #install}. Idempotent.
     *
     * @return true when a watcher was in place and has been removed
     */
    public static synchronized boolean uninstall() {
        if (!isInstalled()) {
            return false;
        }
        // The host's handler is handed back, which is what "installing a watcher"
        // promises a caller: it outranks the chain for a while and does not silently
        // delete what was there. A null is the JVM's own default, and is legal.
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        installed = null;
        handler = null;
        previousHandler = null;
        return true;
    }

    /**
     * @return true when the JVM's current default handler is the one this class
     *         installed - checked by identity, because a handler that reports and a
     *         handler that only prints look identical from the outside
     */
    public static boolean isInstalled() {
        return handler != null
                && Thread.getDefaultUncaughtExceptionHandler() == handler;
    }

    /**
     * The one reaction to a dying thread, split out from the lambda so it is
     * directly testable: pass a reporter wired to a temporary spool and a silent
     * sink, a null previous handler and a {@code PrintStream} in memory, and assert
     * on what each of the three received.
     *
     * @param thread   the thread that died, may be {@code null}
     * @param thrown   the failure, may be {@code null} on a badly behaved caller
     * @param reporter the capture pipeline
     * @param previous the handler that was installed before this one, may be
     *                 {@code null} for the JVM's own default
     * @param err      where the trace is echoed
     */
    static void handle(Thread thread, Throwable thrown, CrashReporter reporter,
                       Thread.UncaughtExceptionHandler previous, PrintStream err) {
        try {
            if (thrown != null) {
                // The JDK prints this dump for us only when no handler is installed,
                // and installing one is the point of this class: without this line
                // the desktop's own log files quietly stop containing crash traces.
                echo(err, "[lg3d] uncaught exception in thread \""
                        + nameOf(thread) + "\": " + thrown);
                echoStack(err, thrown);
                boolean fatal = isFatal(thread);
                if (fatal) {
                    // Synchronously, and this is the one place the rule matters: the
                    // main thread's death ends the JVM, and a daemon reporter thread
                    // would be killed before it wrote the file. Losing the report of
                    // the worst class of crash is the worst possible failure mode.
                    reporter.report(thrown, CrashReport.Kind.CRASH, true, SOURCE);
                } else {
                    reporter.reportAsync(thrown, CrashReport.Kind.CRASH, false, SOURCE);
                }
            }
        } catch (Throwable whileReporting) {
            // Reaching here means the reporter itself, or the console, refused to
            // work. Say so and carry on - never rethrow, because this method is the
            // last line of defence and the handler chained below still has to run.
            echo(err, "[lg3d] the crash reporter failed: " + whileReporting);
            echoStack(err, thrown);
        }
        if (previous != null) {
            try {
                previous.uncaughtException(thread, thrown);
            } catch (Throwable fromPrevious) {
                // The host's handler is not ours to validate; a failure in it must
                // not stop the thread's death from being recorded by the one above.
                echo(err, "[lg3d] the previous uncaught-exception handler failed: "
                        + fromPrevious);
            }
        }
    }

    /**
     * One line to the console, best effort.
     *
     * <p>Every write here is wrapped because {@code System.err} is the one channel
     * this class trusts when everything else has failed - and it can fail too: a
     * desktop started with its output redirected to a closed pipe, or run as a
     * service whose standard handles were reaped. An unwrapped throw while echoing
     * would skip the chained handler and take the host's own crash recording down
     * with it.</p>
     */
    private static void echo(PrintStream err, String message) {
        try {
            err.println(message);
            err.flush();
        } catch (Throwable consoleIsGone) {
            // Nothing is left to report to. The report still went to the spool.
        }
    }

    /** The trace itself, on the same best-effort terms as {@link #echo}. */
    private static void echoStack(PrintStream err, Throwable thrown) {
        if (thrown == null) {
            return;
        }
        try {
            thrown.printStackTrace(err);
            err.flush();
        } catch (Throwable consoleIsGone) {
            // As above: the console is a luxury, the spool and the chain are not.
        }
    }

    /**
     * Decides whether a death counts as "the desktop could not continue".
     *
     * <p>Only the threads whose loss ends the session do: {@code main}, because
     * startup died before the desktop came up, and the AWT event dispatch thread,
     * because every Swing and every input path runs on it. Any other thread - an
     * applet's, a loader's, a Java 3D update's - takes one application down while
     * the desktop survives, which is a {@code MAJOR} defect and not a
     * {@code BLOCKER}. A reporter that called every worker-thread exception fatal
     * would file the same severity for a broken thumbnail and a dead desktop.</p>
     *
     * <p>The test is by thread <em>name</em> and nothing else: an uncaught-exception
     * handler runs on the thread that is dying, so comparing against
     * {@code Thread.currentThread()} would be true for every single exception and
     * would mark every report fatal.</p>
     *
     * @param thread the dying thread, may be {@code null}
     * @return true when the desktop cannot continue without it
     */
    static boolean isFatal(Thread thread) {
        if (thread == null) {
            return false;
        }
        String name = thread.getName();
        return "main".equals(name) || name.startsWith("AWT-EventQueue");
    }

    private static String nameOf(Thread thread) {
        return (thread == null) ? "unknown" : String.valueOf(thread.getName());
    }

    /**
     * @return the reporter currently installed, for a panel that wants to publish a
     *         manual report through the same pipeline
     */
    public static CrashReporter installedReporter() {
        return installed;
    }

    /**
     * Captures on the calling thread and returns once the report is on disk - the
     * path a caller takes when the JVM is about to exit and a queued task would
     * never run, which is precisely what {@code SevereRuntimeError} does.
     *
     * @param thrown the failure
     * @param fatal  whether the desktop is ending
     * @return the capture outcome; never {@code null}, never throws
     */
    public static CrashReporter.Outcome captureNow(Throwable thrown, boolean fatal) {
        CrashReporter reporter = installed;
        if (reporter == null) {
            reporter = CrashReporter.shared();
        }
        return reporter.report(thrown, CrashReport.Kind.CRASH, fatal, SOURCE);
    }
}
