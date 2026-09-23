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
package org.jdesktop.lg3d.apps.games.tictactoe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link TicTacToePanel}, the 2D/Swing counterpart of
 * TicTacToe3D. They drive the panel's public interaction methods (the same ones
 * the cell buttons call) and assert on the reused {@link TicTacToeModel} and the
 * rendered button labels, so no display is needed.
 */
class TicTacToePanelTest {

    private static int count(TicTacToePanel panel, String mark) {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            if (mark.equals(panel.cellButton(i).getText())) {
                n++;
            }
        }
        return n;
    }

    @Test
    void freshPanelIsReadyForTheHuman() {
        TicTacToePanel panel = new TicTacToePanel();
        assertTrue(panel.model().isHumanTurn());
        assertEquals(0, count(panel, "X"));
        assertEquals(0, count(panel, "O"));
        assertEquals("Your turn (X)", panel.statusText());
        for (int i = 0; i < 9; i++) {
            assertTrue(panel.cellButton(i).isEnabled());
        }
    }

    @Test
    void humanMoveIsAnsweredByTheAi() {
        TicTacToePanel panel = new TicTacToePanel();
        panel.playCell(4);
        assertEquals("X", panel.cellButton(4).getText());
        assertEquals(1, count(panel, "X"));
        assertEquals(1, count(panel, "O"));
        // The minimax reply is synchronous, so it is the human's turn again.
        assertTrue(panel.model().isHumanTurn());
    }

    @Test
    void occupiedAndIllegalCellsAreIgnored() {
        TicTacToePanel panel = new TicTacToePanel();
        panel.playCell(4);
        panel.playCell(4);          // already taken
        panel.playCell(-1);         // off board
        panel.playCell(9);          // off board
        assertEquals(1, count(panel, "X"));
    }

    @Test
    void aiFirstOpensWithO() {
        TicTacToePanel panel = new TicTacToePanel();
        panel.newGame(false);
        assertEquals(1, count(panel, "O"));
        assertEquals(0, count(panel, "X"));
        assertTrue(panel.model().isHumanTurn());
    }

    @Test
    void undoRestoresTheEmptyBoard() {
        TicTacToePanel panel = new TicTacToePanel();
        panel.playCell(0);
        panel.undo();
        assertEquals(0, count(panel, "X"));
        assertEquals(0, count(panel, "O"));
        assertTrue(panel.model().isHumanTurn());
        assertEquals("Your turn (X)", panel.statusText());
    }

    @Test
    void undoWithNoHistoryIsHarmless() {
        TicTacToePanel panel = new TicTacToePanel();
        panel.undo();
        assertEquals(0, count(panel, "X"));
    }

    @Test
    void perfectPlayFromBothSidesEndsTheGame() {
        TicTacToePanel panel = new TicTacToePanel();
        for (int i = 0; i < 9; i++) {
            panel.playCell(i);      // ignored once taken / game over
        }
        assertTrue(panel.model().isGameOver());
        assertFalse(panel.model().isHumanTurn());
    }

    @Test
    void statusReportsAConstructedWin() {
        TicTacToePanel panel = new TicTacToePanel();
        // A completed top row for the human; recomputeState records the winner.
        panel.model().restore(new int[] {
                TicTacToeModel.HUMAN, TicTacToeModel.HUMAN, TicTacToeModel.HUMAN,
                TicTacToeModel.AI, TicTacToeModel.AI, TicTacToeModel.EMPTY,
                TicTacToeModel.EMPTY, TicTacToeModel.EMPTY, TicTacToeModel.EMPTY,
        }, TicTacToeModel.HUMAN);
        assertEquals("You win!", panel.statusText());
    }
}
