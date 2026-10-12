/**
 * Project Looking Glass
 *
 * $RCSfile: LogHandler.java,v $
 *
 * Copyright (c) 2005, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - modernization:
 * benign third-party engine SEVERE records (JavaFX/WebKit) no longer raise the
 * crash dialog, the show/suppress decision and trace formatting are testable
 * pure methods, the dialog is built on the EDT, and every severe record that
 * reaches the dialog is also captured by the crash-report pipeline, which spools
 * it and builds the prefilled issue the dialog offers. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 *
 * $Revision: 1.7 $
 * $Date: 2006-03-12 13:05:10 $
 * $State: Exp $
 */

package org.jdesktop.lg3d.displayserver;

import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.displayserver.crash.CrashReport;
import org.jdesktop.lg3d.displayserver.crash.CrashReporter;

/**
 * A {@link Handler} that opens the {@link ErrorDialog} when a severe error
 * occurs. It is instantiated by the logging system after parsing
 * {@code logging.properties}.
 *
 * <p>Only a genuinely desktop-fatal {@link Level#SEVERE} record raises the
 * dialog. Third-party engine loggers are filtered out by
 * {@link #shouldShowDialog}: JavaFX/WebKit logs routine, non-fatal conditions at
 * SEVERE - for example {@code com.sun.webkit.network.URLLoader} emits
 * "Unknown encoding type 'x' found, discarding" whenever a site serves a
 * {@code Content-Encoding} the engine cannot decode (Brotli, zstd, or a
 * non-standard {@code none}). Left unfiltered, merely visiting such a page pops
 * a spurious "save your work" warning even though nothing crashed. Genuine lg3d
 * severe errors (logger {@code lg.*}) are never suppressed.</p>
 *
 * <p>A record that passes that filter is also the desktop's most reliable
 * observation point for a real bug, so {@link #publish} hands the throwable to
 * {@link CrashReporter} on its way to the dialog. The capture never blocks the
 * logging thread: the dialog is queued first and the report's prefilled link is
 * pushed into it when the spool write finishes.</p>
 *
 * @author pinaraf
 */
public class LogHandler extends Handler {

    /**
     * Logger-name / source-class prefixes whose SEVERE records are benign
     * third-party UI and web-engine noise, not desktop-fatal errors.
     */
    private static final String[] SUPPRESSED_ENGINE_PREFIXES = {
        "com.sun.javafx.", "com.sun.webkit.", "javafx."
    };

    /** Names the catching component in the report footer and the archive record. */
    static final String REPORT_SOURCE = "LogHandler";

    private volatile ErrorDialog dialog;

    /** Creates a new instance of LogHandler. */
    public LogHandler() {
        // The dialog is created lazily: most runs never hit a severe error, so
        // there is no point keeping an ErrorDialog in memory "just in case".
        dialog = null;
    }

    @Override
    public void publish(LogRecord record) {
        if (!shouldShowDialog(record)) {
            return;
        }
        final String trace = formatTrace(record.getThrown());
        // Build and show on the EDT so Swing is only touched there. invokeLater
        // returns immediately, so the logging thread (possibly the JavaFX or
        // Java 3D thread) is never blocked, and the dialog is non-modal.
        SwingUtilities.invokeLater(() -> {
            if (dialog == null) {
                dialog = new ErrorDialog();
            }
            dialog.logRecord = record;
            dialog.setTrace(trace);
            dialog.run();
        });
        // Captured after the dialog is queued, so a slow or full disk delays a bug
        // report and never the user's warning.
        try {
            captureReport(record, CrashReporter.shared(), url -> {
                // Marshalled back onto the EDT: the dialog is constructed there, so
                // reading the field from the reporter thread would race the
                // assignment that created it - and on a fast capture the link would
                // be dropped before any dialog existed.
                SwingUtilities.invokeLater(() -> {
                    ErrorDialog shown = dialog;
                    if (shown != null) {
                        shown.setReportLink(url);
                    }
                });
            });
        } catch (Throwable reportingFailed) {
            // The pipeline's own contract is that it never throws; this guard is
            // for a mis-wired shared instance, and losing a report must not stop the
            // dialog that already happened nor break the logger for the next record.
            reportFailureQuietly(reportingFailed);
        }
    }

