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
 * Headless tests for the south {@link OutputConsole}: block grammar, empty
 * and null normalisation, clearing, and the drop-oldest line cap.
 */
class OutputConsoleTest {

    @Test
    @DisplayName("appendOutput writes a titled rule followed by the body")
    void appendFormat() {
        OutputConsole console = new OutputConsole();
        console.appendOutput("javac output", "error: ; expected");
        assertEquals("---- javac output ----\nerror: ; expected\n\n",
                console.consoleText());
        console.appendOutput("java output", "hello");
        assertEquals(2, console.blockCount());
        assertTrue(console.consoleText().endsWith("---- java output ----\nhello\n\n"));
    }

    @Test
    @DisplayName("blank titles and empty bodies degrade to honest placeholders")
    void normalisation() {
        OutputConsole console = new OutputConsole();
        console.appendOutput(null, "");
        assertEquals("---- output ----\n(no output)\n\n", console.consoleText());
        console.clear();
        console.appendOutput("  spaced  ", "body\n");
        assertEquals("---- spaced ----\nbody\n\n", console.consoleText());
    }

    @Test
    @DisplayName("clear empties the transcript and the drop counter")
    void clearResets() {
        OutputConsole console = new OutputConsole();
        console.appendOutput("java", "out");
        console.clear();
        assertEquals("", console.consoleText());
        assertEquals(0, console.droppedLines());
    }

    @Test
    @DisplayName("a run over the cap drops the oldest lines with a note")
    void capsAtMaxLines() {
        OutputConsole console = new OutputConsole();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < OutputConsole.MAX_LINES + 10; i++) {
            big.append("line ").append(i).append('\n');
        }
        console.appendOutput("java", big.toString());
        assertTrue(console.consoleText().startsWith("[dropped "), console.consoleText());
        assertTrue(console.droppedLines() >= 10);
        // The newest content survived; the oldest did not.
        assertTrue(console.consoleText().contains("line " + (OutputConsole.MAX_LINES + 9)));
        assertFalse(console.consoleText().contains("line 0\n"));
    }
}
