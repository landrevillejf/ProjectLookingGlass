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
package org.jdesktop.lg3d.apps.games.sudoku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link SudokuPanel}, the 2D/Swing counterpart of Sudoku3D.
 * They drive the panel's cell-editing and control methods (the same ones the
 * text fields and buttons call) and assert on the reused {@link SudokuModel},
 * so no display is needed.
 */
class SudokuPanelTest {

    private static int firstEditable(SudokuPanel panel) {
        for (int i = 0; i < 81; i++) {
            if (!panel.model().isFixed(i)) {
                return i;
            }
        }
        throw new IllegalStateException("no editable cell");
    }

    private static int blanks(SudokuPanel panel) {
        int n = 0;
        for (int i = 0; i < 81; i++) {
            if (panel.model().value(i) == 0) {
                n++;
            }
        }
        return n;
    }

    @Test
    void freshPanelReportsTheLevelAndLocksTheGivens() {
        SudokuPanel panel = new SudokuPanel();
        assertTrue(panel.statusText().startsWith("Level:"));
        assertEquals(0, panel.levelIndex());
        for (int i = 0; i < 81; i++) {
            assertEquals(panel.model().isFixed(i), !panel.fieldAt(i).isEditable());
        }
    }

    @Test
    void editingAFixedGivenIsIgnored() {
        SudokuPanel panel = new SudokuPanel();
        int fixed = -1;
        for (int i = 0; i < 81; i++) {
            if (panel.model().isFixed(i)) {
                fixed = i;
                break;
            }
        }
        assertTrue(fixed >= 0);
        int before = panel.model().value(fixed);
        panel.editCell(fixed, "9");
        assertEquals(before, panel.model().value(fixed));
    }

    @Test
    void editingAnEmptyCellStoresTheDigit() {
        SudokuPanel panel = new SudokuPanel();
        int i = firstEditable(panel);
        panel.editCell(i, "0");     // clear first, then set
        panel.editCell(i, "5");
        assertEquals(5, panel.model().value(i));
        panel.editCell(i, "");
        assertEquals(0, panel.model().value(i));
    }

    @Test
    void invalidInputIsRejected() {
        SudokuPanel panel = new SudokuPanel();
        int i = firstEditable(panel);
        panel.editCell(i, "");
        panel.editCell(i, "x");
        assertEquals(0, panel.model().value(i));
        panel.editCell(i, "12");
        assertEquals(0, panel.model().value(i));
        panel.editCell(i, "0");
        assertEquals(0, panel.model().value(i));
    }

    @Test
    void aDuplicateInTheRowIsFlaggedAsAConflict() {
        SudokuPanel panel = new SudokuPanel();
        int target = -1;
        int value = -1;
        for (int i = 0; i < 81 && target < 0; i++) {
            if (panel.model().isFixed(i)) {
                continue;
            }
            int row = i / 9;
            for (int c = 0; c < 9; c++) {
                int j = row * 9 + c;
                if (j == i) {
                    continue;
                }
                int v = panel.model().value(j);
                if (v != 0 && v != panel.model().value(i)) {
                    target = i;
                    value = v;
                    break;
                }
            }
        }
        assertTrue(target >= 0, "expected a row with a given to duplicate");
        panel.editCell(target, Integer.toString(value));
        assertTrue(panel.model().conflicts(target));
        assertTrue(panel.statusText().contains("Conflicts"));
    }

    @Test
    void hintFillsExactlyOneCell() {
        SudokuPanel panel = new SudokuPanel();
        int before = blanks(panel);
        panel.hint();
        assertEquals(before - 1, blanks(panel));
    }

    @Test
    void solveCompletesTheGrid() {
        SudokuPanel panel = new SudokuPanel();
        panel.solve();
        assertTrue(panel.model().isSolved());
        assertEquals("Solved! Well done.", panel.statusText());
    }

    @Test
    void resetClearsTheNonGivens() {
        SudokuPanel panel = new SudokuPanel();
        panel.solve();
        panel.reset();
        assertFalse(panel.model().isComplete());
        assertTrue(blanks(panel) > 0);
    }

    @Test
    void newPuzzleAtSelectsTheLevel() {
        SudokuPanel panel = new SudokuPanel();
        panel.newPuzzleAt(2);
        assertEquals(2, panel.levelIndex());
        assertTrue(panel.model().isGenerated());
        assertTrue(panel.statusText().contains("Hard"));
        panel.newPuzzleAt(1);
        assertEquals(1, panel.levelIndex());
    }

    @Test
    void newPuzzleAtClampsOutOfRangeLevels() {
        SudokuPanel panel = new SudokuPanel();
        panel.newPuzzleAt(99);
        assertEquals(2, panel.levelIndex());
        panel.newPuzzleAt(-5);
        assertEquals(0, panel.levelIndex());
    }
}
