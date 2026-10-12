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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jdesktop.lg3d.displayserver.crash.CrashReport;
import org.jdesktop.lg3d.displayserver.crash.CrashReporter;
import org.jdesktop.lg3d.displayserver.crash.CrashStore;
import org.jdesktop.lg3d.displayserver.crash.IssuePrefill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link LogHandler}'s decision logic: which SEVERE records
 * raise the crash dialog (genuine lg3d errors) and which are suppressed as benign
 * JavaFX/WebKit engine noise - the cam4.com "Unknown encoding type 'none' found,
 * discarding" case - the trace formatter, and the capture half of the reporting
 * wiring. The {@link Handler} itself is never {@code publish}ed here because that
 * builds a Swing dialog and the test task is headless; {@link
 * LogHandler#captureReport} is the same code with the dialog taken out.
 */
class LogHandlerTest {

    @TempDir
    Path spool;

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

    private final List<CrashReporter> createdReporters = new ArrayList<>();

    @AfterEach
    void releaseReporterThreads() {
        // Each reporter owns a daemon thread; the suite would otherwise leave one
        // per test behind, which is exactly what the class's shutdown() exists for.
        for (CrashReporter reporter : createdReporters) {
            reporter.shutdown();
        }
        createdReporters.clear();
    }

    /** A reporter wired to a temporary spool and a silent sink: no browser, no home dir. */
    private CrashReporter testReporter(CrashStore store) {
        CrashReporter reporter = new CrashReporter(store,
                new IssuePrefill.Target("acme/widgets", List.of("crash"), ""),
                CrashReporter.SILENT_SINK, CrashReporter.DEFAULT_MAX_PER_SESSION);
        createdReporters.add(reporter);
        return reporter;
    }

    @Test
    @DisplayName("the record's own text is substituted, apostrophes and all")
    void describeKeepsTheUsersWording() {
        LogRecord withParams = record(Level.SEVERE, "lg.displayserver", "cannot open {0} for {1}");
        withParams.setParameters(List.of("/etc/passwd", "read").toArray());
        assertEquals("cannot open /etc/passwd for read", LogHandler.describe(withParams));

        // MessageFormat would eat the apostrophe; a report that misspells the
        // error it is quoting is a report nobody trusts.
        LogRecord apostrophe = record(Level.SEVERE, "lg.wg", "can't bind {0}");
        apostrophe.setParameters(new Object[] {"the texture"});
        assertEquals("can't bind the texture", LogHandler.describe(apostrophe));

        assertEquals("plain", LogHandler.describe(record(Level.SEVERE, "lg.wg", "plain")));
        LogRecord unfilled = record(Level.SEVERE, "lg.wg", "{0} stays when nothing was passed");
        assertEquals("{0} stays when nothing was passed", LogHandler.describe(unfilled));
        assertEquals("", LogHandler.describe(record(Level.SEVERE, "lg.wg", null)));
        assertEquals("", LogHandler.describe(record(Level.SEVERE, "lg.wg", "   ")));
        // A null parameter is a stated null, not a swallowed placeholder.
        LogRecord nullParam = record(Level.SEVERE, "lg.wg", "bg = {0}");
        nullParam.setParameters(new Object[] {null});
        assertEquals("bg = null", LogHandler.describe(nullParam));
    }

    @Test
    @DisplayName("a fatal severe record is captured before the call returns")
    void fatalRecordIsCapturedSynchronously() {
        CrashStore store = new CrashStore(spool, 10, CrashStore.DEFAULT_MAX_BYTES);
        LogRecord dying = record(Level.SEVERE, "lg.displayserver",
                "the display server gave up");
        dying.setThrown(new SevereRuntimeError("the display server gave up", null));
        LogHandler.captureReport(dying, testReporter(store), url -> {
        });
        // No waiting, and that is the assertion: the dialog that follows this call
        // exits the desktop, so a queued capture could be lost with it.
        CrashReport report = store.list().get(0);
        assertEquals(CrashReport.Kind.SEVERE, report.kind());
        assertEquals(CrashReport.Severity.BLOCKER, report.severity());
        assertEquals("LogHandler", report.reporter());
        assertTrue(report.message().contains("gave up"), report.message());
    }

    @Test
    @DisplayName("a recoverable record is queued and its link comes back later")
    void recoverableRecordIsQueued() throws InterruptedException {
        CrashStore store = new CrashStore(spool, 10, CrashStore.DEFAULT_MAX_BYTES);
        CountDownLatch link = new CountDownLatch(1);
        LogRecord noise = record(Level.SEVERE, "lg.displayserver", "texture lost");
        noise.setThrown(new IllegalStateException("texture lost"));
        LogHandler.captureReport(noise, testReporter(store), url -> link.countDown());
        assertTrue(link.await(10, TimeUnit.SECONDS), "the reporter thread never called back");
        CrashReport report = store.list().get(0);
        assertEquals(CrashReport.Severity.MAJOR, report.severity());
        assertEquals("LogHandler", report.reporter());
    }

    @Test
    @DisplayName("a severe record with no throwable still files the message")
    void messageOnlyRecordIsNotAnEmptyReport() throws InterruptedException {
        // The report is the message: an empty one would open a blank issue and spend
        // the session's budget on nothing.
        CrashStore store = new CrashStore(spool, 10, CrashStore.DEFAULT_MAX_BYTES);
        LogRecord textOnly = record(Level.SEVERE, "lg.wg", "cannot open {0}");
        textOnly.setParameters(new Object[] {"the config file"});
        CrashReporter reporter = testReporter(store);
        CountDownLatch link = new CountDownLatch(1);
        LogHandler.captureReport(textOnly, reporter, url -> link.countDown());
        assertTrue(link.await(10, TimeUnit.SECONDS), "the queued capture never called back");
        CrashReport report = store.list().get(0);
        assertEquals("cannot open the config file", report.message());
        assertEquals(CrashReport.Kind.SEVERE, report.kind());
        assertEquals(CrashReport.Severity.MAJOR, report.severity());
    }

    @Test
    @DisplayName("a severe record saying nothing at all files nothing")
    void emptyRecordFilesNothing() throws InterruptedException {
        CrashStore store = new CrashStore(spool, 10, CrashStore.DEFAULT_MAX_BYTES);
        LogRecord blank = record(Level.SEVERE, "lg.wg", "   ");
        CountDownLatch link = new CountDownLatch(1);
        LogHandler.captureReport(blank, testReporter(store), url -> link.countDown());
        assertFalse(link.await(1, TimeUnit.SECONDS),
                "nothing was captured, so nothing was offered");
        assertTrue(store.list().isEmpty(), "an empty report is not a bug report");
    }
}
