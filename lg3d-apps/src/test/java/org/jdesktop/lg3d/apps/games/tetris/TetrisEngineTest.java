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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Tetris rules, tested headless against {@link TetrisEngine} alone -- no
 * panel, no canvas, no timer.
 *
 * <p>Piece-specific cases are made deterministic by handing the engine a
 * {@link Random} that always returns the same piece (the engine's only call
 * into chance is {@code nextInt(bound)}), which is why no test has to hunt for
 * a seed or replay a hundred random drops. The nearly-full wells a line clear
 * and a game over need are built through {@link TetrisEngine#setCell}, the
 * package-private seam this class exists to justify.</p>
 */
class TetrisEngineTest {

    /** The seven pieces, by the index {@code Tetromino.PIECES} uses. */
    private static final int I = 0;
    private static final int O = 1;
    private static final int T = 2;

    /** The colour settled cells are painted with when a test fills the well. */
    private static final Color MARK = Color.GRAY;

    private static final int WIDTH = TetrisEngine.WIDTH;
    private static final int HEIGHT = TetrisEngine.HEIGHT;
    private static final int BOTTOM = HEIGHT - 1;

    // ------------------------------------------------------------- the idle game

    @Test
    @DisplayName("a fresh engine plays nothing until a run starts")
    void freshEngineIsIdle() {
        TetrisEngine engine = new TetrisEngine();
        assertNull(engine.getCurrent());
        assertNull(engine.getNext());
        assertFalse(engine.isRunning());
        assertFalse(engine.isPaused());
        assertFalse(engine.isGameOver());
        assertEquals(1, engine.getLevel());
        assertEquals(0, engine.getDropDistance());
        assertEquals(TetrisEngine.Turn.NOOP, engine.step());
        assertEquals(TetrisEngine.Turn.NOOP, engine.moveLeft());
        assertEquals(TetrisEngine.Turn.NOOP, engine.moveRight());
        assertEquals(TetrisEngine.Turn.NOOP, engine.moveDown());
        assertEquals(TetrisEngine.Turn.NOOP, engine.rotate());
        assertEquals(TetrisEngine.Turn.NOOP, engine.hardDrop());
        assertFalse(engine.togglePause(), "nothing to pause");
    }

    @Test
    @DisplayName("starting drops a piece in at the top and queues the next one")
    void startPutsAPieceInTheWell() {
        TetrisEngine engine = showing(O);
        engine.start();
        assertNotNull(engine.getCurrent());
        assertNotNull(engine.getNext(), "the preview must already be known");
        assertEquals(0, engine.getCurrent().y(), "the piece enters at the top row");
        assertTrue(engine.isRunning());
        assertFalse(engine.isGameOver());
    }

    @Test
    @DisplayName("stopping puts the well back as if no run had happened")
    void stopClearsTheBoard() {
        TetrisEngine engine = showing(O);
        engine.start();
        engine.hardDrop();
        engine.stop();
        assertNull(engine.getCurrent());
        assertNull(engine.getNext());
        assertFalse(engine.isGameOver(), "a stopped board is at rest, not lost");
        assertEquals(0, engine.getScore());
        assertEquals(1, engine.getLevel());
        assertEquals(0, engine.getLinesCleared());
        assertEquals(TetrisEngine.Turn.NOOP, engine.step());
    }

    @Test
    @DisplayName("pause stops the clock and resume restarts it")
    void pauseRoundTrips() {
        TetrisEngine engine = showing(O);
        engine.start();
        assertTrue(engine.togglePause());
        assertTrue(engine.isPaused());
        assertFalse(engine.isRunning());
        assertEquals(TetrisEngine.Turn.NOOP, engine.step());
        assertEquals(TetrisEngine.Turn.NOOP, engine.moveLeft());
        assertFalse(engine.togglePause());
        assertFalse(engine.isPaused());
        assertTrue(engine.isRunning());
        assertNotEquals(TetrisEngine.Turn.NOOP, engine.step());
    }

    // ------------------------------------------------------------------ movement

    @Test
    @DisplayName("side steps never carry a piece through a wall")
    void sideStepsAreBoundedByTheWalls() {
        TetrisEngine engine = showing(T);
        engine.start();
        for (int i = 0; i < WIDTH + 2; i++) {
            assertNotEquals(TetrisEngine.Turn.NOOP, engine.moveLeft());
            assertInsideWell(engine.getCurrent());
        }
        for (int i = 0; i < WIDTH + 2; i++) {
            assertNotEquals(TetrisEngine.Turn.NOOP, engine.moveRight());
            assertInsideWell(engine.getCurrent());
        }
    }

    @Test
    @DisplayName("a rotation the well has no room for is refused, piece unchanged")
    void blockedRotationIsRejected() {
        TetrisEngine engine = showing(I);
        engine.start();
        // The I enters as a 1x4 strip spanning columns 3..6 of row 0; turning it
        // would need column 3 of rows 1..3, so wall those off first.
        engine.setCell(3, 1, MARK);
        engine.setCell(3, 2, MARK);
        engine.setCell(3, 3, MARK);
        Tetromino before = engine.getCurrent();
        assertEquals(TetrisEngine.Turn.REJECTED, engine.rotate());
        assertSame(before, engine.getCurrent(), "a refused turn must not move the piece");
    }

    @Test
    @DisplayName("a turn the well accepts stands the piece up")
    void rotationTurnsThePiece() {
        TetrisEngine engine = showing(I);
        engine.start();
        assertEquals(1, engine.getCurrent().height());
        assertEquals(4, engine.getCurrent().width());
        assertEquals(TetrisEngine.Turn.MOVED, engine.rotate());
        assertEquals(4, engine.getCurrent().height());
        assertEquals(1, engine.getCurrent().width());
    }

    @Test
    @DisplayName("a piece falls one row per tick and locks on the floor")
    void fallingEndsInALock() {
        TetrisEngine engine = showing(O);
        engine.start();
        int lowest = lowestRowOf(engine.getCurrent());
        int stepped = 0;
        TetrisEngine.Turn last = TetrisEngine.Turn.NOOP;
        while (last != TetrisEngine.Turn.LINE_LOCKED && stepped < HEIGHT * 2) {
            last = engine.step();
            if (last == TetrisEngine.Turn.STEPPED) {
                stepped++;
            }
        }
        assertEquals(TetrisEngine.Turn.LINE_LOCKED, last);
        assertEquals(BOTTOM - lowest, stepped, "rows fallen before the lock");
    }

    @Test
    @DisplayName("a soft drop onto a resting piece locks it, like the clock would")
    void softDropAtRestLocks() {
        TetrisEngine engine = showing(O);
        engine.start();
        TetrisEngine.Turn last = TetrisEngine.Turn.MOVED;
        while (last == TetrisEngine.Turn.MOVED) {
            last = engine.moveDown();
        }
        assertEquals(TetrisEngine.Turn.LINE_LOCKED, last,
                "the row that could not fall is a lock, not a refusal");
        assertEquals(Tetromino.PIECES[O].color(), engine.getCell(4, BOTTOM));
    }

    @Test
    @DisplayName("the drop distance counts the rows to the floor")
    void dropDistanceReadsTheGhost() {
        TetrisEngine engine = showing(I);
        engine.start();
        assertEquals(BOTTOM, engine.getDropDistance(), "a flat piece enters at row 0");
        engine.hardDrop();
        assertEquals(BOTTOM - 1, engine.getDropDistance(),
                "the next piece stops on the one that settled");
    }

    @Test
    @DisplayName("a hard drop parks the piece on the floor and merges it into the well")
    void hardDropSettlesThePiece() {
        TetrisEngine engine = showing(O);
        engine.start();
        Color color = engine.getCurrent().color();
        assertEquals(TetrisEngine.Turn.LINE_LOCKED, engine.hardDrop());
        assertEquals(color, engine.getCell(4, BOTTOM));
        assertEquals(color, engine.getCell(5, BOTTOM));
        assertEquals(color, engine.getCell(4, BOTTOM - 1));
        assertNotNull(engine.getCurrent());
        assertEquals(0, engine.getCurrent().y(), "the next piece entered at the top");
    }

    // ------------------------------------------------------------------- scoring

    @Test
    @DisplayName("a completed row pays 40 points and disappears")
    void singleRowClears() {
        TetrisEngine engine = showing(O);
        engine.start();
        fillRowExcept(engine, BOTTOM, 4, 5);
        assertEquals(TetrisEngine.Turn.LINES_CLEARED, engine.hardDrop());
        assertEquals(1, engine.getLastLinesCleared());
        assertEquals(1, engine.getLinesCleared());
        assertEquals(40, engine.getScore());
        assertEquals(1, engine.getLevel());
        // The cleared row is gone; the piece's other half slid down into it.
        assertNull(engine.getCell(0, BOTTOM));
        assertEquals(Tetromino.PIECES[O].color(), engine.getCell(4, BOTTOM));
    }

    @Test
    @DisplayName("four rows at once pay the Tetris score")
    void fourRowsAtOnce() {
        TetrisEngine engine = showing(I);
        engine.start();
        assertEquals(TetrisEngine.Turn.MOVED, engine.rotate());
        for (int y = BOTTOM - 3; y <= BOTTOM; y++) {
            fillRowExcept(engine, y, 3);
        }
        assertEquals(TetrisEngine.Turn.LINES_CLEARED, engine.hardDrop());
        assertEquals(4, engine.getLastLinesCleared());
        assertEquals(4, engine.getLinesCleared());
        assertEquals(1200, engine.getScore());
        for (int y = BOTTOM - 3; y <= BOTTOM; y++) {
            assertNull(engine.getCell(3, y), "the shaft the piece filled is empty again");
        }
    }

    @Test
    @DisplayName("a lock that clears nothing reports zero rows")
    void plainLockClearsNoRows() {
        TetrisEngine engine = showing(O);
        engine.start();
        engine.hardDrop();
        assertEquals(0, engine.getLastLinesCleared());
        assertEquals(0, engine.getScore());
    }

    @Test
    @DisplayName("every tenth row takes the run up a level and speeds the fall")
    void levelRisesEveryTenRows() {
        TetrisEngine engine = showing(O);
        engine.setBaseDelayMs(500);
        engine.start();
        assertEquals(500, engine.getTickDelayMs());
        TetrisEngine.Turn last = TetrisEngine.Turn.NOOP;
        for (int row = 0; row < 10; row++) {
            fillRowExcept(engine, BOTTOM, 4, 5);
            last = engine.hardDrop();
        }
        assertEquals(TetrisEngine.Turn.LEVEL_UP, last, "the tenth row is the one that levels");
        assertEquals(10, engine.getLinesCleared());
        assertEquals(2, engine.getLevel());
        // All ten rows paid at level 1, and the fall got one step quicker.
        assertEquals(400, engine.getScore());
        assertEquals(470, engine.getTickDelayMs());
    }

    @Test
    @DisplayName("the fall delay is clamped to a range a slider cannot leave")
    void baseDelayIsClamped() {
        TetrisEngine engine = new TetrisEngine();
        engine.setBaseDelayMs(0);
        assertEquals(50, engine.getBaseDelayMs());
        assertEquals(50, engine.getTickDelayMs());
        engine.setBaseDelayMs(999_999);
        assertEquals(2000, engine.getBaseDelayMs());
        engine.setBaseDelayMs(-500);
        assertEquals(50, engine.getBaseDelayMs());
    }

    // ----------------------------------------------------------------- game over

    @Test
    @DisplayName("a well with no room left for the next piece ends the run")
    void noRoomLeftEndsTheRun() {
        TetrisEngine engine = showing(O);
        engine.start();
        // Leave only the 2x2 the entering piece occupies, so its lock is the
        // last thing the well can accept. Column 0 stays open throughout: a
        // pre-completed row would pay out instead of ending the run.
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 1; x < WIDTH; x++) {
                if (y > 1 || (x != 4 && x != 5)) {
                    engine.setCell(x, y, MARK);
                }
            }
        }
        assertEquals(TetrisEngine.Turn.GAME_OVER, engine.hardDrop());
        assertTrue(engine.isGameOver());
        assertFalse(engine.isRunning());
        assertNotNull(engine.getCurrent(), "the killing piece stays on the board");
        assertEquals(TetrisEngine.Turn.NOOP, engine.step());
    }

    // --------------------------------------------------------------- reproducibility

    @Test
    @DisplayName("the same seed deals the same game")
    void pieceSequenceFollowsTheSeed() {
        List<Color> first = playEight(new TetrisEngine(new Random(20261019L)));
        List<Color> second = playEight(new TetrisEngine(new Random(20261019L)));
        assertEquals(first, second);
        assertFalse(first.isEmpty());
    }

    // ---------------------------------------------------------------------- helpers

    /** An engine whose every draw is the piece of that index. */
    private static TetrisEngine showing(int pieceIndex) {
        return new TetrisEngine(new Random() {
            @Override
            public int nextInt(int bound) {
                return pieceIndex;
            }
        });
    }

    /** Eight pieces' colours, dropping each one as it arrives. */
    private static List<Color> playEight(TetrisEngine engine) {
        engine.start();
        List<Color> dealt = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            dealt.add(engine.getCurrent().color());
            engine.hardDrop();
        }
        return dealt;
    }

    private static void fillRowExcept(TetrisEngine engine, int y, int... freeColumns) {
        for (int x = 0; x < WIDTH; x++) {
            if (!holds(freeColumns, x)) {
                engine.setCell(x, y, MARK);
            }
        }
    }

    private static boolean holds(int[] values, int wanted) {
        for (int value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    /** Shape-local index of the piece's lowest occupied row. */
    private static int lowestRowOf(Tetromino piece) {
        int lowest = 0;
        for (int r = 0; r < piece.height(); r++) {
            for (int c = 0; c < piece.width(); c++) {
                if (piece.covers(r, c)) {
                    lowest = Math.max(lowest, r);
                }
            }
        }
        return lowest;
    }

    private static void assertInsideWell(Tetromino piece) {
        for (int r = 0; r < piece.height(); r++) {
            for (int c = 0; c < piece.width(); c++) {
                if (!piece.covers(r, c)) {
                    continue;
                }
                int x = piece.x() + c;
                int y = piece.y() + r;
                assertTrue(x >= 0 && x < WIDTH, "column " + x + " is outside the well");
                assertTrue(y < HEIGHT, "row " + y + " is below the floor");
            }
        }
    }
}
