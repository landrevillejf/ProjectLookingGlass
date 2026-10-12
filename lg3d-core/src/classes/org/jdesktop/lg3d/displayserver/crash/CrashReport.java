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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One bug report, from the desktop to the tracker.
 *
 * <p>An immutable value object: the capture path builds it once, then the spool,
 * the clipboard, the dialog and the browser all read the same instance. That is
 * the whole reason it carries its own Markdown - so a report that reads one way
 * on disk, another way in the clipboard and a third way in the issue URL cannot
 * exist. The three surfaces showing the maintainer different text is the classic
 * failure of home-grown crash reporters.</p>
 *
 * <p>Every field is already redacted and clipped by the time it lands here: the
 * builder takes the raw parts and applies {@link Redactor}, so a caller cannot
 * forget to scrub. A field added later inherits the rule instead of needing to
 * remember it.</p>
 */
public final class CrashReport {

    /** Where the report came from, which decides how much of it is machine text. */
    public enum Kind {
        /** An uncaught exception that ended a thread - the stack is the evidence. */
        CRASH,
        /** A {@code SEVERE} log record that raised the error dialog. */
        SEVERE,
        /** Filed by a person from the Bug Reporter panel - the narrative is. */
        MANUAL
    }

    /** How much the bug costs the user, as the reporter states it. */
    public enum Severity {
        /** Cosmetic, or a warning the desktop recovered from. */
        TRIVIAL,
        /** One feature degraded; a workaround exists. */
        MINOR,
        /** A feature is unusable, the desktop still runs. */
        MAJOR,
        /** The desktop died or data was lost. */
        BLOCKER;

        /**
         * The default for a machine-captured report, derived rather than asked.
         * Only one fact decides it: whether the desktop could continue. A failure
         * that ended the run is the definition of a blocker; one a thread survived
         * is a major defect and never a cosmetic one - a reporter that downgraded
         * its own crash would let the maintainer's queue bury it. Which channel
         * caught it is deliberately not an input: an uncaught error on a worker
         * thread and a {@code SEVERE} record that raised the dialog cost the user
         * the same feature, and a derivation that scored them differently would
         * be measuring the logger, not the damage. A report filed by hand skips
         * this method entirely, because the person filling the form states the
         * severity in words.
         *
         * @param fatal true when the desktop could not continue
         * @return the derived severity
         */
        public static Severity derive(boolean fatal) {
            return fatal ? BLOCKER : MAJOR;
        }
    }

    /** Stamps the spool file name and the report header line. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ISO_INSTANT;

    /** Budget for the issue title; GitHub itself caps it, a longer string is cut. */
    public static final int TITLE_BUDGET = 140;

    /** Budget for the trace inside the report, in lines. */
    public static final int TRACE_BUDGET_LINES = 120;

    /** Budget for one free-text field, in characters. */
    public static final int TEXT_BUDGET_CHARS = 4000;

    /**
     * Budget for the summary, in characters. It is a title fragment, not a
     * paragraph: anything this long has stopped being a subject line.
     */
    public static final int SUMMARY_BUDGET_CHARS = 200;

    /** Budget for one named evidence file: longer than this is a pasted blob. */
    static final int ATTACHMENT_NAME_BUDGET_CHARS = 120;

    /**
     * What the body prints where a message should be. Shared with the spool's
     * reader, because the sentence "this report has no message" is a rendering
     * decision, and a reader that took it for the message would file a phantom one.
     */
    static final String NO_MESSAGE = "(no message)";

    private final String id;
    private final String signature;
    private final String summary;
    private final Kind kind;
    private final Severity severity;
    private final Instant occurredAt;
    private final String message;
    private final String stack;
    private final String steps;
    private final String expected;
    private final String actual;
    private final List<String> attachments;
    private final ReportEnvironment environment;
    private final int occurrences;
    private final String reporter;

