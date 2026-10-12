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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the submission link - the one artefact that leaves this
 * desktop, and the reason no credential is needed. The budget, the parameter
 * spelling and the whole-line cutting are all load-bearing: a URL over the limit
 * is refused by the browser, {@code assignee} instead of {@code assignees} is
 * ignored without an error, and a mid-line cut reads as a corrupt report.
 */
class IssuePrefillTest {

    private static CrashReport report(String message, String stack) {
        return CrashReport.builder()
                .kind(CrashReport.Kind.CRASH)
                .severity(CrashReport.Severity.BLOCKER)
                .message(message)
                .stack(stack)
                .build();
    }

    @Test
    @DisplayName("the default tracker is the project's own repository")
    void defaultRepoIsTheProject() {
        assertEquals("landrevillejf/ProjectLookingGlass", IssuePrefill.DEFAULT_REPO);
        assertEquals(IssuePrefill.DEFAULT_REPO, IssuePrefill.Target.theDefault().repo());
    }

    @Test
    @DisplayName("a target without owner/name is refused where it is built")
    void blankRepoCannotBuildAUrl() {
        assertThrows(IllegalArgumentException.class, () -> IssuePrefill.Target.of("  "));
        assertThrows(IllegalArgumentException.class,
                () -> new IssuePrefill.Target(null, List.of("bug"), ""));
    }

    @Test
    @DisplayName("labels and assignee are normalised, blanks dropped")
    void targetNormalisesItsLists() {
        // Arrays.asList, not List.of: a null label is exactly the garbage a config
        // file can produce, and the target must tolerate it rather than throw.
        IssuePrefill.Target target = new IssuePrefill.Target(" a/b ",
                Arrays.asList(" crash ", "", null, "3d"), " landrevillejf ");
        assertEquals(List.of("crash", "3d"), target.labels());
        assertEquals("landrevillejf", target.assignee());
        assertEquals("a/b", target.repo());
        assertEquals("", IssuePrefill.Target.of("a/b").assignee());
    }

    @Test
    @DisplayName("the URL is the tracker's own new-issue form")
    void urlShapeIsTheNewIssueForm() {
        IssuePrefill.PrefilledUrl prefilled = IssuePrefill.url(
                report("editor crashed", "java.lang.NullPointerException: editor crashed\n"
                        + "\tat com.acme.Editor.open(Editor.java:12)"),
                new IssuePrefill.Target("a/b", List.of("crash"), "maintainer"));
        String url = prefilled.url();
        assertTrue(url.startsWith("https://github.com/a/b/issues/new?title="), url);
        assertTrue(url.contains("&labels=crash"), url);
        // The plural is the only spelling the form reads; the singular is silently
        // ignored, which would drop the notification without any error anywhere.
        assertTrue(url.contains("&assignees=maintainer"), url);
        assertFalse(url.contains("assignee=maintainer&"), url);
        assertTrue(url.contains("&body="), url);
    }

    @Test
    @DisplayName("an empty label list and an empty assignee omit their parameters")
    void emptyMetadataIsOmitted() {
        String url = IssuePrefill.url(report("boom", ""), IssuePrefill.Target.of("a/b")).url();
        assertFalse(url.contains("labels="), url);
        assertFalse(url.contains("assignees="), url);
    }

    @Test
    @DisplayName("spaces are percent-encoded, never plussed")
    void encodingIsUriNotForm() {
        String url = IssuePrefill.url(report("disk full", ""), IssuePrefill.Target.of("a/b")).url();
        assertFalse(url.contains("+"), "a '+' inside a URI query reads as a literal plus");
        assertTrue(url.contains("%20"), url);
    }

