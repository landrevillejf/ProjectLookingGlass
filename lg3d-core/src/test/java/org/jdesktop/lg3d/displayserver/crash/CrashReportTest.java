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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the report itself: the one object the spool, the clipboard,
 * the dialog and the browser all read. The properties that matter here are that
 * every field arrives scrubbed and clipped whether or not the caller remembered,
 * and that the three renderings of a report cannot drift apart.
 */
class CrashReportTest {

    private static final Instant WHEN = Instant.parse("2026-10-12T12:00:00Z");

    private static CrashReport.Builder manual() {
        return CrashReport.builder()
                .kind(CrashReport.Kind.MANUAL)
                .severity(CrashReport.Severity.MINOR)
                .occurredAt(WHEN)
                .environment(ReportEnvironment.collect(WHEN, name -> null,
                        Runtime.getRuntime(), "UTC"));
    }

    @Test
    @DisplayName("defaults fill a report built with nothing at all")
    void emptyReportIsStillValid() {
        CrashReport report = CrashReport.builder().build();
        assertEquals(CrashReport.Kind.CRASH, report.kind());
        assertEquals(CrashReport.Severity.MAJOR, report.severity());
        assertEquals(1, report.occurrences());
        assertEquals("", report.message());
        assertEquals("", report.stack());
        assertEquals("severe error", report.summary());
        assertEquals(8, report.id().length(), report.id());
        assertFalse(report.signature().isBlank());
    }

    @Test
    @DisplayName("a captured throwable decides severity, message and stack")
    void ofCapturesTheFailure() {
        Throwable thrown = new IllegalStateException("texture lost");
        CrashReport fatal = CrashReport.of(thrown, CrashReport.Kind.CRASH, true);
        CrashReport recovered = CrashReport.of(thrown, CrashReport.Kind.SEVERE, false);
        assertEquals(CrashReport.Severity.BLOCKER, fatal.severity());
        assertEquals(CrashReport.Severity.MAJOR, recovered.severity());
        assertEquals("texture lost", fatal.message());
        assertTrue(fatal.stack().contains("IllegalStateException"), fatal.stack());
        assertEquals("LogHandler",
                CrashReport.of(thrown, CrashReport.Kind.SEVERE, false, "LogHandler").reporter());
        assertEquals("", CrashReport.of(thrown, CrashReport.Kind.SEVERE, false, null).reporter());
    }

    @Test
    @DisplayName("every text field is redacted by the builder, not by the caller")
    void builderScrubsEverything() {
        String home = System.getProperty("user.home");
        CrashReport report = manual()
                .message("cannot open " + home + "/notes.txt for " + System.getProperty("user.name"))
                .steps("I opened " + home + "/notes.txt")
                .expected("the file opens")
                .actual("an error naming " + home)
                .build();
        for (String field : List.of(report.message(), report.steps(), report.actual())) {
            assertFalse(field.contains(home), "raw home path in: " + field);
        }
        assertTrue(report.message().contains("~"), report.message());
    }

    @Test
    @DisplayName("the budgets are enforced on the fields they name")
    void builderClipsEverything() {
        CrashReport report = manual()
                .message("m".repeat(CrashReport.TEXT_BUDGET_CHARS * 2))
                .steps("s".repeat(CrashReport.TEXT_BUDGET_CHARS * 2))
                .stack("at frame.Frame(frame.java:1)\n".repeat(CrashReport.TRACE_BUDGET_LINES * 2))
                .build();
        assertTrue(report.message().length() <= CrashReport.TEXT_BUDGET_CHARS
                + Redactor.CLIP_MARK.length(), String.valueOf(report.message().length()));
        assertTrue(report.stack().contains(Redactor.CLIP_MARK));
        assertTrue(report.stack().split("\n").length <= CrashReport.TRACE_BUDGET_LINES + 1,
                "the trace ceiling is lines, and one line more may say it was cut");
    }