    private CrashReport(Builder b) {
        this.id = (b.id != null) ? b.id : shortId();
        this.kind = (b.kind != null) ? b.kind : Kind.CRASH;
        this.severity = (b.severity != null) ? b.severity : Severity.MAJOR;
        this.occurredAt = (b.occurredAt != null) ? b.occurredAt : Instant.now();
        this.message = scrub(b.message, TEXT_BUDGET_CHARS);
        this.stack = clipStack(b.stack, TRACE_BUDGET_LINES);
        this.steps = scrub(b.steps, TEXT_BUDGET_CHARS);
        this.expected = scrub(b.expected, TEXT_BUDGET_CHARS);
        this.actual = scrub(b.actual, TEXT_BUDGET_CHARS);
        this.attachments = List.copyOf(b.attachments);
        this.environment = (b.environment != null) ? b.environment
                : ReportEnvironment.collect(this.occurredAt);
        this.occurrences = Math.max(1, b.occurrences);
        this.reporter = scrub(b.reporter, 120);
        // The title's subject line, read off the same text the spool keeps: a
        // throwable when the capture still holds one, the rendered stack after a
        // round trip through disk. Both paths run the same filter, so re-filing a
        // stored report produces the identical title instead of a near-duplicate
        // issue that splits the discussion in two. A caller that already knows the
        // subject - a copy made to update one field of an archived report - states
        // it, and the stated form wins, because re-deriving it from a trace whose
        // frames the JVM elides as "... 11 more" is how a report used to lose its
        // location between the archive and the issue.
        String stated = (b.summary == null) ? "" : scrub(b.summary, SUMMARY_BUDGET_CHARS);
        this.summary = !stated.isBlank()
                ? stated
                : (b.thrown != null) ? CrashSignature.describe(b.thrown)
                        : CrashSignature.describeText(this.stack);
        // The signature is the one field with a dependency on the finished values:
        // a caller that supplies a stack trace by hand still gets an identity that
        // matches the trace it handed over, so an injected throwable wins, and the
        // material falls back to the message when neither is present.
        this.signature = (b.signature != null) ? b.signature
                : (b.thrown != null ? CrashSignature.of(b.thrown)
                        : CrashSignature.of(null) + '-'
                                + Integer.toHexString(scrub(b.message, 200).hashCode()));
    }

    /** @return the 8-character report identity, used as the spool file stem */
    public String id() {
        return id;
    }

    /** @return the 12-hex grouping key two reports share when they are one bug */
    public String signature() {
        return signature;
    }

    /** @return e.g. {@code NullPointerException at LayoutManager#doLayout} */
    public String summary() {
        return summary;
    }

    /** @return how the report was captured */
    public Kind kind() {
        return kind;
    }

    /** @return how much the bug costs, as stated */
    public Severity severity() {
        return severity;
    }

    /** @return the moment of the failure */
    public Instant occurredAt() {
        return occurredAt;
    }

    /** @return the redacted one-line message or error text */
    public String message() {
        return message;
    }

    /** @return the redacted, clipped stack trace, possibly empty */
    public String stack() {
        return stack;
    }

    /** @return what the user did, when a person typed it */
    public String steps() {
        return steps;
    }

    /** @return what should have happened */
    public String expected() {
        return expected;
    }

    /** @return what happened instead */
    public String actual() {
        return actual;
    }

    /** @return file names mentioned as evidence, each reduced to its bare name */
    public List<String> attachments() {
        return attachments;
    }

    /** @return the build and machine facts */
    public ReportEnvironment environment() {
        return environment;
    }

    /** @return how many times this signature was captured in this session */
    public int occurrences() {
        return occurrences;
    }

    /** @return the component that captured the report, never a person */
    public String reporter() {
        return reporter;
    }

    /**
     * The issue title: the exception's name and where it happened, which is what
     * a maintainer scans on a list page. Capped because a long message pasted into
     * a title hides the trace's location on the tracker's index.
     *
     * @return a one-line title inside {@link #TITLE_BUDGET} characters
     */
    public String title() {
        StringBuilder sb = new StringBuilder("[");
        sb.append(severity == Severity.BLOCKER ? "CRASH" : severity.name()).append("] ");
        sb.append(summary);
        if (!message.isEmpty()) {
            sb.append(": ").append(message);
        }
        String title = sb.toString();
        return title.length() <= TITLE_BUDGET
                ? title : title.substring(0, TITLE_BUDGET - 1) + "…";
    }

