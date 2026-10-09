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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure helpers {@link SmartIndent} (indent
 * extraction, level generation, next-line indent rules, bracket pairing)
 * and {@link TextPositions} (line/column arithmetic).
 */
class SmartIndentTest {

    @Test
    @DisplayName("indentOf returns the leading whitespace only")
    void indentOf() {
        assertEquals("    ", SmartIndent.indentOf("    code"));
        assertEquals("\t ", SmartIndent.indentOf("\t x"));
        assertEquals("", SmartIndent.indentOf("code"));
        assertEquals("", SmartIndent.indentOf(null));
        assertEquals("  ", SmartIndent.indentOf("  "));
    }

    @Test
    @DisplayName("level emits N spaces or a hard tab, clamped")
    void level() {
        assertEquals("    ", SmartIndent.level(4, false));
        assertEquals("\t", SmartIndent.level(4, true));
        assertEquals(" ", SmartIndent.level(0, false));
        assertEquals(" ".repeat(32), SmartIndent.level(99, false));
    }

    @Test
    @DisplayName("nextLineIndent inherits the indent and adds a level after openers")
    void nextLineIndent() {
        assertEquals("        ", SmartIndent.nextLineIndent("    if (x) {",
                4, false));
        assertEquals("      ", SmartIndent.nextLineIndent("  items: [", 4,
                false));
        // A plain line keeps its own indent.
        assertEquals("  ", SmartIndent.nextLineIndent("  return x;", 4,
                false));
        // A continuation backslash also indents.
        assertEquals("    ", SmartIndent.nextLineIndent("  make \\", 2,
                false));
        // Blank and null lines.
        assertEquals("", SmartIndent.nextLineIndent("", 4, false));
        assertEquals("", SmartIndent.nextLineIndent(null, 4, false));
        // Hard tabs.
        assertEquals("\t\t", SmartIndent.nextLineIndent("\tif (x) {", 4,
                true));
    }

    @Test
    @DisplayName("bracket recognition and pairing")
    void brackets() {
        assertTrue(SmartIndent.isBracket('('));
        assertTrue(SmartIndent.isBracket('}'));
        assertFalse(SmartIndent.isBracket('<'));
        assertEquals(')', SmartIndent.pairOf('('));
        assertEquals('[', SmartIndent.pairOf(']'));
        assertEquals('{', SmartIndent.pairOf('}'));
        assertEquals((char) 0, SmartIndent.pairOf('x'));
    }

    @Test
    @DisplayName("countLines counts 1-based lines including the trailing one")
    void countLines() {
        assertEquals(1, TextPositions.countLines(""));
        assertEquals(1, TextPositions.countLines("one"));
        assertEquals(3, TextPositions.countLines("a\nb\nc"));
        assertEquals(3, TextPositions.countLines("a\nb\n"));
        assertEquals(1, TextPositions.countLines(null));
    }

    @Test
    @DisplayName("lineOfOffset and columnOfOffset are 1-based and clamped")
    void lineAndColumn() {
        String text = "ab\ncde\nf";
        assertEquals(1, TextPositions.lineOfOffset(text, 0));
        assertEquals(1, TextPositions.lineOfOffset(text, 2));
        assertEquals(2, TextPositions.lineOfOffset(text, 3));
        assertEquals(3, TextPositions.lineOfOffset(text, 7));
        // Clamping: negative -> first line, past EOF -> last line.
        assertEquals(1, TextPositions.lineOfOffset(text, -5));
        assertEquals(3, TextPositions.lineOfOffset(text, 999));
        assertEquals(1, TextPositions.lineOfOffset(null, 0));

        assertEquals(1, TextPositions.columnOfOffset(text, 0));
        assertEquals(3, TextPositions.columnOfOffset(text, 2));
        assertEquals(1, TextPositions.columnOfOffset(text, 3));
        assertEquals(1, TextPositions.columnOfOffset(text, 7));
    }

    @Test
    @DisplayName("offsetOfLine returns the start offset of a 1-based line")
    void offsetOfLine() {
        String text = "ab\ncde\nf";
        assertEquals(0, TextPositions.offsetOfLine(text, 1));
        assertEquals(3, TextPositions.offsetOfLine(text, 2));
        assertEquals(7, TextPositions.offsetOfLine(text, 3));
        // Out-of-range lines clamp.
        assertEquals(0, TextPositions.offsetOfLine(text, 0));
        assertEquals(text.length(), TextPositions.offsetOfLine(text, 99));
    }

    @Test
    @DisplayName("clamp bounds an offset into [0, length]")
    void clamp() {
        assertEquals(0, TextPositions.clamp(-3, 10));
        assertEquals(5, TextPositions.clamp(5, 10));
        assertEquals(10, TextPositions.clamp(99, 10));
    }
}