    @Test
    @DisplayName("a report whose trace is over the budget is cut on a line boundary")
    void overBudgetCutsWholeLines() {
        // 120 lines is the report's own trace ceiling, so an oversized body has to
        // be wide rather than long - which is also what a real trace looks like when
        // a lambda with captured variables reaches the bottom of the stack.
        StringBuilder trace = new StringBuilder("java.lang.RuntimeException: huge");
        for (int i = 0; i < 400; i++) {
            trace.append("\n\tat com.acme.Class").append(i)
                 .append(".callWithAVeryLongMethodNameToMakeTheLineWide").append(i)
                 .append("(Class.java:").append(i).append(")");
        }
        CrashReport huge = report("huge", trace.toString());
        IssuePrefill.PrefilledUrl prefilled =
                IssuePrefill.url(huge, IssuePrefill.Target.of("a/b"));
        assertTrue(prefilled.isTruncated(),
                "a body this wide cannot travel whole: " + bytes(prefilled.url()));
        assertTrue(bytes(prefilled.url()) <= IssuePrefill.URL_BUDGET_BYTES,
                "the browser, not the maintainer, decides what a URL can carry: "
                        + bytes(prefilled.url()) + " bytes");
        // Every line the issue shows must exist in the original, whole: a frame cut
        // in the middle looks like transmission damage, not like a size limit. The
        // pointer sentence is the one text the link adds, so it is excluded.
        for (String line : keptLines(prefilled.url())) {
            if (line.isBlank() || line.startsWith("> The full report exceeded")
                    || line.startsWith("paste it into this issue")
                    || line.startsWith("text is in the desktop")) {
                continue;
            }
            assertTrue(prefilled.fullBody().contains(line),
                    "the URL carries a fragment that is not a real line: " + line);
        }
    }

    @Test
    @DisplayName("a body under the budget travels complete")
    void underBudgetIsUntouched() {
        CrashReport small = report("brief", "java.io.IOException: brief\n\tat a.b.c(C.java:1)");
        IssuePrefill.PrefilledUrl prefilled =
                IssuePrefill.url(small, IssuePrefill.Target.of("a/b"));
        assertFalse(prefilled.isTruncated());
        assertEquals(0, prefilled.truncated());
        assertTrue(prefilled.url().contains("%23%23%23%20Error"),
                "the '### Error' heading is part of what is sent");
    }

    @Test
    @DisplayName("the budget is measured in bytes, so non-ASCII costs what it really costs")
    void budgetCountsBytesNotCharacters() {
        // A 3 000-character Japanese message is 9 000 bytes encoded: a budget held
        // in characters would happily overrun the limit on exactly the reports a
        // Japanese user files.
        String wide = "デスクトップが応答しません。".repeat(300);
        IssuePrefill.PrefilledUrl prefilled = IssuePrefill.url(
                report(wide, wide), IssuePrefill.Target.of("a/b"));
        assertTrue(prefilled.isTruncated());
        assertTrue(bytes(prefilled.url()) <= IssuePrefill.URL_BUDGET_BYTES,
                bytes(prefilled.url()) + " bytes emitted");
    }

    @Test
    @DisplayName("the full body is returned even when the URL carries less")
    void fullBodySurvivesTheCut() {
        String stack = "java.lang.RuntimeException: long\n"
                + "\tat com.acme.A.aWithALongNameToWidenTheReport(A.java:1)\n".repeat(400);
        IssuePrefill.PrefilledUrl prefilled = IssuePrefill.url(
                report("long", stack), IssuePrefill.Target.of("a/b"));
        assertTrue(prefilled.isTruncated(), "this body is wider than the link");
        assertTrue(prefilled.fullBody().contains("com.acme.A.aWithALongNameToWidenTheReport"),
                "the clipboard and the spool must get the whole trace");
        // The cut is announced, not silent: a maintainer reading a short issue has
        // to learn that a longer document exists and where to ask for it.
        String carried = String.join("\n", keptLines(prefilled.url()));
        assertTrue(carried.contains("full report exceeded"), carried);
        assertTrue(prefilled.truncated() > 0, "the count names the lines dropped");
    }

    @Test
    @DisplayName("a pathological title leaves the URL structurally valid")
    void hugeTitleStillProducesALink() {
        // The title budget caps it, but the arithmetic must not go negative and
        // produce a URL with a body the browser then refuses.
        String title = "x".repeat(4000);
        IssuePrefill.PrefilledUrl prefilled = IssuePrefill.url(
                report(title, "a".repeat(20000)), IssuePrefill.Target.of("a/b"));
        assertTrue(bytes(prefilled.url()) <= IssuePrefill.URL_BUDGET_BYTES,
                bytes(prefilled.url()) + " bytes emitted");
        assertTrue(prefilled.url().startsWith("https://github.com/a/b/issues/new?title="));
    }

    // ---------------------------------------------------------------- helpers

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The decoded body lines the finished URL actually carries. */
    private static List<String> keptLines(String url) {
        int at = url.indexOf("&body=");
        String encoded = url.substring(at + "&body=".length());
        String decoded = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        return List.of(decoded.split("\n"));
    }
}