    /**
     * The issue body: everything a maintainer reads, in the order that answers
     * "can I reproduce it" first.
     *
     * @return the Markdown body, ending with the report's own identity footer
     */
    public String body() {
        StringBuilder sb = new StringBuilder();
        sb.append("### What happened\n\n");
        if (!steps.isEmpty()) {
            sb.append("1. ").append(steps.replace("\n", "\n1. ")).append("\n\n");
        } else {
            sb.append("_Captured automatically; no manual steps recorded._\n\n");
        }
        if (!expected.isEmpty() || !actual.isEmpty()) {
            sb.append("**Expected:** ").append(expected.isEmpty() ? "(not stated)" : expected)
              .append("\n\n");
            sb.append("**Actual:** ").append(actual.isEmpty() ? "(not stated)" : actual)
              .append("\n\n");
        }
        sb.append("### Error\n\n");
        sb.append("`").append(message.isEmpty() ? NO_MESSAGE : message).append("`\n\n");
        if (!stack.isEmpty()) {
            sb.append("```\n").append(stack).append("\n```\n\n");
        }
        if (!attachments.isEmpty()) {
            sb.append("### Evidence named by the reporter\n\n");
            for (String attachment : attachments) {
                sb.append("- `").append(attachment).append("`\n");
            }
            // An attachment is a claim, not a delivery: this tool has no credential
            // and uploads nothing, so the file has to be dragged onto the issue page
            // by the person who has it. Saying so here prevents a maintainer
            // waiting for a screenshot that was never attached.
            sb.append("\n> Files are named, not uploaded - this desktop sends no ")
              .append("attachment. Drag them onto this issue if they still exist.\n\n");
        }
        sb.append("### Environment\n\n").append(environment.toMarkdown()).append('\n');
        sb.append("---\n");
        sb.append("_report `").append(id).append("`, signature `").append(signature)
          .append("`, kind `").append(kind.name().toLowerCase()).append('`');
        if (occurrences > 1) {
            sb.append(", seen ").append(occurrences).append("×");
        }
        if (!reporter.isEmpty()) {
            sb.append(", captured by ").append(reporter);
        }
        sb.append("_\n");
        return sb.toString();
    }

    /**
     * The spool file: the body with a YAML-ish header, so a report can be read,
     * grepped and re-filed later without the desktop running.
     *
     * @return the whole Markdown document
     */
    public String toMarkdown() {
        return "---\n"
                + "id: " + id + "\n"
                + "signature: " + signature + "\n"
                + "kind: " + kind.name().toLowerCase() + "\n"
                + "severity: " + severity.name().toLowerCase() + "\n"
                + "occurred: " + STAMP.format(occurredAt) + "\n"
                + "occurrences: " + occurrences + "\n"
                + "title: " + title() + "\n"
                + "---\n\n"
                + body();
    }

