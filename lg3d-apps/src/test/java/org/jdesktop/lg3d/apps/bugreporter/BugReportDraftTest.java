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
package org.jdesktop.lg3d.apps.bugreporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jdesktop.lg3d.displayserver.crash.CrashReport;
import org.jdesktop.lg3d.displayserver.crash.CrashSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The draft's rules, headless: when it may be filed, what it advises about,
 * and exactly what report it freezes into. The panel is tested separately;
 * everything with a consequence lives here.
 */
class BugReportDraftTest {

    private static BugReportDraft drafted() {
        return new BugReportDraft()
                .title("the task bar wraps at 1280 px")
                .steps("resize the desktop to 1280 px")
                .expected("one row of task buttons")
                .actual("two rows");
    }

    @Test
    @DisplayName("a blank subject blocks; everything else files")
    void blankTitleIsTheOnlyBlocker() {
        BugReportDraft empty = new BugReportDraft();
        assertFalse(empty.isSubmittable());
        assertEquals("Give the bug a one-line subject first.", empty.blockingReason());
        assertTrue(empty.gaps().contains("no subject"));

        // Whitespace is blank: a space pasted into the title field is not a
        // subject, and GitHub's prefill would strip it anyway.
        assertFalse(new BugReportDraft().title("   \n ").isSubmittable());

        empty.title("clock freezes");
        assertTrue(empty.isSubmittable());
        assertEquals("", empty.blockingReason());
    }

    @Test
    @DisplayName("gaps are advice: short subject, missing halves, no evidence")
    void gapsNameWhatAMaintainerMisses() {
        List<String> gaps = new BugReportDraft()
                .title("bug")
                .expected("it works")
                .gaps();
        // "bug" alone: under the advisory minimum, and expected without
        // actual is a half-sentence the reader has to finish.
        assertTrue(gaps.stream().anyMatch(g -> g.contains("very short")));
        assertTrue(gaps.stream().anyMatch(g -> g.contains("pairs")));
        assertTrue(gaps.contains("no repro steps - an unreproducible bug closes stale"));
        assertTrue(gaps.contains("no evidence named - a screenshot or a log ends the debate"));

        // The well-rounded draft advises nothing at all.
        List<String> none = drafted().addEvidence("/tmp/shot.png").gaps();
        assertEquals(List.of(), none, "a complete draft must not nag: " + none);
    }

    @Test
    @DisplayName("a hand-written draft signs on its own words")
    void manualReportCarriesTheNarrative() {
        CrashReport report = drafted().toReport();

        assertEquals(CrashReport.Kind.MANUAL, report.kind());
        assertEquals(CrashReport.Severity.MINOR, report.severity());
        assertEquals("the task bar wraps at 1280 px", report.summary());
        assertEquals(BugReportDraft.SOURCE, report.reporter());
        assertEquals("resize the desktop to 1280 px", report.steps());
        // The body's template renders the pair, and says nothing the person
        // did not type.
        assertTrue(report.body().contains("**Expected:** one row of task buttons"));
        assertTrue(report.body().contains("**Actual:** two rows"));
        assertFalse(report.body().contains("Captured automatically"));

        // The signature is the narrative key, not a trace hash: two people
        // describing one misbehaviour must collide into one issue.
        assertEquals(CrashSignature.ofNarrative(
                        "the task bar wraps at 1280 px\nresize the desktop to 1280 px"),
                report.signature());
    }

    @Test
    @DisplayName("severity travels, and the default is MINOR")
    void severityIsStatedNotGuessed() {
        assertEquals(CrashReport.Severity.MINOR, new BugReportDraft().severity(null).severity());
        CrashReport blocker = drafted().severity(CrashReport.Severity.BLOCKER).toReport();
        assertEquals(CrashReport.Severity.BLOCKER, blocker.severity());
        // The title renderer spells BLOCKER as "[CRASH]" - what a maintainer
        // scans on the index - while severity() itself stays BLOCKER.
        assertTrue(blocker.title().startsWith("[CRASH]"), blocker.title());
    }

