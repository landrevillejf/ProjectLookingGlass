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

import java.util.ArrayList;
import java.util.List;

import org.jdesktop.lg3d.displayserver.crash.CrashReport;
import org.jdesktop.lg3d.displayserver.crash.CrashSignature;

/**
 * What the Bug Reporter form currently says, before any of it becomes a
 * report. This class is the headless half of the app - no Swing, no X11, no
 * {@code Desktop} - so the rules about when a draft may be filed, and what a
 * filed report looks like, are testable the way {@code lg3d-apps/AGENTS.md}
 * demands of pure logic. The panel is deliberately dumb: it mirrors widgets
 * into a draft and paints whatever the draft says back.
 *
 * <p>A draft is mutable because it is live form state, edited one keystroke
 * at a time; a {@link CrashReport} stays immutable, and {@link #toReport()}
 * is the one place where mutable meets final. Calling it does not submit
 * anything - the engine's {@code CrashReporter.report} still owns spooling
 * and the prefill URL - it just freezes the current answers.</p>
 *
 * <p>Nothing here checks length: {@code CrashReport} budgets and redacts on
 * construction, and a form that rejected input the report would have clipped
 * anyway is a form that argues with its own model.</p>
 */
public final class BugReportDraft {

    /**
     * The name the engine stamps into {@code CrashReport.reporter()}. It is
     * a component, not a person - the same rule the automatic capture
     * follows - and it must match the panel that produced it, because a
     * maintainer triaging "captured by BugReporterPanel" wants to know which
     * UI the reporter was looking at.
     */
    public static final String SOURCE = "BugReporterPanel";

    /**
     * Below this many characters a title is a keystroke, not a subject. Not
     * a blocker - see {@link #gaps()} - because "short" is advice a reporter
     * can overrule, while "blank" is a report no maintainer can index.
     */
    static final int MIN_TITLE_CHARS = 15;

    private String title = "";
    private String steps = "";
    private String expected = "";
    private String actual = "";
    private CrashReport.Severity severity = CrashReport.Severity.MINOR;
    private final List<String> evidence = new ArrayList<>();

    /**
     * The spooled crash this draft was seeded from, or {@code null} for a
     * bug noticed by hand. Held as an attached <em>identity</em>: when the
     * person files against a real crash, the resulting report must keep the
     * crash's signature, trace and environment, or the archive groups the
     * same bug twice - the exact near-duplicate failure the round-trip rules
     * in {@code lg3d-core/AGENTS.md} exist to prevent.
     */
    private CrashReport linkedCrash;

    /** Mirrors the subject field. */
    public BugReportDraft title(String value) {
        this.title = value == null ? "" : value;
        return this;
    }

    /** Mirrors the repro-steps area. */
    public BugReportDraft steps(String value) {
        this.steps = value == null ? "" : value;
        return this;
    }

    /** Mirrors the expected-result field. */
    public BugReportDraft expected(String value) {
        this.expected = value == null ? "" : value;
        return this;
    }

    /** Mirrors the actual-result field. */
    public BugReportDraft actual(String value) {
        this.actual = value == null ? "" : value;
        return this;
    }

    /** Mirrors the severity combo. */
    public BugReportDraft severity(CrashReport.Severity value) {
        if (value != null) {
            this.severity = value;
        }
        return this;
    }

    /**
     * Adds one evidence file by path. The path is kept here - the panel has
     * to show what was picked and the person may want to remove it again -
     * but never survives into the report: {@code CrashReport.Builder.
     * addAttachment} reduces every entry to a bare, redacted file name.
     */
    public BugReportDraft addEvidence(String path) {
        if (path != null && !path.isBlank()) {
            evidence.add(path.trim());
        }
        return this;
    }

    /** Removes one evidence path, matching the panel's selection. */
    public BugReportDraft removeEvidence(String path) {
        if (path != null) {
            evidence.remove(path);
        }
        return this;
    }

    /** Drops every evidence path - the Clear button does this, among others. */
    public BugReportDraft clearEvidence() {
        evidence.clear();
        return this;
    }

    /** The evidence paths as shown in the panel, in pick order. */
    public List<String> evidenceList() {
        return List.copyOf(evidence);
    }

    /** Seeds the draft from a spooled crash; {@code null} means "by hand". */
    public BugReportDraft linkedCrash(CrashReport report) {
        this.linkedCrash = report;
        return this;
    }

    /** @return the crash this draft files against, or {@code null} */
    public CrashReport linkedCrash() {
        return linkedCrash;
    }