    /**
     * The capture half of {@link #publish}, split out so it can be tested without
     * building a Swing dialog. A record with no throwable is still reported: a
     * desktop that logs SEVERE without an exception has found something wrong, and
     * "no stack trace" is a fact about the bug rather than a reason to file nothing
     * - so the record's own text becomes the report.
     *
     * @param record   the severe record already accepted by {@link
     *                 #shouldShowDialog}
     * @param reporter the pipeline to hand it to
     * @param onLink   receives the prefilled issue link, off the calling thread for
     *                 a recoverable error and on it for a fatal one
     */
    static void captureReport(LogRecord record, CrashReporter reporter,
                              Consumer<String> onLink) {
        Throwable thrown = record.getThrown();
        String message = describe(record);
        if (thrown == null && message.isEmpty()) {
            // Neither a stack nor a text says nothing a maintainer could act on:
            // such a report would open an empty issue and spend the session's budget
            // on it. The dialog still appears, as it always did.
            return;
        }
        // Issue 435's rule, reused: only a SevereRuntimeError ends the desktop.
        boolean fatal = thrown instanceof SevereRuntimeError;
        if (fatal) {
            // Synchronously: the dialog will exit the desktop when the user
            // acknowledges it, and a queued task on a daemon thread is not
            // guaranteed to have run by then. The report of the crash that ends
            // the session is the one report that cannot be lost.
            reporter.report(thrown, message, CrashReport.Kind.SEVERE, true, REPORT_SOURCE);
        } else {
            reporter.capture(thrown, message, CrashReport.Kind.SEVERE, false, REPORT_SOURCE,
                    outcome -> {
                        if (outcome.prefill() != null) {
                            onLink.accept(outcome.prefill().url());
                        }
                    });
        }
    }

    /**
     * The text of a record, with its parameters substituted by hand.
     * {@code MessageFormat} is not used on purpose: it treats every apostrophe as an
     * escape, so "can't open the file" would be filed as "cant open the file" - a
     * report that misspells the user's own error message is a report a maintainer
     * stops trusting.
     *
     * @param record the log record
     * @return the message, never {@code null}
     */
    static String describe(LogRecord record) {
        String raw = record.getMessage();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) {
            return raw;
        }
        String out = raw;
        for (int i = 0; i < params.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(params[i]));
        }
        return out;
    }

    /**
     * Records a failure of the reporting pipeline itself, without re-entering this
     * handler - a {@code SEVERE} record here would raise a dialog and try to report
     * the failure to report, which is the recursion every crash reporter eventually
     * discovers the hard way.
     */
    private static void reportFailureQuietly(Throwable failure) {
        java.util.logging.Logger.getLogger("lg.crashreport").log(
                Level.WARNING, "the crash report could not be started", failure);
    }

    @Override
    public void close() {
    }

    @Override
    public void flush() {
    }

    /**
     * Decides whether a record should raise the {@link ErrorDialog}: it must be
     * a {@link Level#SEVERE} record that is not benign third-party engine noise.
     * Pure and package-private for headless unit tests.
     *
     * @param record the candidate record (may be null)
     * @return true only for a severe, non-suppressed record
     */
    static boolean shouldShowDialog(LogRecord record) {
        return record != null
                && record.getLevel() == Level.SEVERE
                && !isSuppressedEngineNoise(record);
    }

    /**
     * True when the record originates from a third-party UI/web engine that logs
     * non-fatal conditions at SEVERE. Recognised purely by logger-name or
     * source-class prefix, so a genuine lg3d severe error is never masked.
     *
     * @param record the record to classify (must not be null)
     * @return true when the record is known-benign engine noise
     */
    static boolean isSuppressedEngineNoise(LogRecord record) {
        return hasSuppressedPrefix(record.getLoggerName())
                || hasSuppressedPrefix(record.getSourceClassName());
    }

    /** True when {@code name} begins with one of the suppressed engine prefixes. */
    private static boolean hasSuppressedPrefix(String name) {
        if (name == null) {
            return false;
        }
        for (String prefix : SUPPRESSED_ENGINE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders a throwable (with its cause and stack) as the dialog's trace text.
     * Pure and package-private for headless unit tests.
     *
     * @param t the throwable, may be null
     * @return the formatted trace, or an empty string when {@code t} is null
     */
    static String formatTrace(Throwable t) {
        if (t == null) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        buf.append("Thrown ").append(t).append('\n');
        buf.append("Cause ").append(t.getCause()).append('\n');
        for (StackTraceElement ste : t.getStackTrace()) {
            buf.append("    ").append(ste).append('\n');
        }
        buf.append('\n');
        return buf.toString();
    }
}
