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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The piece as a value type: the seven shapes and their colours, where a piece
 * enters, what a turn does to the matrix, and the immutability the engine's
 * "probe a candidate, keep it only if it fits" rule rests on.
 */
class TetrominoTest {

    @Test
    @DisplayName("the set is the seven classic pieces in the classic colours")
    void sevenPiecesWithTheirColours() {
        assertEquals(7, Tetromino.PIECES.length);
        assertEquals(Color.CYAN, Tetromino.PIECES[0].color(), "I");
        assertEquals(Color.YELLOW, Tetromino.PIECES[1].color(), "O");
        assertEquals(Color.MAGENTA, Tetromino.PIECES[2].color(), "T");
        assertEquals(Color.ORANGE, Tetromino.PIECES[3].color(), "L");
        assertEquals(Color.BLUE, Tetromino.PIECES[4].color(), "J");
        assertEquals(Color.GREEN, Tetromino.PIECES[5].color(), "S");
        assertEquals(Color.RED, Tetromino.PIECES[6].color(), "Z");
        for (Tetromino piece : Tetromino.PIECES) {
            assertEquals(4, occupiedCells(piece), "every piece is four cells");
        }
    }

    @Test
    @DisplayName("a piece enters on the top row, centred across the well")
    void spawnPositionIsCentred() {
        assertEquals(0, Tetromino.PIECES[0].y());
        assertEquals(3, Tetromino.PIECES[0].x(), "the 4-wide I spans columns 3..6");
        assertEquals(4, Tetromino.PIECES[1].x(), "the 2-wide O straddles the middle");
        assertEquals(3, Tetromino.PIECES[2].x(), "a 3-wide piece cannot be exactly centred");
        for (Tetromino piece : Tetromino.PIECES) {
            assertTrue(piece.x() >= 0);
            assertTrue(piece.x() + piece.width() <= TetrisEngine.WIDTH,
                    "no piece may enter past a wall");
        }
    }

    @Test
    @DisplayName("moving returns a new piece and leaves the original alone")
    void movedByIsAVCopy() {
        Tetromino original = Tetromino.PIECES[2];
        Tetromino moved = original.movedBy(-1, 3);
        assertNotSame(original, moved);
        assertEquals(original.x(), Tetromino.PIECES[2].x(), "the shared shape was not moved");
        assertEquals(original.x() - 1, moved.x());
        assertEquals(original.y() + 3, moved.y());
        assertSame(original.color(), moved.color());
        assertEquals(original.width(), moved.width());
        assertEquals(original.height(), moved.height());
    }

    @Test
    @DisplayName("a quarter turn trades rows for columns")
    void rotationTradesRowsForColumns() {
        Tetromino flat = Tetromino.PIECES[0];  // the I, 1 row x 4 columns
        assertEquals(1, flat.height());
        assertEquals(4, flat.width());
        Tetromino upright = flat.rotatedCW();
        assertEquals(4, upright.height());
        assertEquals(1, upright.width());
        assertEquals(flat.x(), upright.x(), "a turn is about the piece, not the board");
        assertEquals(flat.y(), upright.y());
        for (int r = 0; r < upright.height(); r++) {
            assertTrue(upright.covers(r, 0), "the turned I is a full column");
        }
    }

    @Test
    @DisplayName("the T's bump swings from up to right, clockwise")
    void rotationOfTheTPointsTheBumpRight() {
        Tetromino t = Tetromino.PIECES[2];
        assertTrue(t.covers(0, 1), "the bump starts on top");
        assertFalse(t.covers(0, 0));
        Tetromino turned = t.rotatedCW();
        // 3 rows x 2 columns: a full left column with the bump out of its middle.
        assertEquals(3, turned.height());
        assertEquals(2, turned.width());
        assertTrue(turned.covers(0, 0));
        assertTrue(turned.covers(1, 0));
        assertTrue(turned.covers(2, 0));
        assertTrue(turned.covers(1, 1), "the bump now points right");
        assertFalse(turned.covers(0, 1));
        assertFalse(turned.covers(2, 1));
    }

    @Test
    @DisplayName("four quarter turns come back to where they started")
    void fourTurnsAreOneFullTurn() {
        for (Tetromino piece : Tetromino.PIECES) {
            Tetromino turned = piece;
            for (int i = 0; i < 4; i++) {
                turned = turned.rotatedCW();
            }
            assertEquals(piece.width(), turned.width(), "shape returns after a full turn");
            assertEquals(piece.height(), turned.height());
            assertEquals(occupiedCells(piece), occupiedCells(turned));
            for (int r = 0; r < piece.height(); r++) {
                for (int c = 0; c < piece.width(); c++) {
                    assertEquals(piece.covers(r, c), turned.covers(r, c));
                }
            }
        }
    }

    @Test
    @DisplayName("the square never changes shape, only its own cells")
    void theSquareIsItsOwnRotation() {
        Tetromino square = Tetromino.PIECES[1];
        Tetromino turned = square.rotatedCW();
        assertEquals(2, turned.width());
        assertEquals(2, turned.height());
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                assertTrue(turned.covers(r, c));
            }
        }
    }

    @Test
    @DisplayName("toString names the position a stuck piece was at")
    void toStringNamesThePiece() {
        Tetromino piece = Tetromino.PIECES[3].movedBy(-3, 5);
        String text = piece.toString();
        assertTrue(text.startsWith("Tetromino["), text);
        assertTrue(text.contains("0,5"), "the board position reads out: " + text);
    }

    private static int occupiedCells(Tetromino piece) {
        int cells = 0;
        for (int r = 0; r < piece.height(); r++) {
            for (int c = 0; c < piece.width(); c++) {
                if (piece.covers(r, c)) {
                    cells++;
                }
            }
        }
        return cells;
    }
}