    @Test
    @DisplayName("the title is severity, summary and message - inside one line")
    void titleIsCappedAndPrefixed() {
        // Built from stack text rather than a live throwable, because the frames of
        // a throwable created inside a test name the test: what is under proof here
        // is how the title is composed, not how the summary is read.
        CrashReport blocker = manual().severity(CrashReport.Severity.BLOCKER)
                .message("texture lost")
                .stack("java.lang.IllegalStateException: texture lost\n"
                        + "\tat com.acme.Texture.bind(Texture.java:42)")
                .build();
        assertTrue(blocker.title().startsWith("[CRASH] "), blocker.title());
        assertTrue(blocker.title().contains("IllegalStateException at Texture#bind"),
                blocker.title());
        assertTrue(blocker.title().endsWith(": texture lost"), blocker.title());

        CrashReport minor = manual().message("a cosmetic note").build();
        assertTrue(minor.title().startsWith("[MINOR] "), minor.title());
        assertTrue(minor.title().contains("a cosmetic note"), minor.title());

        CrashReport huge = manual().message("w".repeat(4000)).build();
        assertEquals(CrashReport.TITLE_BUDGET, huge.title().length(),
                "a long message pasted into a title hides the location on the index");
        assertTrue(huge.title().endsWith("…"), huge.title());
    }

    @Test
    @DisplayName("a report re-read from its trace text keeps the same subject")
    void titleSurvivesTheRoundTrip() {
        Throwable thrown = new RuntimeException("wrapper",
                new java.io.IOException("printer offline"));
        String rendered = CrashReport.render(thrown);
        CrashReport live = CrashReport.of(thrown, CrashReport.Kind.CRASH, false);
        // The copy a re-filed report is built from: the same severity and the same
        // message, and only the trace text instead of the live throwable. What is
        // under proof is that the subject line survives that substitution - a
        // "Caused by:" whose frames the JVM elides as "... 11 more" used to lose it
        // entirely, and a near-duplicate title opens a near-duplicate issue.
        CrashReport reread = manual()
                .kind(live.kind())
                .severity(live.severity())
                .stack(rendered)
                .message(live.message())
                .build();
        assertEquals(live.summary(), reread.summary(),
                "the root cause names the bug, not the wrapper");
        assertEquals("IOException", reread.summary().split(" at ")[0], reread.summary());
        assertEquals(live.title(), reread.title());
    }

    @Test
    @DisplayName("the body answers 'can I reproduce it' before anything else")
    void bodySectionsAreOrdered() {
        CrashReport report = manual()
                .steps("start the desktop\nopen the file manager")
                .expected("the window opens")
                .actual("nothing happens")
                .message("no such file")
                .stack("java.io.FileNotFoundException: no such file\n"
                        + "\tat com.acme.Store.open(Store.java:9)")
                .build();
        String body = report.body();
        assertTrue(body.indexOf("### What happened") < body.indexOf("**Expected:**"));
        assertTrue(body.indexOf("**Actual:**") < body.indexOf("### Error"));
        assertTrue(body.indexOf("### Error") < body.indexOf("### Environment"));
        assertTrue(body.contains("1. start the desktop\n1. open the file manager"), body);
        assertTrue(body.contains("```\njava.io.FileNotFoundException"), "the trace is fenced");
        assertTrue(body.endsWith("_\n"), body.substring(body.length() - 60));
    }

    @Test
    @DisplayName("an automatic report says so instead of inventing steps")
    void bodyWithoutManualSteps() {
        String body = CrashReport.of(new IllegalStateException("x"),
                CrashReport.Kind.CRASH, true).body();
        assertTrue(body.contains("_Captured automatically; no manual steps recorded._"), body);
        assertFalse(body.contains("**Expected:**"), "an unstated expectation is omitted");
        assertFalse(body.contains("### Evidence"), "no evidence was named");
    }

