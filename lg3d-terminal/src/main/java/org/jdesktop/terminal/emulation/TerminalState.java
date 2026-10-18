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
package org.jdesktop.terminal.emulation;

/**
 * Holds the complete state of a virtual terminal: cursor position, character
 * attributes, scroll regions, and terminal mode flags. This is the data model
 * that the emulator mutates and the display reads.
 *
 * <p>The state is a rectangular grid of {@link CharacterCell}s, {@code rows}
 * by {@code columns}. The cursor is at {@code (cursorRow, cursorCol)} with
 * {@code 0 <= cursorRow < rows} and {@code 0 <= cursorCol < columns}.</p>
 */
public final class TerminalState {
    
    private final int rows;
    private final int columns;
    private final CharacterCell[][] grid;
    
    // Cursor
    private int cursorRow;
    private int cursorCol;
    private boolean cursorVisible = true;
    
    // Scroll region (0-based, inclusive)
    private int scrollTop;
    private int scrollBottom;
    
    // Current character attributes
    private CharacterAttr currentAttr = CharacterAttr.DEFAULT;
    
    // Terminal modes
    private boolean autoWrap = true;
    private boolean insertMode = false;
    private boolean originMode = false;
    private boolean lineFeedNewLine = false;
    
    // Saved cursor state (for DECSC/DECRC)
    private int savedCursorRow;
    private int savedCursorCol;
    private CharacterAttr savedAttr = CharacterAttr.DEFAULT;
    
