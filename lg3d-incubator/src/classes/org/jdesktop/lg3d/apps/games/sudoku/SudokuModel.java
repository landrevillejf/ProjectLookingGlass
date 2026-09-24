/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.games.sudoku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Pure sudoku engine: generates a random full grid, digs a unique-solution
 * puzzle out of it, and tracks the working grid the player edits. No lg3d / AWT
 * dependency, so generation and solving can be exercised headlessly.
 *
 * <p>Cells are addressed {@code 0..80} as {@code row * 9 + col}; a value of
 * {@code 0} is blank. {@link #newPuzzle(int)} builds a fresh board with the
 * requested number of givens, guaranteeing a single solution by re-testing
 * uniqueness after each dig. {@link #isSolved()} is true only when the grid is
 * complete and free of row/column/box conflicts.</p>
 */
public class SudokuModel {

    /** Preset difficulty levels, expressed as the number of givens. */
    public static final int EASY = 44;
    public static final int MEDIUM = 34;
    public static final int HARD = 27;
    public static final int[] LEVELS = { EASY, MEDIUM, HARD };
    public static final String[] LEVEL_NAMES = { "Easy", "Medium", "Hard" };

    private final Random rnd = new Random();

    private final int[] solution = new int[81];
    private final int[] puzzle = new int[81];
    private final int[] current = new int[81];
    private final boolean[] fixed = new boolean[81];

    private int levelIndex = 0;
    private boolean generated;

    public SudokuModel() {
        newPuzzle(LEVELS[levelIndex]);
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    /** Generates a fresh puzzle with {@code givens} clues and a unique answer. */
    public final void newPuzzle(int givens) {
        for (int i = 0; i < 81; i++) {
            solution[i] = 0;
        }
        fill(solution, 0);
        System.arraycopy(solution, 0, puzzle, 0, 81);
        dig(givens);
        startFromPuzzle();
        generated = true;
    }

    /** Starts a new puzzle at the next difficulty level; returns its name. */
    public String nextLevel() {
        levelIndex = (levelIndex + 1) % LEVELS.length;
        newPuzzle(LEVELS[levelIndex]);
        return getLevelName();
    }

    public String getLevelName() {
        return LEVEL_NAMES[levelIndex];
    }

    public int getLevelIndex() {
        return levelIndex;
    }

    /** Backtracking fill of a full valid grid, digits shuffled for variety. */
    private boolean fill(int[] g, int pos) {
        if (pos == 81) {
            return true;
        }
        for (int n : shuffledDigits()) {
            if (canPlace(g, pos, n)) {
                g[pos] = n;
                if (fill(g, pos + 1)) {
                    return true;
                }
                g[pos] = 0;
            }
        }
        return false;
    }

    /** Removes cells (random order) while the puzzle keeps exactly one answer. */
    private void dig(int givens) {
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < 81; i++) {
            order.add(i);
        }
        Collections.shuffle(order, rnd);
        int remaining = 81;
        for (int idx : order) {
            if (remaining <= givens) {
                break;
            }
            int backup = puzzle[idx];
            puzzle[idx] = 0;
            if (countSolutions(puzzle, 2) != 1) {
                puzzle[idx] = backup;   // removal broke uniqueness; keep clue
            } else {
                remaining--;
            }
        }
    }

    /** Counts solutions of {@code g}, stopping early once {@code limit} is hit. */
    private int countSolutions(int[] g, int limit) {
        int pos = -1;
        for (int i = 0; i < 81; i++) {
            if (g[i] == 0) {
                pos = i;
                break;
            }
        }
        if (pos == -1) {
            return 1;
        }
        int total = 0;
        for (int n = 1; n <= 9; n++) {
            if (canPlace(g, pos, n)) {
                g[pos] = n;
                total += countSolutions(g, limit);
                g[pos] = 0;
                if (total >= limit) {
                    return total;
                }
            }
        }
        return total;
    }

    private boolean canPlace(int[] g, int pos, int n) {
        int row = pos / 9;
        int col = pos % 9;
        for (int i = 0; i < 9; i++) {
            if (g[row * 9 + i] == n) {
                return false;
            }
            if (g[i * 9 + col] == n) {
                return false;
            }
        }
        int br = (row / 3) * 3;
        int bc = (col / 3) * 3;
        for (int r = br; r < br + 3; r++) {
            for (int c = bc; c < bc + 3; c++) {
                if (g[r * 9 + c] == n) {
                    return false;
                }
            }
        }
        return true;
    }

    private int[] shuffledDigits() {
        int[] d = { 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        for (int i = d.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = d[i];
            d[i] = d[j];
            d[j] = t;
        }
        return d;
    }

    // ------------------------------------------------------------------
    // Play
    // ------------------------------------------------------------------

    private void startFromPuzzle() {
        for (int i = 0; i < 81; i++) {
            current[i] = puzzle[i];
            fixed[i] = puzzle[i] != 0;
        }
    }

    public int value(int i) {
        return current[i];
    }

    public boolean isFixed(int i) {
        return fixed[i];
    }

    public boolean isGenerated() {
        return generated;
    }

    /** Sets an editable cell (0 clears it); fixed givens are ignored. */
    public boolean setValue(int i, int v) {
        if (i < 0 || i > 80 || fixed[i] || v < 0 || v > 9) {
            return false;
        }
        current[i] = v;
        return true;
    }

    public boolean isComplete() {
        for (int i = 0; i < 81; i++) {
            if (current[i] == 0) {
                return false;
            }
        }
        return true;
    }

    /** True when the grid is complete and has no row/column/box conflict. */
    public boolean isSolved() {
        return isComplete() && !hasAnyConflict();
    }

    public boolean hasAnyConflict() {
        for (int i = 0; i < 81; i++) {
            if (conflicts(i)) {
                return true;
            }
        }
        return false;
    }

    /** True when the value at {@code i} duplicates another in its row/col/box. */
    public boolean conflicts(int i) {
        int n = current[i];
        if (n == 0) {
            return false;
        }
        int row = i / 9;
        int col = i % 9;
        for (int k = 0; k < 9; k++) {
            int rc = row * 9 + k;
            if (rc != i && current[rc] == n) {
                return true;
            }
            int cc = k * 9 + col;
            if (cc != i && current[cc] == n) {
                return true;
            }
        }
        int br = (row / 3) * 3;
        int bc = (col / 3) * 3;
        for (int r = br; r < br + 3; r++) {
            for (int c = bc; c < bc + 3; c++) {
                int bi = r * 9 + c;
                if (bi != i && current[bi] == n) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fills one incorrect/blank cell from the stored solution.
     *
     * @return the cell index that was filled, or -1 when already solved
     */
    public int hint() {
        for (int i = 0; i < 81; i++) {
            if (current[i] != solution[i]) {
                current[i] = solution[i];
                return i;
            }
        }
        return -1;
    }

    /** Fills every remaining cell from the stored solution. */
    public void solve() {
        System.arraycopy(solution, 0, current, 0, 81);
    }

    /** Clears every non-given cell back to blank. */
    public void resetToPuzzle() {
        startFromPuzzle();
    }
}