    /** @return a builder with no fields set */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder holding every value of this report, for a derived copy */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .signature(signature)
                // Stated rather than left to derivation: a copy made after a
                // round trip has no throwable, and re-reading the subject off the
                // trace is what used to make the archive and the issue disagree.
                .summary(summary)
                .kind(kind)
                .severity(severity)
                .occurredAt(occurredAt)
                .message(message)
                .stack(stack)
                .steps(steps)
                .expected(expected)
                .actual(actual)
                .attachments(attachments)
                .environment(environment)
                .occurrences(occurrences)
                .reporter(reporter);
    }

    /**
     * Captures a throwable: its rendered trace becomes the stack, its message
     * becomes the message, and the signature comes from the throwable itself so
     * the grouping matches the frames a maintainer will read.
     *
     * @param thrown the failure, must not be {@code null}
     * @param kind   how it was captured
     * @param fatal  whether the desktop could not continue
     * @return the report, environment already collected
     */
    public static CrashReport of(Throwable thrown, Kind kind, boolean fatal) {
        return of(thrown, kind, fatal, null);
    }

    /**
     * Captures a throwable and names the component that caught it, which is what
     * lets a maintainer tell an {@code SEVERE} log record from an uncaught
     * exception once both are in the same tracker.
     *
     * @param thrown the failure, must not be {@code null}
     * @param kind   how it was captured
     * @param fatal  whether the desktop could not continue
     * @param source the catching component, may be {@code null}
     * @return the report, environment already collected
     */
    public static CrashReport of(Throwable thrown, Kind kind, boolean fatal, String source) {
        return builder()
                .thrown(thrown)
                .kind(kind)
                .severity(Severity.derive(fatal))
                .occurredAt(Instant.now())
                .message(thrown.getMessage())
                .stack(render(thrown))
                .reporter(source)
                .build();
    }

    /** Renders a throwable and its cause chain the way {@code printStackTrace} does. */
    static String render(Throwable thrown) {
        if (thrown == null) {
            return "";
        }
        StringWriter writer = new StringWriter(1024);
        thrown.printStackTrace(new PrintWriter(writer, true));
        return writer.toString();
    }

    private static String scrub(String text, int budget) {
        if (text == null) {
            return "";
        }
        return Redactor.clipText(Redactor.redact(text, home(), user()), budget);
    }

    private static String clipStack(String trace, int budgetLines) {
        if (trace == null) {
            return "";
        }
        return Redactor.clipStack(Redactor.redact(trace, home(), user()), budgetLines);
    }

    private static String home() {
        return System.getProperty("user.home");
    }

    private static String user() {
        return System.getProperty("user.name");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * The report's only mutation path. Kept as a builder rather than a long
     * constructor because a report is assembled from three different sources -
     * the capture site, the environment block and, for a filed-by-hand report, a
     * Swing form - and each contributes a different subset.
     */
    public static final class Builder {
        private String id;
        private String signature;
        private String summary;
        private Kind kind;
        private Severity severity;
        private Instant occurredAt;
        private String message;
        private String stack;
        private String steps;
        private String expected;
        private String actual;
        private final List<String> attachments = new ArrayList<>();
        private ReportEnvironment environment;
        private int occurrences = 1;
        private String reporter;
        private Throwable thrown;

        private Builder() {
        }

        /** Sets the report identity; a caller re-filing a spooled report keeps it. */
        public Builder id(String value) {
            this.id = value;
            return this;
        }

        /** Overrides the computed grouping key, for a report read back from disk. */
        public Builder signature(String value) {
            this.signature = value;
            return this;
        }

        /**
         * States the subject line, for a copy of a report whose identity was
         * already derived; blank or {@code null} means "work it out from the
         * throwable or the trace", which is what a fresh capture wants.
         */
        public Builder summary(String value) {
            this.summary = value;
            return this;
        }

        /** The throwable this report describes, used for the signature. */
        public Builder thrown(Throwable value) {
            this.thrown = value;
            return this;
        }

        public Builder kind(Kind value) {
            this.kind = value;
            return this;
        }

        public Builder severity(Severity value) {
            this.severity = value;
            return this;
        }

        public Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        public Builder message(String value) {
            this.message = value;
            return this;
        }

        public Builder stack(String value) {
            this.stack = value;
            return this;
        }

        public Builder steps(String value) {
            this.steps = value;
            return this;
        }

        public Builder expected(String value) {
            this.expected = value;
            return this;
        }

        public Builder actual(String value) {
            this.actual = value;
            return this;
        }

        /**
         * Names one evidence file. Only the file's own name survives: the report
         * is a claim that the file exists so a maintainer can ask for it, and the
         * absolute path a file chooser hands back - the one form a person pastes,
         * and the only form the automatic capture never produces - would carry the
         * reporter's user name and directory layout into a public issue, which is
         * exactly what the {@link Redactor} exists to strip. A name that is not a
         * name (blank, {@code .}, {@code ..}) is dropped rather than reported.
         */
        public Builder addAttachment(String fileName) {
            if (fileName == null || fileName.isBlank()) {
                return this;
            }
            String redacted = Redactor.redact(fileName.trim(), home(), user());
            int separator = Math.max(redacted.lastIndexOf('/'), redacted.lastIndexOf('\\'));
            String name = Redactor.clipText(redacted.substring(separator + 1).trim(),
                    ATTACHMENT_NAME_BUDGET_CHARS);
            if (!name.isEmpty() && !name.equals(".") && !name.equals("..")) {
                this.attachments.add(name);
            }
            return this;
        }

        public Builder attachments(List<String> names) {
            for (String name : names) {
                addAttachment(name);
            }
            return this;
        }

        public Builder environment(ReportEnvironment value) {
            this.environment = value;
            return this;
        }

        public Builder occurrences(int value) {
            this.occurrences = value;
            return this;
        }

        public Builder reporter(String value) {
            this.reporter = value;
            return this;
        }

        /** @return the immutable report, every field scrubbed and clipped */
        public CrashReport build() {
            return new CrashReport(this);
        }
    }

    /** @return the attachment names, unmodifiable (kept for the spool's reader) */
    List<String> attachmentView() {
        return Collections.unmodifiableList(attachments);
    }
}