    /**
     * Creates a new terminal state with the specified dimensions.
     *
     * @param rows    the number of rows
     * @param columns the number of columns
     */
    public TerminalState(int rows, int columns) {
        if (rows <= 0 || columns <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        this.rows = rows;
        this.columns = columns;
        this.grid = new CharacterCell[rows][columns];
        clear();
        
        this.scrollTop = 0;
        this.scrollBottom = rows - 1;
    }
    
    /** Clears the entire grid to spaces with default attributes. */
    public void clear() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                grid[r][c] = CharacterCell.SPACE;
            }
        }
    }
    
    /** Clears a single line to spaces with default attributes. */
    public void clearLine(int row) {
        if (row < 0 || row >= rows) return;
        for (int c = 0; c < columns; c++) {
            grid[row][c] = CharacterCell.SPACE;
        }
    }
    
    /** Clears a range of columns on a single line. */
    public void clearLineRange(int row, int startCol, int endCol) {
        if (row < 0 || row >= rows) return;
        for (int c = startCol; c <= endCol && c < columns; c++) {
            grid[row][c] = CharacterCell.SPACE;
        }
    }
    
    /** Clears from the cursor to the end of the screen. */
    public void clearFromCursorToEnd() {
        clearLineRange(cursorRow, cursorCol, columns - 1);
        for (int r = cursorRow + 1; r < rows; r++) {
            clearLine(r);
        }
    }
    
    /** Clears from the beginning of the screen to the cursor. */
    public void clearFromBeginningToCursor() {
        for (int r = 0; r < cursorRow; r++) {
            clearLine(r);
        }
        clearLineRange(cursorRow, 0, cursorCol);
    }
    
    /** Clears the entire screen. */
    public void clearScreen() {
        clear();
    }
    
    // -- Character access ---------------------------------------------------
    
    /** Returns the cell at the specified position. */
    public CharacterCell getCell(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= columns) {
            return CharacterCell.SPACE;
        }
        return grid[row][col];
    }
    
    /** Sets the cell at the specified position. */
    public void setCell(int row, int col, CharacterCell cell) {
        if (row >= 0 && row < rows && col >= 0 && col < columns) {
            grid[row][col] = cell;
        }
    }
    
    /** Writes a character at the cursor position using current attributes. */
    public void putChar(char ch) {
        if (cursorRow >= 0 && cursorRow < rows && cursorCol >= 0 && cursorCol < columns) {
            grid[cursorRow][cursorCol] = new CharacterCell(ch, currentAttr);
        }
    }
    
    // -- Cursor movement ----------------------------------------------------
    
    public int getCursorRow() { return cursorRow; }
    public int getCursorCol() { return cursorCol; }
    public boolean isCursorVisible() { return cursorVisible; }
    
    public void setCursorVisible(boolean visible) {
        this.cursorVisible = visible;
    }
    
    /** Moves the cursor to the specified position (0-based). */
    public void setCursorPosition(int row, int col) {
        this.cursorRow = clamp(row, 0, rows - 1);
        this.cursorCol = clamp(col, 0, columns - 1);
    }
    
    /** Moves the cursor up by {@code n} rows, clamped to the scroll region top. */
    public void moveCursorUp(int n) {
        int limit = (originMode || cursorRow >= scrollTop) ? scrollTop : 0;
        cursorRow = Math.max(limit, cursorRow - n);
    }
    
    /** Moves the cursor down by {@code n} rows, clamped to the scroll region bottom. */
    public void moveCursorDown(int n) {
        int limit = (originMode || cursorRow <= scrollBottom) ? scrollBottom : rows - 1;
        cursorRow = Math.min(limit, cursorRow + n);
    }
    
    /** Moves the cursor forward (right) by {@code n} columns. */
    public void moveCursorForward(int n) {
        cursorCol = Math.min(columns - 1, cursorCol + n);
    }
    
    /** Moves the cursor backward (left) by {@code n} columns. */
    public void moveCursorBackward(int n) {
        cursorCol = Math.max(0, cursorCol - n);
    }
    
    /** Moves the cursor to the beginning of the next line, scrolling if needed. */
    public void newLine() {
        if (cursorRow == scrollBottom) {
            scrollUp(1);
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
        if (lineFeedNewLine) {
            cursorCol = 0;
        }
    }
    
    /** Carriage return: moves cursor to column 0. */
    public void carriageReturn() {
        cursorCol = 0;
    }
    
    /** Tab: moves cursor to the next tab stop (every 8 columns). */
    public void tab() {
        cursorCol = Math.min(columns - 1, ((cursorCol / 8) + 1) * 8);
    }
    
    // -- Scroll region ------------------------------------------------------
    
    public int getScrollTop() { return scrollTop; }
    public int getScrollBottom() { return scrollBottom; }
    
    /** Sets the scroll region (0-based, inclusive). */
    public void setScrollRegion(int top, int bottom) {
        this.scrollTop = clamp(top, 0, rows - 1);
        this.scrollBottom = clamp(bottom, scrollTop, rows - 1);
        if (originMode) {
            setCursorPosition(0, 0);
        }
    }
    
    /** Scrolls the scroll region up by {@code n} lines. */
    public void scrollUp(int n) {
        for (int i = 0; i < n; i++) {
            // Move each line in the scroll region up by one
            for (int r = scrollTop; r < scrollBottom; r++) {
                System.arraycopy(grid[r + 1], 0, grid[r], 0, columns);
            }
            clearLine(scrollBottom);
        }
    }
    
    /** Scrolls the scroll region down by {@code n} lines. */
    public void scrollDown(int n) {
        for (int i = 0; i < n; i++) {
            for (int r = scrollBottom; r > scrollTop; r--) {
                System.arraycopy(grid[r - 1], 0, grid[r], 0, columns);
            }
            clearLine(scrollTop);
        }
    }
    
    // -- Attributes ---------------------------------------------------------
    
    public CharacterAttr getCurrentAttr() { return currentAttr; }
    public void setCurrentAttr(CharacterAttr attr) { this.currentAttr = attr; }
    
    // -- Terminal modes -----------------------------------------------------
    
    public boolean isAutoWrap() { return autoWrap; }
    public void setAutoWrap(boolean autoWrap) { this.autoWrap = autoWrap; }
    
    public boolean isInsertMode() { return insertMode; }
    public void setInsertMode(boolean insertMode) { this.insertMode = insertMode; }
    
    public boolean isOriginMode() { return originMode; }
    public void setOriginMode(boolean originMode) { this.originMode = originMode; }
    
    public boolean isLineFeedNewLine() { return lineFeedNewLine; }
    public void setLineFeedNewLine(boolean lineFeedNewLine) {
        this.lineFeedNewLine = lineFeedNewLine;
    }
    
    // -- Save/restore cursor ------------------------------------------------
    
    /** Saves the cursor position and current attributes (DECSC). */
    public void saveCursor() {
        savedCursorRow = cursorRow;
        savedCursorCol = cursorCol;
        savedAttr = currentAttr;
    }
    
    /** Restores the cursor position and current attributes (DECRC). */
    public void restoreCursor() {
        cursorRow = savedCursorRow;
        cursorCol = savedCursorCol;
        currentAttr = savedAttr;
    }
    
    // -- Dimensions ---------------------------------------------------------
    
    public int getRows() { return rows; }
    public int getColumns() { return columns; }
    
    /**
     * Resizes the terminal to new dimensions. Content is preserved where it
     * fits; new areas are filled with spaces.
     */
    public TerminalState resize(int newRows, int newCols) {
        TerminalState newState = new TerminalState(newRows, newCols);
        int copyRows = Math.min(rows, newRows);
        int copyCols = Math.min(columns, newCols);
        for (int r = 0; r < copyRows; r++) {
            System.arraycopy(grid[r], 0, newState.grid[r], 0, copyCols);
        }
        newState.cursorRow = Math.min(cursorRow, newRows - 1);
        newState.cursorCol = Math.min(cursorCol, newCols - 1);
        newState.scrollTop = Math.min(scrollTop, newRows - 1);
        newState.scrollBottom = Math.min(scrollBottom, newRows - 1);
        newState.currentAttr = currentAttr;
        newState.autoWrap = autoWrap;
        newState.insertMode = insertMode;
        newState.originMode = originMode;
        return newState;
    }
    
    // -- Utilities ----------------------------------------------------------
    
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