    public String title() {
        return title;
    }

    public String steps() {
        return steps;
    }

    public CrashReport.Severity severity() {
        return severity;
    }

    /**
     * The one hard rule of the form: an issue without a subject cannot be
     * filed, because the tracker's index is the subject and GitHub's
     * prefill URL rejects an empty title anyway. Everything else a reporter
     * can leave out at their own cost - see {@link #gaps()}.
     *
     * @return whether {@link #toReport()} may be called at all
     */
    public boolean isSubmittable() {
        return !title.isBlank();
    }

    /**
     * Why submission is disabled, phrased for the status line rather than a
     * dialog - the house rule forbids modal dialogs in a hosted panel.
     *
     * @return the reason, or {@code ""} when {@link #isSubmittable()}
     */
    public String blockingReason() {
        return isSubmittable() ? "" : "Give the bug a one-line subject first.";
    }

    /**
     * Advice, not a gate: what a maintainer will find missing. Shown as a
     * quiet line under the form so a person who knows the crash is obvious
     * ("title says it all") can still file it. A draft with no gaps is the
     * report a triager can act on without a round trip of comments.
     *
     * @return zero or more short sentences, empty when nothing is missing
     */
    public List<String> gaps() {
        List<String> gaps = new ArrayList<>();
        if (title.isBlank()) {
            gaps.add("no subject");
        } else if (title.trim().length() < MIN_TITLE_CHARS) {
            gaps.add("the subject is very short - one more word helps the index");
        }
        if (steps.isBlank()) {
            gaps.add(linkedCrash != null
                    ? "no repro steps - the capture knows the trace, not what you did"
                    : "no repro steps - an unreproducible bug closes stale");
        }
        if (expected.isBlank() ^ actual.isBlank()) {
            // One of the two is a half-sentence: "expected X" without "what
            // actually happened" makes the reader guess the failure, and the
            // reverse makes them guess the intent. Filed together or not at
            // all is the contract the body's template assumes.
            gaps.add("expected and actual go in pairs - one of them is missing");
        }
        if (evidence.isEmpty() && linkedCrash == null) {
            gaps.add("no evidence named - a screenshot or a log ends the debate");
        }
        return List.copyOf(gaps);
    }

    /**
     * Freezes the draft into the report the engine will spool and prefill.
     * Two shapes, and the difference is the whole point of the crash picker:
     *
     * <ul>
     *   <li><b>Against a spooled crash</b> - the crash's own identity wins:
     *       its signature, trace, message and environment are kept, and the
     *       human fields are layered on top. Filing "by hand" text against a
     *       captured crash while replacing the signature would put the same
     *       bug in the tracker twice.</li>
     *   <li><b>No crash</b> - a {@code MANUAL} report signing on its own
     *       words, through {@code CrashSignature.ofNarrative}, which is what
     *       lets two people describing one misbehaviour collide into one
     *       issue.</li>
     * </ul>
     *
     * <p>{@code occurrences(1)} is deliberate and load-bearing:
     * {@code CrashStore.store} <em>adds</em> the incoming count to the file's
     * tally, and a re-filed report read back from the archive already carries
     * the accumulated number - clicking Submit once must register one
     * sighting, not double the history.</p>
     *
     * @return the report; never {@code null}, but only meaningful when
     *         {@link #isSubmittable()}
     */
    public CrashReport toReport() {
        String subject = title.trim();
        int newline = subject.indexOf('\n');
        if (newline >= 0) {
            // The title field is technically multi-line through a paste; the
            // subject of an issue never is.
            subject = subject.substring(0, newline).trim();
        }
        if (linkedCrash != null) {
            return linkedCrash.toBuilder()
                    .summary(subject)
                    .severity(severity)
                    .steps(steps)
                    .expected(expected)
                    .actual(actual)
                    .attachments(evidence)
                    .reporter(SOURCE)
                    .occurrences(1)
                    .build();
        }
        return CrashReport.builder()
                .kind(CrashReport.Kind.MANUAL)
                .severity(severity)
                .summary(subject)
                // Narrative signs cover subject plus steps: two reports of
                // one bug usually differ in the subject's phrasing and agree
                // on what was done, so both feed the key.
                .signature(CrashSignature.ofNarrative(subject + "\n" + steps))
                .steps(steps)
                .expected(expected)
                .actual(actual)
                .attachments(evidence)
                .reporter(SOURCE)
                .occurrences(1)
                .build();
    }
}
