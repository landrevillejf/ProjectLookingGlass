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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link BracketMatcher}: pair location in both
 * directions, nesting, and the literal-awareness that keeps brackets inside
 * strings and comments from counting.
 */
class BracketMatcherTest {

    private static final Language JAVA = Languages.forName("Java");

    @Test
    @DisplayName("opening and closing brackets find each other")
    void simplePairs() {
        String text = "a(bc)d";
        //            012345
        assertEquals(4, BracketMatcher.match(text, 1, JAVA));
        assertEquals(1, BracketMatcher.match(text, 4, JAVA));
        // The anchor may sit just after the bracket (caret style).
        assertEquals(4, BracketMatcher.match(text, 2, JAVA));
        assertEquals(1, BracketMatcher.match(text, 5, JAVA));
    }

    @Test
    @DisplayName("nesting is depth-counted")
    void nesting() {
        String text = "(a(b)c)";
        //  0123456
        assertEquals(6, BracketMatcher.match(text, 0, JAVA));
        // Caret just after the inner "(" pairs with the inner ")".
        assertEquals(4, BracketMatcher.match(text, 3, JAVA));
        assertEquals(2, BracketMatcher.match(text, 4, JAVA));
        assertEquals(0, BracketMatcher.match(text, 6, JAVA));
    }

    @Test
    @DisplayName("unmatched and mixed brackets return -1")
    void unmatched() {
        assertEquals(-1, BracketMatcher.match("a ( b", 2, JAVA));
        assertEquals(-1, BracketMatcher.match("(a]", 0, JAVA));
        assertEquals(-1, BracketMatcher.match("plain text", 3, JAVA));
    }

    @Test
    @DisplayName("brackets inside strings do not pair")
    void insideStrings() {
        String text = "s = \"(\"; // )";
        int open = text.indexOf('(');
        assertEquals(-1, BracketMatcher.match(text, open, JAVA));
        // But a real pair around the string still matches.
        String wrapped = "f(\")\")";
        assertEquals(5, BracketMatcher.match(wrapped, 1, JAVA));
        assertEquals(1, BracketMatcher.match(wrapped, 5, JAVA));
    }

    @Test
    @DisplayName("brackets inside comments do not pair")
    void insideComments() {
        String line = "x(); // ( dangling";
        assertEquals(-1, BracketMatcher.match(line, line.lastIndexOf('('),
                JAVA));
        String block = "/* ( */ y";
        assertEquals(-1, BracketMatcher.match(block, block.indexOf('('),
                JAVA));
    }

    @Test
    @DisplayName("a null language scans syntax-blind")
    void nullLanguage() {
        // The "(" inside the string counts when no language is given.
        String text = "\"(\"\n)";
        assertEquals(4, BracketMatcher.match(text, 1, null));
    }

    @Test
    @DisplayName("degenerate inputs return -1")
    void degenerate() {
        assertEquals(-1, BracketMatcher.match(null, 0, JAVA));
        assertEquals(-1, BracketMatcher.match("", 0, JAVA));
        assertEquals(-1, BracketMatcher.match("(x)", -1, JAVA));
        assertEquals(-1, BracketMatcher.match("(x)", 99, JAVA));
    }
}
