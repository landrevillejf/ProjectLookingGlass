/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.games.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ChessPanel}, the 2D/Swing counterpart of Chess3D.
 * They drive {@code clickSquare} (the same entry point the board buttons use)
 * and assert on the reused {@link ChessModel}. Squares are {@code row * 8 + col}
 * with row 0 = rank 8, so e2 = 52, e3 = 44, e4 = 36.
 */
class ChessPanelTest {

    private static final int E2 = 52, E3 = 44, E4 = 36;

    @Test
    void freshBoardIsTheStartingPosition() {
        ChessPanel panel = new ChessPanel();
        assertTrue(panel.model().whiteToMove());
        assertEquals("Your move (White)", panel.statusText());
        assertEquals(ChessModel.PAWN, panel.model().pieceAt(E2));
        assertEquals(ChessModel.NONE, panel.model().pieceAt(E4));
        // White pieces render as the hollow (upper) Unicode glyphs.
        assertEquals("\u2659", panel.squareButton(E2).getText());
    }

    @Test
    void clickingAPawnSelectsItAndShowsItsTargets() {
        ChessPanel panel = new ChessPanel();
        panel.clickSquare(E2);
        assertEquals(E2, panel.selectedSquare());
        assertTrue(panel.legalTargets().contains(E3));
        assertTrue(panel.legalTargets().contains(E4));
        assertEquals(2, panel.legalTargets().size());
    }

    @Test
    void clickingTheSelectedSquareAgainDeselects() {
        ChessPanel panel = new ChessPanel();
        panel.clickSquare(E2);
        panel.clickSquare(E2);
        assertEquals(-1, panel.selectedSquare());
        assertTrue(panel.legalTargets().isEmpty());
    }

    @Test
    void clickingAnEmptyNonTargetDeselects() {
        ChessPanel panel = new ChessPanel();
        panel.clickSquare(E2);
        panel.clickSquare(28);      // e5: empty, not a pawn target
        assertEquals(-1, panel.selectedSquare());
    }

    @Test
    void clickingAnEnemyPieceDoesNotSelectIt() {
        ChessPanel panel = new ChessPanel();
        panel.clickSquare(12);      // e7 black pawn
        assertEquals(-1, panel.selectedSquare());
    }

    @Test
    void aLegalMoveIsPlayedAndAnswered() {
        ChessPanel panel = new ChessPanel();
        panel.clickSquare(E2);
        panel.clickSquare(E4);
        assertEquals(ChessModel.PAWN, panel.model().pieceAt(E4));
        assertEquals(ChessModel.NONE, panel.model().pieceAt(E2));
        assertEquals(-1, panel.selectedSquare());
        // The AI replies synchronously, so it is White's turn again.
        assertTrue(panel.model().whiteToMove());
    }

    @Test
    void undoRollsBackTheMoveAndTheReply() {
        ChessPanel panel = new ChessPanel();
        panel.clickSquare(E2);
        panel.clickSquare(E4);
        panel.undo();
        assertTrue(panel.model().whiteToMove());
        assertEquals(ChessModel.PAWN, panel.model().pieceAt(E2));
        assertEquals(ChessModel.NONE, panel.model().pieceAt(E4));
    }

    @Test
    void newGameRestoresTheStartingPosition() {
        ChessPanel panel = new ChessPanel();
        panel.clickSquare(E2);
        panel.clickSquare(E4);
        panel.newGame();
        assertTrue(panel.model().whiteToMove());
        assertEquals(ChessModel.PAWN, panel.model().pieceAt(E2));
        assertEquals(-1, panel.selectedSquare());
    }

    @Test
    void clicksAreIgnoredWhenItIsNotTheHumanTurn() {
        ChessPanel panel = new ChessPanel();
        panel.clickSquare(E2);
        panel.clickSquare(E4);
        // White to move again; a bogus off-board click is a no-op.
        panel.clickSquare(-1);
        panel.clickSquare(64);
        assertEquals(-1, panel.selectedSquare());
    }
}
