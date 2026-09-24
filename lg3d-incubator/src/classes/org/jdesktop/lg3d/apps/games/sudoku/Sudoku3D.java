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

import org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaButton;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * A native 3D sudoku. The window is a plain {@link Frame3D} (standard glassy
 * decoration); its body is a {@link SudokuView} live-texture board over two
 * strips of {@link AgendaButton} controls — a {@code 1..9} digit row and a
 * {@code Clr / New / Level / Hint / Solve} action row.
 *
 * <p>Interaction is click-driven (dev mode has no keyboard-focus routing):
 * click a cell to select it, then a digit to enter it ({@code Clr} blanks it).
 * Givens cannot be edited, conflicting cells are flagged, {@code Level} cycles
 * Easy / Medium / Hard (each a freshly generated unique-solution puzzle),
 * {@code Hint} fills one correct cell and {@code Solve} completes the grid.</p>
 */
public class Sudoku3D extends Frame3D {

    private static final float DEPTH = 0.01f;

    private final SudokuModel model = new SudokuModel();
    private SudokuView view;

    private float width;
    private float height;
    private float viewW;
    private float viewH;
    private float viewCenterY;
    private float btnH;
    private float rowGap;
    private float bottomY;
    private int controlRows = 2;

    public static void main(String[] args) {
        new Sudoku3D();
    }

    public Sudoku3D() {
        super();
        try {
            setName("Sudoku 3D");
            computeLayout();
            setPreferredSize(new Vector3f(width, height, DEPTH));
            createUI();
            setVisible(true);
            changeEnabled(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Sudoku3D", e);
        }
    }

    private void computeLayout() {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        height = tk.getScreenHeight() * 0.5f;

        float topMargin = height * 0.10f;
        float bottomMargin = height * 0.03f;
        float controlH = height * 0.17f;     // two button rows
        float gap = height * 0.025f;

        viewH = height - topMargin - bottomMargin - controlH - gap;
        viewW = viewH;                        // the board texture is square
        width = viewW;
        viewCenterY = height * 0.5f - topMargin - viewH * 0.5f;

        rowGap = controlH * 0.12f;
        btnH = (controlH - (controlRows - 1) * rowGap) / controlRows;
        bottomY = -height * 0.5f + bottomMargin + btnH * 0.5f;
    }

    private void createUI() {
        view = new SudokuView(viewW, viewH);
        view.setTranslation(0.0f, viewCenterY, 0.001f);
        view.setModel(model);
        view.setCellListener(new SudokuView.CellListener() {
            public void cellClicked(int cell) {
                // Selection is tracked by the view; nothing else to do here.
            }
        });
        addChild(view);

        // Digit row.
        String[] digits = { "1", "2", "3", "4", "5", "6", "7", "8", "9" };
        Runnable[] digitActions = new Runnable[9];
        for (int d = 1; d <= 9; d++) {
            final int value = d;
            digitActions[d - 1] = new Runnable() {
                public void run() { enterDigit(value); }
            };
        }
        addButtonRow(digits, digitActions, 0);

        // Action row.
        addButtonRow(
            new String[] { "Clr", "New", "Level", "Hint", "Solve" },
            new Runnable[] {
                new Runnable() { public void run() { enterDigit(0); } },
                new Runnable() { public void run() { newPuzzle(); } },
                new Runnable() { public void run() { nextLevel(); } },
                new Runnable() { public void run() { hint(); } },
                new Runnable() { public void run() { solve(); } },
            }, 1);
    }

    private void addButtonRow(String[] labels, Runnable[] actions, int row) {
        int n = labels.length;
        float gap = width * 0.012f;
        float bw = (width - (n - 1) * gap) / n;
        float x0 = -width * 0.5f + bw * 0.5f;
        for (int i = 0; i < n; i++) {
            final Runnable action = actions[i];
            AgendaButton button = new AgendaButton(labels[i], bw, btnH,
                    new ActionNoArg() {
                        public void performAction(LgEventSource source) {
                            action.run();
                        }
                    });
            button.setTranslation(x0 + i * (bw + gap), rowY(row), 0.002f);
            addChild(button);
        }
    }

    private float rowY(int row) {
        return bottomY + (controlRows - 1 - row) * (btnH + rowGap);
    }

    // ------------------------------------------------------------------
    // Game actions
    // ------------------------------------------------------------------

    private void enterDigit(int value) {
        int cell = view.getSelected();
        if (cell < 0 || model.isFixed(cell)) {
            return;
        }
        if (model.setValue(cell, value)) {
            view.refresh();
        }
    }

    private void newPuzzle() {
        model.newPuzzle(SudokuModel.LEVELS[model.getLevelIndex()]);
        view.setModel(model);
    }

    private void nextLevel() {
        model.nextLevel();
        view.setModel(model);
    }

    private void hint() {
        model.hint();
        view.refresh();
    }

    private void solve() {
        model.solve();
        view.refresh();
    }
}
