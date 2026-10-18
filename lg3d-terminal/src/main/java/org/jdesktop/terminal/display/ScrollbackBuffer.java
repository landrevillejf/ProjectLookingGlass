/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file except in
 * compliance with the License. A copy of the License is available at
 * http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.terminal.display;

import org.jdesktop.terminal.emulation.CharacterCell;

/**
 * A ring buffer of terminal lines for scrollback history. When the terminal
 * scrolls up, the top line is pushed into the scrollback buffer; the user can
 * then scroll back through it. The buffer has a fixed maximum capacity; when
 * full, the oldest lines are discarded.
 *
 * <p>Each line is a {@code CharacterCell[]} of {@code columns} cells. Lines
 * are stored by reference — the caller must not mutate them after pushing.</p>
 */
public final class ScrollbackBuffer {
    
    private final int maxLines;
    private final int columns;
    private final CharacterCell[][] buffer;
    private int head;    // next write position
    private int size;    // current number of lines stored
    
    /**
     * Creates a new scrollback buffer.
     *
     * @param maxLines the maximum number of lines to retain
     * @param columns  the width of each line (in cells)
     */
    public ScrollbackBuffer(int maxLines, int columns) {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive");
        }
        this.maxLines = maxLines;
        this.columns = columns;
        this.buffer = new CharacterCell[maxLines][];
    }
    
    /**
     * Pushes a line into the scrollback buffer. If the buffer is full, the
     * oldest line is discarded.
     *
     * @param line the line to push (must be {@code columns} cells long)
     */
    public void push(CharacterCell[] line) {
        if (line == null || line.length != columns) {
            throw new IllegalArgumentException("Line must be " + columns + " cells");
        }
        buffer[head] = line;
        head = (head + 1) % maxLines;
        if (size < maxLines) {
            size++;
        }
    }
    
    /**
     * Returns the line at the given scrollback index. Index 0 is the most
     * recently pushed line; {@code size() - 1} is the oldest.
     *
     * @param index the scrollback index (0-based from newest)
     * @return the line, or null if the index is out of range
     */
    public CharacterCell[] getLine(int index) {
        if (index < 0 || index >= size) {
            return null;
        }
        int actualIndex = (head - 1 - index + maxLines) % maxLines;
        return buffer[actualIndex];
    }
    
    /** Returns the number of lines currently stored. */
    public int size() {
        return size;
    }
    
    /** Returns the maximum number of lines this buffer can hold. */
    public int getMaxLines() {
        return maxLines;
    }
    
    /** Returns the column width of each line. */
    public int getColumns() {
        return columns;
    }
    
    /** Clears all stored lines. */
    public void clear() {
        for (int i = 0; i < maxLines; i++) {
            buffer[i] = null;
        }
        head = 0;
        size = 0;
    }
    
    /**
     * Returns the selected range of lines as a string, suitable for copying
     * to the clipboard. Trailing spaces on each line are trimmed.
     *
     * @param startLine the first scrollback index (inclusive)
     * @param endLine   the last scrollback index (inclusive)
     * @return the text representation
     */
    public String getText(int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine && i < size; i++) {
            CharacterCell[] line = getLine(i);
            if (line != null) {
                int lastNonSpace = line.length - 1;
                while (lastNonSpace >= 0 && line[lastNonSpace].getChar() == ' ') {
                    lastNonSpace--;
                }
                for (int c = 0; c <= lastNonSpace; c++) {
                    sb.append(line[c].getChar());
                }
            }
            if (i < endLine) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
