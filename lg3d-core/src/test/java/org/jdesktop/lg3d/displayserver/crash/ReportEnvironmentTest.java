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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the environment block. It is the half of a report that a
 * maintainer reads first and the half that can hurt the reporter, so the tests
 * hold both promises: every fact a fix needs is present, and no fact identifies
 * the person or the machine beyond its configuration.
 */
class ReportEnvironmentTest {

    /** A property reader that answers only what a test names. */
    private static Function<String, String> props(Map<String, String> known) {
        return known::get;
    }

    private static final Instant WHEN = Instant.EPOCH.plus(1_700_000_000L, ChronoUnit.SECONDS);

    private ReportEnvironment collected = ReportEnvironment.collect(
            WHEN,
            props(Map.of("java.version", "21.0.12", "java.vendor", "Eclipse Adoptium",
                    "os.name", "Linux", "os.version", "6.9", "os.arch", "amd64",
                    "lg3d.version", "1.68.1-dev", "lg.fws.mode", "2d")),
            Runtime.getRuntime(),
            "UTC");

    @Test
    @DisplayName("every fact a maintainer needs to reproduce is collected")
    void knownFieldsArePresent() {
        Map<String, String> values = collected.values();
        assertEquals("1.68.1-dev", values.get("lg3d version"));
        assertEquals("21.0.12 (Eclipse Adoptium)", values.get("java"));
        assertEquals("Linux 6.9 amd64", values.get("os"));
        assertEquals("2d", values.get("fws mode"));
        assertEquals("UTC", values.get("zone"));
        assertTrue(values.get("when").startsWith("2023-11-14"), values.get("when"));
        assertTrue(values.get("heap").endsWith("MiB max"), values.get("heap"));
        assertTrue(Integer.parseInt(values.get("cpus")) > 0);
        assertFalse(values.get("thread").isBlank());
        assertFalse(values.get("locale").isBlank());
        assertEquals("false", values.get("3D background"), "unset means the default");
    }

    @Test
    @DisplayName("the version falls through the three places the launchers put it")
    void versionResolutionOrder() {
        assertEquals("9.9.9", ReportEnvironment.collect(WHEN,
                props(Map.of("lg3d.version", "9.9.9", "product.version", "8.8.8")),
                Runtime.getRuntime(), "UTC").values().get("lg3d version"));
        assertEquals("8.8.8", ReportEnvironment.collect(WHEN,
                props(Map.of("product.version", "8.8.8")),
                Runtime.getRuntime(), "UTC").values().get("lg3d version"));
        // Neither property: the manifest, then "unknown" - never a crash here,
        // because an unset number costs one field and not the whole report.
        String fromManifest = ReportEnvironment.collect(WHEN, props(Map.of()),
                Runtime.getRuntime(), "UTC").values().get("lg3d version");
        assertTrue("unknown".equals(fromManifest) || fromManifest.matches("\\d[\\d.].*"),
                fromManifest);
    }

    @Test
    @DisplayName("a blank property is the same as no property")
    void blankMeansUnknown() {
        Map<String, String> values = ReportEnvironment.collect(WHEN,
                props(Map.of("java.version", "   ")), Runtime.getRuntime(), "UTC")
                .values();
        assertTrue(values.get("java").startsWith(ReportEnvironment.UNKNOWN), values.get("java"));
        assertEquals(ReportEnvironment.UNKNOWN, values.get("display config"));
    }

    @Test
    @DisplayName("an unreadable zone costs one field, not the report")
    void garbageZoneFallsBackToUtc() {
        Map<String, String> values = ReportEnvironment.collect(WHEN, props(Map.of()),
                Runtime.getRuntime(), "Not/AZone").values();
        assertEquals("Not/AZone", values.get("zone"), "stated as read, so the gap is visible");
        // The instant is still printed, still in UTC - the fallback zone is the
        // offset form, which the pattern renders as "Z" rather than the word.
        String when = values.get("when");
        assertTrue(when.startsWith("2023-11-14 22:13:20"), when);
        assertTrue(when.endsWith("UTC") || when.endsWith(" Z"), when);
        assertFalse(when.contains("Not/AZone"), when);
    }

    @Test
    @DisplayName("the block is a Markdown table whose cells cannot break the table")
    void markdownIsATable() {
        String markdown = collected.toMarkdown();
        assertTrue(markdown.startsWith("| field | value |\n| --- | --- |\n"), markdown);
        assertTrue(markdown.contains("| lg3d version | 1.68.1-dev |\n"));
        // A value containing a pipe would split a cell and silently corrupt every
        // row after it - the one property an injected display config can have.
        String escaped = ReportEnvironment.collect(WHEN,
                props(Map.of("lg.displayconfigurl", "j3d1x1|nested")),
                Runtime.getRuntime(), "UTC").toMarkdown();
        assertTrue(escaped.contains("j3d1x1\\|nested"), escaped);
    }

    @Test
    @DisplayName("the values are unmodifiable, so no later component rewrites history")
    void valuesAreReadonly() {
        // A report is evidence: the spool file, the clipboard text and the issue
        // body are built from this map at different moments, and a live handle into
        // it would let one of the three disagree with the others.
        assertThrows(UnsupportedOperationException.class,
                () -> collected.values().put("lg3d version", "forged"));
    }

    @Test
    @DisplayName("nothing in the block identifies the person or the machine")
    void noIdentityFields() {
        // The contract of the whole package, asserted where the fields are listed:
        // a public issue that carries a user name, a host name or an address turns a
        // bug report into an attack surface on the reporter's own machine. Matched
        // on the phrases, not on short substrings: "display config" contains "ip".
        for (String field : collected.values().keySet()) {
            String lower = field.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("user") || lower.contains("host")
                            || lower.contains("address") || lower.contains("email")
                            || lower.contains("name"),
                    "identity field: " + field);
        }
        assertFalse(collected.values().containsValue(System.getProperty("user.name")));
        assertFalse(collected.toMarkdown().contains(System.getProperty("user.name")));
    }

    @Test
    @DisplayName("the live collector works on this JVM with no properties set")
    void liveCollectionIsUsable() {
        ReportEnvironment live = ReportEnvironment.collect(Instant.now());
        assertEquals(12, live.values().size(), live.values().keySet().toString());
        assertFalse(live.toMarkdown().isBlank());
    }

    @Test
    @DisplayName("a null instant is stated, not invented")
    void nullTimeIsUnknown() {
        assertEquals(ReportEnvironment.UNKNOWN,
                ReportEnvironment.collect(null, props(Map.of()),
                        Runtime.getRuntime(), "UTC").values().get("when"));
    }
}
