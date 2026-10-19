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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The Tetris rules as a headless state machine: a 10&times;20 grid of settled
 * cells, one falling piece, a previewed next piece, score / level / lines and
 * the run in progress.
 *
 * <p>No Swing and no timer: the engine is advanced by {@link #step()} once per
 * tick and by the player's input methods between ticks, and it answers with a
 * fresh state the view paints. That split is what makes the rules testable
 * without a display (see {@code TetrisEngineTest}) and what lets the same
 * engine drive both desktop surfaces -- the {@code SwingNode}-hosted 3D window
 * and the 2D MDI frame.</p>
 *
 * <p>Every method must be called on the event dispatch thread: there is no
 * internal synchronisation, only state a single caller advances.</p>
 */
public final class TetrisEngine {

    /** Columns of the well. */
    public static final int WIDTH = 10;
    /** Rows of the well. */
    public static final int HEIGHT = 20;

    /** Rows cleared at once, index 0 = a single line. The classic table. */
    private static final int[] LINE_SCORES = {40, 100, 300, 1200};
    /** Delay of the first level, in milliseconds. */
    private static final int BASE_DELAY_MS = 500;
    /** Milliseconds shaved off per level, and the floor the fall never passes. */
    private static final int DELAY_PER_LEVEL_MS = 30;
    private static final int MIN_DELAY_MS = 50;
    /** Rows a level costs. */
    private static final int LINES_PER_LEVEL = 10;

    /**
     * What a turn of the game did, so the view can pick a sound and a repaint
     * without re-deriving it from the state. {@code lines} is the number of
     * rows completed by {@link #LINE_LOCKED} (0 when nothing was cleared).
     */
    public enum Turn {
        /** Nothing happened: not running, paused or already over. */
        NOOP,
        /** A shift or a rotation the well accepted. */
        MOVED,
        /** A shift or a rotation the wall or a settled cell rejected. */
        REJECTED,
        /** A row fell and the piece came to rest where it was. */
        STEPPED,
        /** The piece locked into the grid (no row completed). */
        LINE_LOCKED,
        /** The piece locked and rows were completed; {@code lines} counts them. */
        LINES_CLEARED,
        /** The lock took the run to a new level. */
        LEVEL_UP,
        /** The lock left no room for the next piece: the run is over. */
        GAME_OVER
    }

    /** Settled cells, row-major; {@code null} is an empty cell. */
    private final Color[][] grid = new Color[HEIGHT][WIDTH];
    private final Random random;

    private Tetromino current;
    private Tetromino next;
    private int score;
    private int level = 1;
    private int linesCleared;
    private boolean running;
    private boolean paused;
    private int clearedThisTurn;

    /** An engine fed by {@link java.security.SecureRandom}-free but unseeded RNG. */
    public TetrisEngine() {
        this(new Random());
    }

    /**
     * An engine whose piece sequence is reproducible -- the seam
     * {@code TetrisEngineTest} uses to build a nearly-full well and know which
     * piece lands next.
     */
    public TetrisEngine(Random random) {
        this.random = random;
    }

    // ------------------------------------------------------------------ state

    public boolean isRunning() {
        return running && !paused && current != null;
    }

    public boolean isPaused() {
        return running && paused;
    }

    public boolean isGameOver() {
        return current != null && !running && !paused;
    }

    public int getScore() {
        return score;
    }

    public int getLevel() {
        return level;
    }

    public int getLinesCleared() {
        return linesCleared;
    }

    /** The piece now falling, or {@code null} before the first {@link #start()}. */
    public Tetromino getCurrent() {
        return current;
    }

    /** The piece queued after the current one, or {@code null} before a start. */
    public Tetromino getNext() {
        return next;
    }

    /** Settled colour at board column / row; {@code null} when the cell is free. */
    public Color getCell(int x, int y) {
        return grid[y][x];
    }

    /** Rows completed by the {@link Turn} the caller was just handed. */
    public int getLastLinesCleared() {
        return clearedThisTurn;
    }

    /**
     * The tick interval the level asks for: 500 ms at level 1, 30 ms shorter
     * per level, never below 50 ms. The view re-reads this after every
     * {@link Turn#LEVEL_UP}.
     */
    public int getTickDelayMs() {
        return Math.max(MIN_DELAY_MS,
                BASE_DELAY_MS - (level - 1) * DELAY_PER_LEVEL_MS);
    }

    // ------------------------------------------------------------------- play

    /** Clears the well, zeroes the counters and drops the first piece in. */
    public Turn start() {
        for (Color[] row : grid) {
            Arrays.fill(row, null);
        }
        score = 0;
        level = 1;
        linesCleared = 0;
        clearedThisTurn = 0;
        paused = false;
        running = true;
        next = null;
        spawn();
        return Turn.STEPPED;
    }

    /** Stops the run and puts the well back at rest (a closed window). */
    public void stop() {
        running = false;
        paused = false;
    }

    /** Pauses a run in progress, or resumes it; a no-op once the run is over. */
    public Turn togglePause() {
        if (!running || current == null) {
            return Turn.NOOP;
        }
        paused = !paused;
        return Turn.NOOP;
    }

    public Turn moveLeft() {
        return shift(-1, 0);
    }

    public Turn moveRight() {
        return shift(1, 0);
    }

    /** One row down when the well allows it; the piece locks when it cannot. */
    public Turn moveDown() {
        return shift(0, 1);
    }

    public Turn rotate() {
        if (!isRunning()) {
            return Turn.NOOP;
        }
        Tetromino turned = current.rotatedCW();
        if (!fits(turned)) {
            return Turn.REJECTED;
        }
        current = turned;
        return Turn.MOVED;
    }

    /**
     * Drops the piece to the floor of its column and locks it at once -- the
     * space bar. Returns {@link Turn#LINES_CLEARED} / {@link Turn#GAME_OVER}
     * exactly as a lock reached by falling one row at a time would.
     */
    public Turn hardDrop() {
        if (!isRunning()) {
            return Turn.NOOP;
        }
        while (fits(current.movedBy(0, 1))) {
            current = current.movedBy(0, 1);
        }
        return lock();
    }

    /**
     * Advances the run by one tick: the piece falls a row, or comes to rest
     * when the row below is not free. This is the only method the timer calls.
     */
    public Turn step() {
        if (!isRunning()) {
            return Turn.NOOP;
        }
        if (fits(current.movedBy(0, 1))) {
            current = current.movedBy(0, 1);
            return Turn.STEPPED;
        }
        return lock();
    }

    /** Rows a piece would take to reach the floor from where it stands. */
    public int getDropDistance() {
        if (current == null) {
            return 0;
        }
        int rows = 0;
        while (fits(current.movedBy(0, rows + 1))) {
            rows++;
        }
        return rows;
    }

    // ------------------------------------------------------------------ inner

    private Turn shift(int dx, int dy) {
        if (!isRunning()) {
            return Turn.NOOP;
        }
        Tetromino candidate = current.movedBy(dx, dy);
        if (!fits(candidate)) {
            // A blocked sideways move is simply ignored; a blocked downward one
            // is the piece landing, which is what the classic game does.
            return dy > 0 ? lock() : Turn.REJECTED;
        }
        current = candidate;
        return Turn.MOVED;
    }

    /** The piece settles into the grid, completed rows pay, the next piece comes. */
    private Turn lock() {
        for (int r = 0; r < current.height(); r++) {
            for (int c = 0; c < current.width(); c++) {
                if (current.covers(r, c)) {
                    int gy = current.y() + r;
                    int gx = current.x() + c;
                    // A cell above the well is not lost: it is the overflow
                    // that ends the run, and the rows below still have to pay.
                    if (gy >= 0) {
                        grid[gy][gx] = current.color();
                    }
                }
            }
        }
        clearedThisTurn = clearRows();
        spawn();
        if (!running) {
            return Turn.GAME_OVER;
        }
        if (clearedThisTurn > 0) {
            return levelUpIfNeeded() ? Turn.LEVEL_UP : Turn.LINES_CLEARED;
        }
        return Turn.LINE_LOCKED;
    }

    /**
     * Removes every full row and drops the rows above it into the gap. Returns
     * how many rows went, and pays the classic score for that many at the
     * level the rows completed on.
     */
    private int clearRows() {
        List<Integer> full = new ArrayList<>();
        for (int y = 0; y < HEIGHT; y++) {
            if (isFull(y)) {
                full.add(y);
            }
        }
        if (full.isEmpty()) {
            return 0;
        }
        for (int y : full) {
            // Rows above the cleared one slide down into it, then row 0 empties.
            for (int row = y; row > 0; row--) {
                System.arraycopy(grid[row - 1], 0, grid[row], 0, WIDTH);
            }
            Arrays.fill(grid[0], null);
        }
        int count = full.size();
        score += LINE_SCORES[Math.min(count - 1, LINE_SCORES.length - 1)] * level;
        linesCleared += count;
        return count;
    }

    private boolean isFull(int y) {
        for (int x = 0; x < WIDTH; x++) {
            if (grid[y][x] == null) {
                return false;
            }
        }
        return true;
    }

    /** A level every {@value #LINES_PER_LEVEL} rows; reports whether one was reached. */
    private boolean levelUpIfNeeded() {
        int reached = linesCleared / LINES_PER_LEVEL + 1;
        if (reached <= level) {
            return false;
        }
        level = reached;
        return true;
    }

    /** Queues the next piece under the current one, or ends the run. */
    private void spawn() {
        current = next != null ? next : randomPiece();
        next = randomPiece();
        if (!fits(current)) {
            // The well is full to the top: the piece stays visible for the
            // game-over frame, but the run is done.
            running = false;
            paused = false;
            return;
        }
        running = true;
        paused = false;
    }

    private Tetromino randomPiece() {
        return Tetromino.PIECES[random.nextInt(Tetromino.PIECES.length)];
    }

    /**
     * Whether every cell the piece covers lies inside the well and on a free
     * cell. A piece may stick out above row 0 -- that is how a stack that has
     * reached the top still lets its last piece in -- but never past a wall or
     * the floor.
     */
    private boolean fits(Tetromino piece) {
        for (int r = 0; r < piece.height(); r++) {
            for (int c = 0; c < piece.width(); c++) {
                if (!piece.covers(r, c)) {
                    continue;
                }
                int gx = piece.x() + c;
                int gy = piece.y() + r;
                if (gx < 0 || gx >= WIDTH || gy >= HEIGHT) {
                    return false;
                }
                if (gy >= 0 && grid[gy][gx] != null) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------- test seam

    /**
     * Forces a settled cell. Package-private and used only by
     * {@code TetrisEngineTest} to build a nearly-full well without playing a
     * hundred moves; the view never calls it.
     */
    void setCell(int x, int y, Color color) {
        grid[y][x] = color;
    }
}
