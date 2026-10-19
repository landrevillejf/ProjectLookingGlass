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
package org.jdesktop.lg3d.apps.games.tetris;

import java.awt.Color;

/**
 * One falling piece: a shape matrix, a colour and a board position.
 *
 * <p>Immutable. {@link #movedBy(int, int)} and {@link #rotatedCW()} return a
 * new piece instead of mutating this one, which is what lets
 * {@link TetrisEngine} probe a candidate against the wall and the settled
 * cells without a rollback step (the shape and position travel together in one
 * value, so a rejected candidate simply never becomes the current piece).</p>
 */
public final class Tetromino {

    /** The seven standard pieces, in the classic colour assignment. */
    public static final Tetromino[] PIECES = {
        new Tetromino(new int[][] {{1, 1, 1, 1}}, Color.CYAN),          // I
        new Tetromino(new int[][] {{1, 1}, {1, 1}}, Color.YELLOW),      // O
        new Tetromino(new int[][] {{0, 1, 0}, {1, 1, 1}}, Color.MAGENTA), // T
        new Tetromino(new int[][] {{1, 0, 0}, {1, 1, 1}}, Color.ORANGE),  // L
        new Tetromino(new int[][] {{0, 0, 1}, {1, 1, 1}}, Color.BLUE),    // J
        new Tetromino(new int[][] {{0, 1, 1}, {1, 1, 0}}, Color.GREEN),   // S
        new Tetromino(new int[][] {{1, 1, 0}, {0, 1, 1}}, Color.RED)      // Z
    };

    /** Occupancy grid: row-major, {@code 1} where the piece covers a cell. */
    private final int[][] shape;
    private final Color color;
    /** Board column / row of the shape's top-left cell (row 0 is the top). */
    private final int x;
    private final int y;

    /** A piece at its spawn position: horizontally centred on the top row. */
    public Tetromino(int[][] shape, Color color) {
        this(shape, color, (TetrisEngine.WIDTH - shape[0].length) / 2, 0);
    }

    private Tetromino(int[][] shape, Color color, int x, int y) {
        this.shape = shape;
        this.color = color;
        this.x = x;
        this.y = y;
    }

    public Color color() {
        return color;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    /** Cells across the shape (not the board). */
    public int width() {
        return shape[0].length;
    }

    /** Cells down the shape (not the board). */
    public int height() {
        return shape.length;
    }

    /** {@code true} where the shape covers a cell at shape-local row / column. */
    public boolean covers(int row, int col) {
        return shape[row][col] != 0;
    }

    /** This piece shifted by {@code dx} columns and {@code dy} rows. */
    public Tetromino movedBy(int dx, int dy) {
        return new Tetromino(shape, color, x + dx, y + dy);
    }

    /**
     * This piece turned a quarter turn clockwise about the shape's own centre,
     * at the same board position. An 8&times;1 strip becomes a 1&times;8 column
     * and so on: the source cell {@code [r][c]} lands at {@code [c][h-1-r]}.
     * No wall kick is attempted -- the caller tests the result and drops the
     * rotation when it does not fit, which is the classic rule.
     */
    public Tetromino rotatedCW() {
        int h = shape.length;
        int w = shape[0].length;
        int[][] turned = new int[w][h];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                turned[c][h - 1 - r] = shape[r][c];
            }
        }
        return new Tetromino(turned, color, x, y);
    }

    /** Shape-identical copy at a fresh position, for the engine's probes. */
    public Tetromino at(int newX, int newY) {
        return new Tetromino(shape, color, newX, newY);
    }

    @Override
    public String toString() {
        return "Tetromino[" + x + "," + y + " " + height() + "x" + width()
                + "]";
    }
}
