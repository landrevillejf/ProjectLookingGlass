/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.games.tictactoe;

/**
 * Pure tic-tac-toe rules and an unbeatable minimax opponent, kept free of any
 * lg3d / AWT dependency so it can be exercised headlessly.
 *
 * <p>The board is nine cells, {@link #EMPTY} / {@link #HUMAN} / {@link #AI}.
 * The human always plays {@link #HUMAN}; who moves first alternates with
 * {@link #reset(boolean)}. {@link #bestMove()} returns the AI's optimal cell
 * (full-width minimax on a 3x3 board is trivial), so perfect play from the
 * human can only ever draw.</p>
 */
public class TicTacToeModel {

    public static final int EMPTY = 0;
    public static final int HUMAN = 1;   // drawn as X
    public static final int AI = 2;      // drawn as O

    /** The eight winning lines, as triples of cell indices. */
    private static final int[][] LINES = {
        {0, 1, 2}, {3, 4, 5}, {6, 7, 8},   // rows
        {0, 3, 6}, {1, 4, 7}, {2, 5, 8},   // columns
        {0, 4, 8}, {2, 4, 6},              // diagonals
    };

    private final int[] board = new int[9];
    private int turn;              // HUMAN or AI
    private int winner;            // EMPTY (none/draw-in-progress), HUMAN, AI
    private int[] winLine;         // the three cells of the winning line
    private boolean draw;

    public TicTacToeModel() {
        reset(true);
    }

    /** Clears the board; {@code humanFirst} decides who opens. */
    public void reset(boolean humanFirst) {
        for (int i = 0; i < 9; i++) {
            board[i] = EMPTY;
        }
        winner = EMPTY;
        winLine = null;
        draw = false;
        turn = humanFirst ? HUMAN : AI;
    }

    public int cell(int i) {
        return board[i];
    }

    public int getTurn() {
        return turn;
    }

    public int getWinner() {
        return winner;
    }

    public boolean isDraw() {
        return draw;
    }

    public boolean isGameOver() {
        return winner != EMPTY || draw;
    }

    /** The three winning cells, or {@code null} while no line is complete. */
    public int[] getWinLine() {
        return winLine;
    }

    public boolean isHumanTurn() {
        return turn == HUMAN && !isGameOver();
    }

    /**
     * Places the side-to-move marker at {@code cell} if that is a legal move.
     *
     * @return true when the mark was placed
     */
    public boolean play(int cell) {
        if (isGameOver() || cell < 0 || cell > 8 || board[cell] != EMPTY) {
            return false;
        }
        board[cell] = turn;
        if (evaluateWinner(turn)) {
            winner = turn;
        } else if (isFull()) {
            draw = true;
        } else {
            turn = (turn == HUMAN) ? AI : HUMAN;
        }
        return true;
    }

    /**
     * Lets the AI take its turn (no-op unless it is already the AI's move and
     * the game is live). Uses full-width minimax, so the reply is optimal.
     */
    public void aiMove() {
        if (isGameOver() || turn != AI) {
            return;
        }
        int best = bestMove();
        if (best >= 0) {
            play(best);
        }
    }

    /**
     * Returns the AI's optimal cell for the current position, or -1 when the
     * game is already over. Prefers an immediate win, then a block, then the
     * centre, then a corner, then a side — all of which minimax confirms.
     */
    public int bestMove() {
        if (isGameOver()) {
            return -1;
        }
        int bestScore = Integer.MIN_VALUE;
        int bestCell = -1;
        for (int i = 0; i < 9; i++) {
            if (board[i] != EMPTY) {
                continue;
            }
            board[i] = AI;
            int score = minimax(false, 0);
            board[i] = EMPTY;
            if (score > bestScore) {
                bestScore = score;
                bestCell = i;
            }
        }
        return bestCell;
    }

    /** Minimax with depth so the AI wins as fast as possible / loses slowly. */
    private int minimax(boolean aiTurn, int depth) {
        if (evaluateWinner(AI)) {
            return 10 - depth;
        }
        if (evaluateWinner(HUMAN)) {
            return depth - 10;
        }
        if (isFull()) {
            return 0;
        }
        int best = aiTurn ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (board[i] != EMPTY) {
                continue;
            }
            board[i] = aiTurn ? AI : HUMAN;
            int score = minimax(!aiTurn, depth + 1);
            board[i] = EMPTY;
            best = aiTurn ? Math.max(best, score) : Math.min(best, score);
        }
        return best;
    }

    private boolean isFull() {
        for (int i = 0; i < 9; i++) {
            if (board[i] == EMPTY) {
                return false;
            }
        }
        return true;
    }

    /** True when {@code player} occupies a full line; records the line. */
    private boolean evaluateWinner(int player) {
        for (int[] line : LINES) {
            if (board[line[0]] == player && board[line[1]] == player
                    && board[line[2]] == player) {
                if (this.winner == player) {
                    this.winLine = line;
                }
                return true;
            }
        }
        return false;
    }

    /** Recomputes winner/winLine/draw from scratch (used after a load). */
    public void recomputeState() {
        winner = EMPTY;
        winLine = null;
        draw = false;
        for (int[] line : LINES) {
            int a = board[line[0]];
            if (a != EMPTY && a == board[line[1]] && a == board[line[2]]) {
                winner = a;
                winLine = line;
                return;
            }
        }
        if (isFull()) {
            draw = true;
        }
    }

    /** Exposes the raw board for persistence. */
    public int[] snapshot() {
        int[] copy = new int[9];
        System.arraycopy(board, 0, copy, 0, 9);
        return copy;
    }

    /** Restores a raw board and recomputes whose turn / result it is. */
    public void restore(int[] cells, int turnPlayer) {
        for (int i = 0; i < 9; i++) {
            board[i] = (cells != null && i < cells.length) ? cells[i] : EMPTY;
        }
        turn = (turnPlayer == AI) ? AI : HUMAN;
        recomputeState();
        if (!isGameOver()) {
            turn = turnPlayer;
        }
    }
}
