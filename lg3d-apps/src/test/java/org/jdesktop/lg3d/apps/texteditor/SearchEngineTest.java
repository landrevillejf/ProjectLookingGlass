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
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.jdesktop.lg3d.apps.texteditor.SearchEngine.Match;
import org.jdesktop.lg3d.apps.texteditor.SearchEngine.ReplaceResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure-String {@link SearchEngine}: literal and regex
 * compilation, match enumeration (including zero-length matches), directional
 * find with wrap-around, tolerant group-reference expansion and the
 * stale-match guards on replace.
 */
class SearchEngineTest {

    @Test
    @DisplayName("literal queries match metacharacters verbatim, case-folded by default")
    void literalSearch() {
        // findAll(text, query, regex, matchCase)
        List<Match> hits = SearchEngine.findAll("a.c a+c", "a.c",
                false, true);
        assertEquals(1, hits.size()); // case-sensitive: only the literal
        assertEquals(0, hits.get(0).start());
        assertEquals(3, hits.get(0).end());
        // Literal mode quotes the query: the dot never matches "abc", but
        // case-folding still applies ("A.C").
        hits = SearchEngine.findAll("a.c a+c abc A.C", "a.c", false, false);
        assertEquals(2, hits.size());
    }

    @Test
    @DisplayName("regex queries honour groups and flags")
    void regexSearch() {
        List<Match> hits = SearchEngine.findAll("cat car dog", "c[au]r",
                true, true);
        assertEquals(1, hits.size());
        assertEquals(4, hits.get(0).start());
        assertThrows(PatternSyntaxException.class,
                () -> SearchEngine.compile("([unclosed", true, true));
        // compile() quotes literals, so metacharacters are safe.
        assertDoesNotThrow(() -> SearchEngine.compile("([safe", false, true));
    }

    @Test
    @DisplayName("empty, null and invalid queries never match")
    void degenerateQueries() {
        assertTrue(SearchEngine.findAll("text", "", false, false).isEmpty());
        assertTrue(SearchEngine.findAll("text", null, false, false).isEmpty());
        assertTrue(SearchEngine.findAll(null, "x", false, false).isEmpty());
        assertTrue(SearchEngine.findAll("text", "([bad", true, false)
                .isEmpty());
        assertNull(SearchEngine.find("text", "", 0, false, false, false,
                true));
    }

    @Test
    @DisplayName("zero-length matches advance instead of livelocking")
    void zeroWidthMatches() {
        List<Match> hits = SearchEngine.findAll("abc", "x*", true, true);
        assertEquals(4, hits.size()); // before a, b, c and at EOF
        for (Match match : hits) {
            assertEquals(match.start(), match.end());
            assertEquals(0, match.length());
        }
    }

    @Test
    @DisplayName("find walks forward and backward with wrap-around")
    void directionalFind() {
        String text = "one two one two one";
        Match first = SearchEngine.find(text, "two", 0, false, true, false,
                false);
        assertNotNull(first);
        assertEquals(4, first.start());
        // Forward from inside "two" reaches the next one.
        Match next = SearchEngine.find(text, "two", 5, false, true, false,
                false);
        assertEquals(12, next.start());
        // Forward past the last match: null without wrap, first with wrap.
        assertNull(SearchEngine.find(text, "two", 13, false, true, false,
                false));
        assertEquals(4, SearchEngine.find(text, "two", 13, false, true, false,
                true).start());
        // Backward from 12 finds the match at 4; backward from 4 wraps to 12.
        assertEquals(4, SearchEngine.find(text, "two", 12, false, true, true,
                false).start());
        assertEquals(12, SearchEngine.find(text, "two", 4, false, true, true,
                true).start());
        assertNull(SearchEngine.find(text, "two", 4, false, true, true,
                false));
    }

    @Test
    @DisplayName("replaceAll counts every replacement and expands group refs")
    void replaceAll() {
        ReplaceResult result = SearchEngine.replaceAll("aXbXc", "X", "-",
                false, true);
        assertEquals("a-b-c", result.text());
        assertEquals(2, result.count());

        // Regex group references: $1, ${name} and \-escapes.
        result = SearchEngine.replaceAll("2026-10-09",
                "(\\d+)-(\\d+)-(\\d+)", "$3/$2/$1", true, true);
        assertEquals("09/10/2026", result.text());
        assertEquals(1, result.count());

        result = SearchEngine.replaceAll("ab", "(?<g>a)b", "${g}!", true,
                true);
        assertEquals("a!", result.text());

        // Literal mode inserts the replacement verbatim ($1 is not a group).
        result = SearchEngine.replaceAll("a", "a", "$1", false, true);
        assertEquals("$1", result.text());

        // No match: untouched text, zero count.
        result = SearchEngine.replaceAll("abc", "zz", "-", false, true);
        assertEquals("abc", result.text());
        assertEquals(0, result.count());
    }

    @Test
    @DisplayName("unresolvable group references stay verbatim (tolerant expansion)")
    void tolerantExpansion() {
        ReplaceResult result = SearchEngine.replaceAll("ab", "(a)b",
                "[$9]${nope}$", true, true);
        // $9 and ${nope} do not exist in "(a)b"; a dangling $ ends the spec.
        assertEquals("[$9]${nope}$", result.text());
        assertEquals(1, result.count());

        // \-escapes: \$ inserts a literal dollar, \\ a literal backslash.
        result = SearchEngine.replaceAll("ab", "(a)b", "\\$1\\\\x", true,
                true);
        assertEquals("$1\\x", result.text());
    }

    @Test
    @DisplayName("replaceOne rejects stale matches and honours groups")
    void replaceOne() {
        String text = "foo bar foo";
        Match at = new Match(8, 11);
        assertEquals("foo bar FOO",
                SearchEngine.replaceOne(text, at, "FOO", "foo", false, true));

        // A stale match (text changed, offsets no longer match) is refused.
        String changed = "foo bar baz";
        assertEquals(changed, SearchEngine.replaceOne(changed, at, "X",
                "foo", false, true));

        // Out-of-range and null inputs come back unchanged.
        assertEquals(text, SearchEngine.replaceOne(text, new Match(-1, 2),
                "X", "foo", false, true));
        assertEquals(text, SearchEngine.replaceOne(text, new Match(0, 99),
                "X", "foo", false, true));
        assertEquals(text, SearchEngine.replaceOne(text, null, "X", "foo",
                false, true));
        assertNull(SearchEngine.replaceOne(null, at, "X", "foo", false, true));

        // Regex mode with a group reference.
        Match word = new Match(0, 3);
        assertEquals("<foo> bar foo", SearchEngine.replaceOne(text, word,
                "<$1>", "(foo)", true, true));
    }

    @Test
    @DisplayName("a catastrophic regex reports no match instead of crashing")
    void stackOverflowGuard() {
        // Classic exponential-backtracking pattern against non-matching input.
        String evil = "(a+)+$";
        String input = "a".repeat(40) + "!";
        assertDoesNotThrow(() -> {
            List<Match> hits = SearchEngine.findAll(input, evil, true, true);
            assertTrue(hits.isEmpty());
        });
    }
}