    @Test
    @DisplayName("pasting a multi-line title keeps the subject one line")
    void titleIsCutAtTheFirstLine() {
        CrashReport report = new BugReportDraft()
                .title("3D workspaces flip on resize\nand the splash stays behind")
                .toReport();
        assertEquals("3D workspaces flip on resize", report.summary());
    }

    @Test
    @DisplayName("evidence arrives as paths and leaves as bare names")
    void evidenceLosesItsPath() {
        String home = System.getProperty("user.home");
        CrashReport report = drafted()
                .addEvidence(home + "/Desktop/shot.png")
                .addEvidence("   ")
                .addEvidence(null)
                .toReport();

        // The panel shows the full path; the report - which goes to a public
        // issue - keeps only the file's name.
        assertEquals(1, report.attachments().size());
        assertEquals("shot.png", report.attachments().get(0));
        assertFalse(report.body().contains(home));
    }

    @Test
    @DisplayName("remove and clear edit the evidence list the panel shows")
    void evidenceListIsEditable() {
        BugReportDraft draft = drafted()
                .addEvidence("/tmp/a.log")
                .addEvidence("/tmp/b.png");
        assertEquals(List.of("/tmp/a.log", "/tmp/b.png"), draft.evidenceList());
        draft.removeEvidence("/tmp/a.log");
        assertEquals(List.of("/tmp/b.png"), draft.evidenceList());
        draft.removeEvidence("/tmp/not-there.log");
        assertEquals(List.of("/tmp/b.png"), draft.evidenceList(), "removing what is not there is silence");
        draft.clearEvidence();
        assertEquals(List.of(), draft.evidenceList());
    }

    @Test
    @DisplayName("filing against a crash keeps the crash's identity")
    void linkedCrashKeepsItsSignatureTraceAndEnvironment() {
        CrashReport crash = CrashReport.of(new IllegalStateException("layout blew up"),
                CrashReport.Kind.CRASH, false, "WindowManager");
        BugReportDraft draft = drafted().linkedCrash(crash);
        assertTrue(draft.gaps().isEmpty(),
                "a linked crash supplies the evidence term: " + draft.gaps());

        CrashReport filed = draft.toReport();

        // The human half is replaced...
        assertEquals("the task bar wraps at 1280 px", filed.summary());
        assertEquals("resize the desktop to 1280 px", filed.steps());
        assertEquals(BugReportDraft.SOURCE, filed.reporter());
        // ...and the machine half is not: identity, trace and environment are
        // the capture's, or the same bug would group twice.
        assertEquals(crash.signature(), filed.signature());
        assertEquals(crash.id(), filed.id());
        assertEquals(crash.message(), filed.message());
        assertEquals(crash.stack(), filed.stack());
        assertEquals(crash.environment().toMarkdown(), filed.environment().toMarkdown());
    }

    @Test
    @DisplayName("one click is one sighting, never a double count")
    void occurrencesAreExactlyOne() {
        // CrashStore.store ADDS the incoming count to the file's tally, and a
        // re-filed report read back from the archive already carries the
        // accumulated number - so the draft must state one, whatever the
        // crash it links to has seen.
        CrashReport crash = CrashReport.of(new IllegalStateException("boom"),
                CrashReport.Kind.CRASH, false).toBuilder().occurrences(7).build();
        assertEquals(1, drafted().toReport().occurrences());
        assertEquals(1, drafted().linkedCrash(crash).toReport().occurrences());
    }

    @Test
    @DisplayName("unlinking the crash falls back to the narrative signature")
    void unlinkedCrashSignsByHand() {
        CrashReport crash = CrashReport.of(new IllegalStateException("boom"),
                CrashReport.Kind.CRASH, false);
        BugReportDraft draft = drafted().linkedCrash(crash).linkedCrash(null);
        assertNull(draft.linkedCrash());

        CrashReport filed = draft.toReport();
        assertEquals(CrashReport.Kind.MANUAL, filed.kind());
        assertNotEquals(crash.signature(), filed.signature());
        // The gap list also switches dialect: with no crash behind it, the
        // missing evidence is now the reporter's problem.
        assertTrue(draft.gaps().contains(
                "no evidence named - a screenshot or a log ends the debate"));
    }
}
