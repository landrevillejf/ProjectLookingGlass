/**
 * Project Looking Glass
 *
 * $RCSfile: LogHandler.java,v $
 *
 * Copyright (c) 2005, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - modernization:
 * benign third-party engine SEVERE records (JavaFX/WebKit) no longer raise the
 * crash dialog, the show/suppress decision and trace formatting are testable
 * pure methods, and the dialog is built on the EDT. All Rights Reserved.
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

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.swing.SwingUtilities;

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

    private ErrorDialog dialog;

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
