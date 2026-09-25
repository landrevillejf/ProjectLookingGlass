/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AppSearch}, the pure type-to-search matcher behind the 2D start
 * menu: ranking tiers, case-insensitivity, the result cap, description/command
 * fallback, and the empty/null cases. No Swing, fully deterministic.
 */
class AppSearchTest {

    private static ItemSpec item(String name) {
        return new ItemSpec(name, "swingapp " + name, null, "Main", null);
    }

    private static ItemSpec item(String name, String command, String desc) {
        return new ItemSpec(name, command, desc, "Main", null);
    }

    private static List<String> names(List<ItemSpec> items) {
        List<String> out = new ArrayList<>();
        for (ItemSpec i : items) {
            out.add(i.getName());
        }
        return out;
    }

    @Test
    @DisplayName("blank, whitespace-only or null query matches nothing")
    void blankQueryMatchesNothing() {
        List<ItemSpec> all = List.of(item("Calculator"), item("Terminal"));
        assertTrue(AppSearch.match(all, "").isEmpty());
        assertTrue(AppSearch.match(all, "   ").isEmpty());
        assertTrue(AppSearch.match(all, null).isEmpty());
    }

    @Test
    @DisplayName("a null item list is tolerated")
    void nullListTolerated() {
        assertTrue(AppSearch.match(null, "calc").isEmpty());
    }

    @Test
    @DisplayName("a query that matches nothing returns an empty list")
    void noMatch() {
        List<ItemSpec> all = List.of(item("Calculator"), item("Terminal"));
        assertTrue(AppSearch.match(all, "zzzz").isEmpty());
    }

    @Test
    @DisplayName("exact name beats prefix beats word-boundary beats substring")
    void ranksAllTiers() {
        List<ItemSpec> all = List.of(
                item("Woman"),                       // NAME substring of "man"
                item("Man"),                         // EXACT
                item("File Manager"),                // WORD boundary
                item("Xman"),                        // NAME substring
                item("Gimp", "swingapp gimp", "manual image editor")); // DETAIL (desc)
        assertEquals(
                List.of("Man", "File Manager", "Woman", "Xman", "Gimp"),
                names(AppSearch.match(all, "man")),
                // Woman and Xman tie at NAME rank and keep descriptor order.
                "tiers rank best-first; equal tiers stay in input order");
    }

    @Test
    @DisplayName("an exact match outranks a prefix match")
    void exactBeatsPrefix() {
        List<ItemSpec> all = List.of(item("Paint Shop"), item("Paint"));
        assertEquals(List.of("Paint", "Paint Shop"),
                names(AppSearch.match(all, "paint")));
    }

    @Test
    @DisplayName("a prefix outranks an interior substring")
    void prefixBeatsSubstring() {
        List<ItemSpec> all = List.of(item("Xpaint"), item("Paint pro"));
        assertEquals(List.of("Paint pro", "Xpaint"),
                names(AppSearch.match(all, "paint")));
    }

    @Test
    @DisplayName("a word-boundary match outranks an interior substring")
    void wordBoundaryBeatsSubstring() {
        List<ItemSpec> all = List.of(item("Superman"), item("Super man"));
        assertEquals(List.of("Super man", "Superman"),
                names(AppSearch.match(all, "man")));
    }

    @Test
    @DisplayName("matching is case-insensitive on name, description and command")
    void caseInsensitive() {
        assertEquals(List.of("Terminal"),
                names(AppSearch.match(List.of(item("Terminal")), "TERM")));
        assertEquals(1, AppSearch.match(
                List.of(item("Foo", "zbarcommand", "nothing")), "ZBAR").size(),
                "command match");
        assertEquals(1, AppSearch.match(
                List.of(item("Baz", "swingapp baz", "QUX detail")), "qux").size(),
                "description match");
    }

    @Test
    @DisplayName("results are capped at MAX_RESULTS, keeping input order")
    void cappedAtMaxResults() {
        List<ItemSpec> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(item(String.format("app%02d", i)));
        }
        List<ItemSpec> result = AppSearch.match(all, "app");
        assertEquals(AppSearch.MAX_RESULTS, result.size());
        assertEquals("app00", result.get(0).getName());
        assertEquals("app11", result.get(result.size() - 1).getName());
    }
}
