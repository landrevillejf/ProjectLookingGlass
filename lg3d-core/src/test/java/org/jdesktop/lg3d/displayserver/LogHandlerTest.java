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
package org.jdesktop.lg3d.displayserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link LogHandler}'s pure decision logic: which SEVERE
 * records raise the crash dialog (genuine lg3d errors) and which are suppressed
 * as benign JavaFX/WebKit engine noise - the cam4.com "Unknown encoding type
 * 'none' found, discarding" case - plus the trace formatter. The {@link Handler}
 * itself is never {@code publish}ed here because that builds a Swing dialog and
 * the test task is headless.
 */
class LogHandlerTest {

    private static LogRecord record(Level level, String loggerName, String msg) {
        LogRecord r = new LogRecord(level, msg);
        r.setLoggerName(loggerName);
        return r;
    }

    @Test
    @DisplayName("a genuine lg3d SEVERE record still raises the dialog")
    void lgSevereShowsDialog() {
        assertTrue(LogHandler.shouldShowDialog(
                record(Level.SEVERE, "lg.displayserver", "boom")));
    }

    @Test
    @DisplayName("JavaFX/WebKit engine SEVERE noise is suppressed (the cam4 case)")
    void engineNoiseIsSuppressed() {
        // The real record: com.sun.webkit.network.URLLoader logs
        // "Unknown encoding type 'none' found, discarding" at SEVERE for a
        // Content-Encoding JavaFX 21's HTTP2Loader cannot decode.
        assertFalse(LogHandler.shouldShowDialog(
                record(Level.SEVERE, "com.sun.webkit.network.URLLoader",
                        "Unknown encoding type 'none' found, discarding")));
        assertFalse(LogHandler.shouldShowDialog(
                record(Level.SEVERE, "com.sun.javafx.scene.Node", "layout")));
        assertFalse(LogHandler.shouldShowDialog(
                record(Level.SEVERE, "javafx.scene.web.WebEngine", "load")));
    }

    @Test
    @DisplayName("suppression also matches the source class when the logger name differs")
    void suppressionAlsoMatchesSourceClass() {
        LogRecord r = record(Level.SEVERE, "some.other.Logger", "msg");
        r.setSourceClassName("com.sun.webkit.network.HTTP2Loader");
        assertTrue(LogHandler.isSuppressedEngineNoise(r));
        assertFalse(LogHandler.shouldShowDialog(r));
    }

    @Test
    @DisplayName("non-SEVERE levels and a null record never raise the dialog")
    void onlySevereShows() {
        assertFalse(LogHandler.shouldShowDialog(record(Level.WARNING, "lg.wg", "warn")));
        assertFalse(LogHandler.shouldShowDialog(record(Level.INFO, "lg.sg", "info")));
        assertFalse(LogHandler.shouldShowDialog(null));
    }

    @Test
    @DisplayName("a null logger and source name is not mistaken for engine noise")
    void nullNamesAreNotSuppressed() {
        LogRecord r = new LogRecord(Level.SEVERE, "msg");
        r.setLoggerName(null);
        r.setSourceClassName(null);
        assertFalse(LogHandler.isSuppressedEngineNoise(r));
        assertTrue(LogHandler.shouldShowDialog(r));
    }

    @Test
    @DisplayName("formatTrace renders the throwable, its cause and stack; empty for null")
    void formatTraceRendersThrowable() {
        assertEquals("", LogHandler.formatTrace(null));

        Throwable cause = new IllegalStateException("root");
        Throwable t = new RuntimeException("wrapper", cause);
        String trace = LogHandler.formatTrace(t);
        assertTrue(trace.contains("Thrown " + t), "includes the throwable");
        assertTrue(trace.contains("Cause " + cause), "includes the cause");
        assertTrue(trace.contains(LogHandlerTest.class.getName()), "includes stack frames");
        assertTrue(trace.endsWith("\n"), "trace is newline-terminated");
    }
}
