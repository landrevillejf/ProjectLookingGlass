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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The GitHub issue URL, prefilled: how this desktop files a bug while holding no
 * credential.
 *
 * <p>An issue can be created two ways. Through the REST API, which needs a token
 * - and a token shipped inside a desktop that any user can unpack, or asked for
 * from every reporter, is either a secret published to the world or an account
 * requirement that stops the bug report dead. Neither is acceptable for a crash
 * path whose entire purpose is to survive a broken desktop. The other way is
 * <b>the tracker's own prefill link</b>: {@code /issues/new?title=&body=&labels=&assignees=}.
 * No credential leaves the machine, the maintainer stays the only person with
 * GitHub rights, and the reporter sees the issue before it becomes public - which
 * is the consent step an automatic POST can never offer.</p>
 *
 * <p>The one hard constraint the design bends around is <b>length</b>. A prefilled
 * body travels in a GET request line, and browsers, proxies and GitHub itself
 * refuse long URLs; GitHub's own documentation stays well under 8 KiB. A stack
 * trace is routinely bigger than that, so {@link #URL_BUDGET_BYTES} is spent
 * greedily: title and metadata first, then as many <em>whole lines</em> of body as
 * fit, then a pointer to the spool file that holds the rest. Cutting mid-line, or
 * cutting the trace's head, would produce a report that reads as corrupt.</p>
 *
 * <p>The budget is spent in <b>encoded</b> bytes, which is the only honest unit:
 * percent-encoding is additive per character, and one Japanese character costs
 * {@code %E3%81%9D} - nine bytes on the wire for one character in the file. A
 * budget held in characters, or in unencoded bytes, would overrun by a factor of
 * three on exactly the reports a non-Latin user files, and the browser would
 * refuse them.</p>
 *
 * <p>Pure: no browser, no I/O, no system property read after construction, so a
 * headless test pins the byte budget and the encoding rules exactly.</p>
 */
public final class IssuePrefill {

    /** The public tracker this desktop reports to. */
    public static final String DEFAULT_REPO = "landrevillejf/ProjectLookingGlass";

    /**
     * The byte ceiling for the finished URL. 7 600 leaves a margin under the 8 KiB
     * a proxy will forward: the limit is measured in encoded bytes of UTF-8 text,
     * which is what a request line is - a character count would be off by three on
     * accented text and by nine on Japanese.
     */
    public static final int URL_BUDGET_BYTES = 7600;

    /** The form of {@code URL_BUDGET_BYTES} left for the body after the rest. */
    private static final int OVERHEAD_RESERVE_BYTES = 220;

    private IssuePrefill() {
    }

    /**
     * Where a report goes: which tracker, with which labels and which assignee.
     *
     * <p>The assignee is what answers "and it mails the admin". Nothing here
     * speaks SMTP and nothing here holds a mail password: GitHub's own notification
     * system sends the e-mail when an issue is created for a named assignee, on
     * infrastructure that already knows the recipient, already rate limits itself,
     * and already survives the desktop being offline. A credential the desktop
     * cannot exfiltrate is a credential that cannot leak.</p>
     *
     * @param repo     {@code owner/name}, never blank
     * @param labels   zero or more labels; an empty list files the issue untagged
     * @param assignee the login that gets notified, may be empty
     */
    public record Target(String repo, List<String> labels, String assignee) {

        /**
         * Normalises the inputs, since a half-empty repo would build a URL that
         * 404s after the user already clicked Submit.
         *
         * @throws IllegalArgumentException when {@code repo} is blank
         */
        public Target {
            if (repo == null || repo.isBlank()) {
                throw new IllegalArgumentException("an issue target needs owner/name");
            }
            repo = repo.trim();
            List<String> kept = new ArrayList<>();
            if (labels != null) {
                for (String label : labels) {
                    if (label != null && !label.isBlank()) {
                        kept.add(label.trim());
                    }
                }
            }
            labels = List.copyOf(kept);
            assignee = (assignee == null) ? "" : assignee.trim();
        }

        /** @return the tracker with no label and no assignee */
        public static Target of(String repo) {
            return new Target(repo, List.of(), "");
        }

        /** @return the default project tracker, untagged and unassigned */
        public static Target theDefault() {
            return new Target(DEFAULT_REPO, List.of(), "");
        }
    }

    /**
     * A prefilled URL that fit the budget, with what had to be left out named.
     *
     * @param url       the link to open, always inside {@link #URL_BUDGET_BYTES}
     * @param truncated the body lines dropped because of the budget
     * @param fullBody  the entire body, for the clipboard and the spool
     */
    public record PrefilledUrl(String url, int truncated, String fullBody) {

        /** @return true when the budget forced a cut and the tracker sees less */
        public boolean isTruncated() {
            return truncated > 0;
        }
    }

    /**
     * Builds the link.
     *
     * @param report the report, already redacted
     * @param target where it goes
     * @return the URL, the dropped-line count and the whole body
     */
    public static PrefilledUrl url(CrashReport report, Target target) {
        String body = report.body();
        String title = report.title();

        StringBuilder fixed = new StringBuilder("https://github.com/")
                .append(target.repo())
                .append("/issues/new?title=").append(encode(title));
        if (!target.labels().isEmpty()) {
            fixed.append("&labels=").append(encode(String.join(",", target.labels())));
        }
        if (!target.assignee().isEmpty()) {
            // "assignees", plural, is the parameter the tracker's form reads; the
            // singular "assignee" is silently ignored, which would drop the
            // notification - the whole point of the target - without any error.
            fixed.append("&assignees=").append(encode(target.assignee()));
        }
        fixed.append("&body=");

        int fixedBytes = bytes(fixed.toString());
        int bodyBudget = URL_BUDGET_BYTES - fixedBytes - OVERHEAD_RESERVE_BYTES;
        // A pathological title (a user pasting 400 characters into the summary)
        // can leave no room at all; something has to be sent, and an URL with an
        // empty body plus a pointer to the spool still identifies the crash.
        if (bodyBudget < 0) {
            bodyBudget = 0;
        }

        String fitted = fitBody(body, bodyBudget);
        String url = fixed.toString() + encode(fitted);
        if (bytes(url) > URL_BUDGET_BYTES) {
            // Encoding expands (a space costs "%20", Markdown's pipes cost more),
            // so the byte budget is checked on the finished string and the body is
            // re-fit from the original rather than from the already-cut one -
            // cutting a truncated body would stack a second pointer sentence on the
            // first and report an issue that reads as broken twice over.
            bodyBudget = Math.max(0, URL_BUDGET_BYTES - bytes(fixed.toString())
                    - OVERHEAD_RESERVE_BYTES - SAFETY_MARGIN_BYTES);
            fitted = fitBody(body, bodyBudget);
            url = fixed.toString() + encode(fitted);
        }
        return new PrefilledUrl(url, droppedLines(body, fitted), body);
    }

    /** Room kept for the re-fit, so the expansion check above cannot oscillate. */
    private static final int SAFETY_MARGIN_BYTES = 64;

    /** @return how many body lines the budget dropped, ignoring the pointer text */
    private static int droppedLines(String fullBody, String fitted) {
        if (fitted.equals(fullBody)) {
            // The body travelled whole. Counting lines here would compare a string
            // against its own trailing-whitespace form and report a "drop" of two
            // lines for every small report - a truncation warning on a complete
            // report is worse than no counter at all.
            return 0;
        }
        String withoutPointer = fitted.endsWith(SPOOL_POINTER)
                ? fitted.substring(0, fitted.length() - SPOOL_POINTER.length())
                : fitted;
        return Math.max(0, countLines(fullBody) - countLines(withoutPointer.stripTrailing()));
    }

    /**
     * Keeps whole leading lines up to a byte ceiling, then appends the pointer the
     * maintainer needs to read the rest.
     *
     * <p>Line granularity is the rule: a trace cut in the middle of a frame name
     * makes the report unrunnable-by-search and looks like transmission damage
     * rather than like a size limit. Costs are measured after encoding, because
     * that is the string that travels, and encoding is additive per character - so
     * the running total below is the exact final size, not an estimate of it.</p>
     */
    static String fitBody(String body, int budgetBytes) {
        if (encodedBytes(body) <= budgetBytes) {
            return body;
        }
        String[] lines = body.split("\n", -1);
        StringBuilder kept = new StringBuilder();
        // The pointer sentence is reserved before the first line is taken, so the
        // finished string cannot overrun the budget by the length of the sentence
        // announcing the cut.
        int used = encodedBytes(SPOOL_POINTER);
        for (String line : lines) {
            int cost = encodedBytes(line) + NEWLINE_BYTES;
            if (used + cost > budgetBytes) {
                break;
            }
            kept.append(line).append('\n');
            used += cost;
        }
        // The final newline is stripped with the pointer's leading blank, so the
        // total charged above is one newline more than what ships: conservative.
        return kept.toString().stripTrailing() + SPOOL_POINTER;
    }

    /** A line break costs {@code %0A} once encoded: three bytes, one character. */
    private static final int NEWLINE_BYTES = 3;

    /**
     * The size the given text contributes to the finished URL. Encoding is
     * per-character, so {@code encodedBytes(a) + encodedBytes(b)} is exactly
     * {@code encodedBytes(a + b)} for the encoding used here.
     */
    private static int encodedBytes(String value) {
        return bytes(encode(value));
    }

    /**
     * The sentence that replaces what the budget could not carry. It names where
     * the complete report lives, so a truncated issue is a lead, not a dead end.
     */
    private static final String SPOOL_POINTER =
            "\n\n> The full report exceeded the prefill link's size limit; the complete"
                    + " text is in the desktop's report spool and on the clipboard,"
                    + " paste it into this issue to add it.\n";

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                // A space as "+" is form encoding; the issue form reads the query
                // as a URI, where "%20" is what survives intact.
                .replace("+", "%20");
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int countLines(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
