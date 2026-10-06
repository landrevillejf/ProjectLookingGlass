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
package org.jdesktop.lg3d.utils.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.utils.search.SearchQuery.NameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link NameMatcher} matching and relevance scoring. */
class NameMatcherTest {

    private static NameMatcher sub(String pattern, boolean caseSensitive) {
        return NameMatcher.of(NameMode.SUBSTRING, pattern, caseSensitive);
    }

    @Test
    @DisplayName("substring matching honours case sensitivity")
    void substringCase() {
        assertTrue(sub("txt", false).matches("Report.TXT"));
        assertFalse(sub("txt", true).matches("Report.TXT"));
        assertTrue(sub("TXT", true).matches("Report.TXT"));
        assertFalse(sub("xyz", false).matches("Report.TXT"));
    }

    @Test
    @DisplayName("a blank pattern matches every name")
    void blankMatchesAll() {
        assertTrue(sub("", false).matches("anything"));
        assertTrue(sub(null, false).matches("anything"));
        assertEquals(NameMatcher.SCORE_PATTERN, sub("", false).score("anything"));
    }

    @Test
    @DisplayName("null name never matches")
    void nullName() {
        assertFalse(sub("a", false).matches(null));
        assertEquals(NameMatcher.SCORE_NONE, sub("a", false).score(null));
    }

    @Test
    @DisplayName("glob matches whole names and an invalid glob matches nothing")
    void glob() {
        NameMatcher g = NameMatcher.of(NameMode.GLOB, "*.java", false);
        assertTrue(g.matches("Foo.java"));
        assertFalse(g.matches("Foo.txt"));
        assertEquals(NameMatcher.SCORE_PATTERN, g.score("Foo.java"));
        // An unparseable glob degrades to "matches nothing" instead of throwing.
        assertFalse(NameMatcher.of(NameMode.GLOB, "[", false).matches("Foo.java"));
    }

    @Test
    @DisplayName("regex uses find(), honours case, and an invalid regex matches nothing")
    void regex() {
        NameMatcher r = NameMatcher.of(NameMode.REGEX, "^rep.*\\.txt$", false);
        assertTrue(r.matches("Report.TXT"));
        assertFalse(r.matches("notes.txt"));
        assertFalse(NameMatcher.of(NameMode.REGEX, "^rep", true).matches("Report"));
        assertFalse(NameMatcher.of(NameMode.REGEX, "(", false).matches("anything"));
    }

    @Test
    @DisplayName("null mode falls back to substring")
    void nullMode() {
        assertTrue(NameMatcher.of(null, "abc", false).matches("xxABCxx"));
    }

    @Test
    @DisplayName("scoring ranks exact > prefix > mid-string contains")
    void scoreBands() {
        NameMatcher exact = sub("report.txt", false);
        assertEquals(NameMatcher.SCORE_EXACT, exact.score("Report.TXT"));

        NameMatcher prefix = sub("rep", false);
        assertEquals(NameMatcher.SCORE_PREFIX, prefix.score("report.txt"));

        NameMatcher mid = sub("ort", false);
        int midScore = mid.score("report.txt"); // idx of "ort" is 3
        assertTrue(midScore < NameMatcher.SCORE_PREFIX,
                "a mid-string hit ranks below a prefix hit");
        assertTrue(midScore > NameMatcher.SCORE_NONE);
        assertEquals(NameMatcher.SCORE_CONTAINS - 3, midScore);
    }

    @Test
    @DisplayName("a non-matching name scores zero")
    void scoreNone() {
        assertEquals(NameMatcher.SCORE_NONE, sub("zzz", false).score("report.txt"));
    }

    @Test
    @DisplayName("the mid-string penalty is capped so deep hits stay positive")
    void penaltyCapped() {
        // "q" sits far into the name; the penalty is capped at CONTAINS/2.
        NameMatcher m = sub("q", false);
        String name = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaq"; // index 30
        assertEquals(NameMatcher.SCORE_CONTAINS - NameMatcher.SCORE_CONTAINS / 2, m.score(name));
    }
}