    @Test
    @DisplayName("named evidence is a claim, and the body says nothing was uploaded")
    void attachmentsAreNamedNotSent() {
        CrashReport report = manual()
                .addAttachment("screenshot.png")
                .addAttachment("  lg3d.log  ")
                .addAttachment(null)
                .addAttachment("   ")
                .attachments(List.of("scene.j3f"))
                .build();
        assertEquals(List.of("screenshot.png", "lg3d.log", "scene.j3f"), report.attachments());
        assertTrue(report.body().contains("Files are named, not uploaded"), report.body());
        assertTrue(report.body().contains("- `scene.j3f`"));
    }

    @Test
    @DisplayName("a named file keeps its name and loses its path")
    void attachmentsLoseTheirPath() {
        // Only the manual path can hand over an absolute path - a file chooser, or
        // a person pasting one - since the automatic capture names nothing. What
        // is under proof is that the directory part, which carries the account name
        // and the disk layout, never reaches a public issue.
        String home = System.getProperty("user.home");
        CrashReport report = manual()
                .addAttachment(home + "/Desktop/trip.png")
                .addAttachment("C:\\Users\\someone\\Desktop\\keys.txt")
                .addAttachment("/mnt/backup/alice/work/scene.j3f")
                .addAttachment("./")
                .addAttachment("..")
                .addAttachment("   ")
                .addAttachment(null)
                .build();
        assertEquals(List.of("trip.png", "keys.txt", "scene.j3f"), report.attachments());
        assertFalse(report.body().contains(home), "the raw home path leaked");
        assertFalse(report.body().contains("alice"), "another account name leaked");
        assertFalse(report.body().contains("Desktop"), "the directory layout leaked");
    }

    @Test
    @DisplayName("a file name is redacted like any other text, and bounded")
    void attachmentNameIsScrubbed() {
        CrashReport shaped = manual()
                .addAttachment("ghp_" + "A".repeat(36) + ".png")
                .build();
        assertEquals(List.of(Redactor.TOKEN_MASK + ".png"), shaped.attachments(),
                "a credential pasted into a file name is still a credential");

        CrashReport huge = manual().addAttachment("n".repeat(400) + ".png").build();
        String kept = huge.attachments().get(0);
        assertTrue(kept.length() <= CrashReport.ATTACHMENT_NAME_BUDGET_CHARS
                + Redactor.CLIP_MARK.length(), String.valueOf(kept.length()));
        // The archive rebuilds a report from its own text, so the clipper must be
        // idempotent or every re-filed report grows a marker and a shorter name.
        assertEquals(huge.attachments(), huge.toBuilder().build().attachments());
    }

    @Test
    @DisplayName("the footer carries the identity a maintainer will be quoted")
    void footerCarriesIdentity() {
        CrashReport report = manual().id("deadbeef").signature("abc123def456")
                .occurrences(7).reporter("BugReporterPanel").build();
        String body = report.body();
        assertTrue(body.contains("report `deadbeef`"), body);
        assertTrue(body.contains("signature `abc123def456`"), body);
        assertTrue(body.contains("kind `manual`"), body);
        assertTrue(body.contains("seen 7\u00d7"), body);
        assertTrue(body.contains("captured by BugReporterPanel"), body);
    }

    @Test
    @DisplayName("one occurrence is not announced as a repeat")
    void singleOccurrenceIsSilent() {
        assertFalse(manual().build().body().contains("seen"));
    }

    @Test
    @DisplayName("the spool document is the body plus a parseable header")
    void markdownHeaderRoundTrips() {
        CrashReport report = manual().id("cafe0001").signature("ff00ff00ff00")
                .severity(CrashReport.Severity.MAJOR).occurrences(3).build();
        String markdown = report.toMarkdown();
        assertTrue(markdown.startsWith("---\nid: cafe0001\n"), markdown);
        assertTrue(markdown.contains("signature: ff00ff00ff00\n"));
        assertTrue(markdown.contains("kind: manual\n"));
        assertTrue(markdown.contains("severity: major\n"));
        assertTrue(markdown.contains("occurrences: 3\n"));
        assertTrue(markdown.contains("occurred: 2026-10-12T12:00:00Z\n"));
        assertTrue(markdown.contains("title: " + report.title()));
        // The header line is what the spool edits on a repeat: a body that said the
        // count differently would make the file contradict itself.
        assertTrue(markdown.contains("occurrences: " + report.occurrences()));
    }

