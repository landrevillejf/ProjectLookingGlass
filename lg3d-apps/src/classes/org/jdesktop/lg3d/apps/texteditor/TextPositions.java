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

/**
 * Pure offset/line/column arithmetic on {@code '\n'}-normalised text, shared
 * by the caret status line, Go-to-Line and the gutter. Offsets are clamped
 * into the text so a stale caret position can never throw; lines and columns
 * are 1-based (what humans read), offsets 0-based (what documents use).
 */
public final class TextPositions {

    private TextPositions() {
        // Static utility.
    }

    /** The number of lines in the text (at least 1, empty text included). */
    public static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** The 1-based line containing {@code offset}, clamped into the text. */
    public static int lineOfOffset(String text, int offset) {
        if (text == null) {
            return 1;
        }
        int at = clamp(offset, text.length());
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The 0-based offset where 1-based {@code line} starts, clamped. */
    public static int offsetOfLine(String text, int line) {
        if (text == null) {
            return 0;
        }
        if (line <= 1) {
            return 0;
        }
        int seen = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                seen++;
                if (seen == line) {
                    return Math.min(i + 1, text.length());
                }
            }
        }
        return text.length(); // line past the end: clamp to the last offset
    }

    /** The 1-based column of {@code offset} within its line, clamped. */
    public static int columnOfOffset(String text, int offset) {
        if (text == null) {
            return 1;
        }
        int at = clamp(offset, text.length());
        return at - offsetOfLine(text, lineOfOffset(text, at)) + 1;
    }

    /** Clamps an offset into {@code [0, length]}. */
    public static int clamp(int offset, int length) {
        return Math.max(0, Math.min(offset, Math.max(0, length)));
    }
}
