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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ErrorDialog}'s pure message logic and report URL -
 * the widget itself is never constructed (the test task is headless). The key
 * regression guarded here: the dead 2006 personal crash-reporter URL
 * ({@code pinaraf.robertlan.eu.org}) is gone, replaced by the project's live
 * issue tracker, and the generic/fatal/specific message variants are correct.
 */
class ErrorDialogTest {

    @BeforeEach
    void ensureNoOverride() {
        System.clearProperty(ErrorDialog.REPORT_URL_PROPERTY);
    }

    @AfterEach
    void clearOverride() {
        System.clearProperty(ErrorDialog.REPORT_URL_PROPERTY);
    }

    private static LogRecord severe(String msg) {
        LogRecord r = new LogRecord(Level.SEVERE, msg);
        r.setLoggerName("lg.displayserver");
        return r;
    }

    @Test
    @DisplayName("the report URL defaults to the live GitHub issue tracker")
    void defaultReportUrlIsLive() {
        assertEquals("https://github.com/landrevillejf/ProjectLookingGlass/issues",
                ErrorDialog.DEFAULT_REPORT_URL);
        assertEquals(ErrorDialog.DEFAULT_REPORT_URL, ErrorDialog.reportUrl());
    }

    @Test
    @DisplayName("the dead pinaraf crash-reporter URL is gone from the message")
    void deadUrlRemoved() {
        String msg = ErrorDialog.buildMessage(severe("boom"), false, true);
        assertFalse(msg.contains("pinaraf"), "no dead personal server");
        assertFalse(msg.contains("robertlan"), "no dead personal domain");
        assertFalse(msg.contains("crash_reporter"), "no dead crash-reporter path");
        assertTrue(msg.contains(ErrorDialog.DEFAULT_REPORT_URL), "uses the live tracker");
    }

    @Test
    @DisplayName("reportUrl honours a non-blank override and ignores a blank one")
    void reportUrlOverride() {
        System.setProperty(ErrorDialog.REPORT_URL_PROPERTY, "  https://tracker.example/bugs  ");
        assertEquals("https://tracker.example/bugs", ErrorDialog.reportUrl(),
                "a non-blank override wins and is trimmed");
        System.setProperty(ErrorDialog.REPORT_URL_PROPERTY, "   ");
        assertEquals(ErrorDialog.DEFAULT_REPORT_URL, ErrorDialog.reportUrl(),
                "a blank override falls back to the default");
    }

    @Test
    @DisplayName("generic message warns and picks continue-vs-fatal wording")
    void genericMessageContent() {
        String nonFatal = ErrorDialog.buildMessage(severe("disk on fire"), false, true);
        assertTrue(nonFatal.contains("A severe error occurred!"));
        assertTrue(nonFatal.contains("save your work"));
        assertTrue(nonFatal.contains("may be able to continue"));
        assertTrue(nonFatal.contains("disk on fire"), "appends the record message");
        assertFalse(nonFatal.contains("can't continue"));

        String fatal = ErrorDialog.buildMessage(severe("unrecoverable"), true, true);
        assertTrue(fatal.contains("can't continue to work"), "fatal wording when mustExit");
        assertFalse(fatal.contains("may be able to continue"));
    }

    @Test
    @DisplayName("generic=false suppresses the preamble and URL, showing only the message")
    void specificMessageOnly() {
        String msg = ErrorDialog.buildMessage(severe("Java 3D install failed"), false, false);
        assertEquals("Java 3D install failed", msg);
        assertFalse(msg.contains("save your work"));
        assertFalse(msg.contains(ErrorDialog.DEFAULT_REPORT_URL));
    }

    @Test
    @DisplayName("a null record and a null message are handled without an NPE")
    void nullSafe() {
        String noRecord = ErrorDialog.buildMessage(null, false, true);
        assertTrue(noRecord.contains("A severe error occurred!"));
        assertTrue(noRecord.contains(ErrorDialog.DEFAULT_REPORT_URL));

        LogRecord nullMsg = new LogRecord(Level.SEVERE, null);
        String msg = ErrorDialog.buildMessage(nullMsg, false, true);
        assertTrue(msg.contains("A severe error occurred!"));
        assertFalse(msg.endsWith("null"), "a null record message is not appended");
    }
}