    @Test
    @DisplayName("an explicit signature or id is honoured, a computed one is stable")
    void identityCanBeOverridden() {
        Throwable thrown = new IllegalStateException("dup");
        CrashReport computed = CrashReport.of(thrown, CrashReport.Kind.CRASH, false);
        assertEquals(CrashSignature.of(thrown), computed.signature());
        CrashReport overridden = manual().signature("stored-signature").id("stored-id").build();
        assertEquals("stored-signature", overridden.signature());
        assertEquals("stored-id", overridden.id());
        // No throwable and no stack: the message still separates two different bugs.
        assertEquals(CrashReport.builder().message("a").build().signature(),
                CrashReport.builder().message("a").build().signature());
        assertFalse(CrashReport.builder().message("a").build().signature()
                .equals(CrashReport.builder().message("b").build().signature()));
    }

    @Test
    @DisplayName("the occurrence count cannot be zero or negative")
    void occurrencesAreFloored() {
        assertEquals(1, manual().occurrences(0).build().occurrences());
        assertEquals(1, manual().occurrences(-5).build().occurrences());
        assertEquals(9, manual().occurrences(9).build().occurrences());
    }

    @Test
    @DisplayName("a copy of a report changes one field and keeps every identity")
    void toBuilderCopiesFaithfully() {
        // The spool rebuilds a report to add one to its count, so a copy must be
        // the same report apart from that number: the signature is the directory
        // name, the summary is the title's subject, and a copy that re-derived
        // either would file the archived bug under a second issue.
        CrashReport original = manual()
                .message("cannot bind the texture")
                .stack("java.lang.IllegalStateException: cannot bind the texture\n"
                        + "\tat com.acme.Texture.bind(Texture.java:42)")
                .steps("start the desktop")
                .expected("the window paints")
                .actual("it does not")
                .addAttachment("screenshot.png")
                .reporter("BugReporterPanel")
                .build();
        CrashReport copy = original.toBuilder().occurrences(12).build();
        assertEquals(original.id(), copy.id());
        assertEquals(original.signature(), copy.signature());
        assertEquals(original.summary(), copy.summary());
        assertEquals(original.title(), copy.title());
        assertEquals(original.message(), copy.message());
        assertEquals(original.stack(), copy.stack());
        assertEquals(original.kind(), copy.kind());
        assertEquals(original.severity(), copy.severity());
        assertEquals(original.occurredAt(), copy.occurredAt());
        assertEquals(original.reporter(), copy.reporter());
        assertEquals(original.attachments(), copy.attachments());
        assertEquals(12, copy.occurrences());
        assertTrue(copy.body().contains("seen 12\u00d7"), copy.body());
        // A field already at its budget is not cut a second time, or the archive
        // would grow a marker per rewrite.
        CrashReport clipped = manual().message("m".repeat(CrashReport.TEXT_BUDGET_CHARS * 2))
                .build();
        assertEquals(clipped.message(), clipped.toBuilder().build().message());
        // A stated summary is honoured; a blank one leaves the derivation in charge.
        assertEquals("stated subject", manual().summary("stated subject").build().summary());
        assertEquals(original.summary(),
                original.toBuilder().summary("   ").build().summary());
    }

    @Test
    @DisplayName("rendering a null throwable is empty, not a crash in the reporter")
    void renderHandlesNull() {
        assertEquals("", CrashReport.render(null));
        assertTrue(CrashReport.render(new IllegalStateException("x"))
                .contains("IllegalStateException"));
    }

    @Test
    @DisplayName("severity derivation: only whether the desktop survived decides")
    void severityDerivation() {
        assertEquals(CrashReport.Severity.BLOCKER, CrashReport.Severity.derive(true));
        assertEquals(CrashReport.Severity.MAJOR, CrashReport.Severity.derive(false));
        assertEquals(4, CrashReport.Severity.values().length);
        assertEquals(3, CrashReport.Kind.values().length);
    }
}
