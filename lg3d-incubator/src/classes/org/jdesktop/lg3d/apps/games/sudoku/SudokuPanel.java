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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The 2D/Swing counterpart of the native-3D {@link Sudoku3D}. It reuses the
 * very same {@link SudokuModel} (an AWT-free generator / solver that digs a
 * unique-solution puzzle out of a random full grid) that the {@code Frame3D}
 * host drives, so both desktops share one engine — but here the board is a real
 * 9x9 grid of keyboard-editable {@link JTextField}s instead of a click-picked
 * live texture, so the player types digits directly.
 *
 * <p>Registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code Sudoku3D} main class, so the one shared start-menu descriptor launches
 * this panel as an MDI internal frame in the 2D/Swing desktop while the 3D
 * desktop keeps building the {@code Frame3D}. The panel loads no Java 3D class,
 * so it also runs where the 3D desktop is unavailable.</p>
 */
public class SudokuPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 460;
    public static final int HEIGHT_PX = 560;

    private static final Color CONFLICT = new Color(0xC5, 0x2A, 0x2A);
    private static final Color GIVEN = new Color(0x1A, 0x1A, 0x1A);
    private static final Color EDITABLE = new Color(0x1E, 0x5E, 0xA8);
    private static final Color GRID = new Color(0x55, 0x55, 0x55);
    private static final Font DIGIT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 18);
    private static final Font GIVEN_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 18);

    private final SudokuModel model = new SudokuModel();
    private final JTextField[] fields = new JTextField[81];
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JComboBox<String> levelCombo =
            new JComboBox<String>(SudokuModel.LEVEL_NAMES);

    /** The difficulty the combo selected; the model only cycles via nextLevel. */
    private int levelIndex;

    /** Guards against DocumentListener recursion during programmatic edits. */
    private boolean updating;

    public SudokuPanel() {
        super(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        add(status, BorderLayout.NORTH);
        add(buildGrid(), BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        levelIndex = model.getLevelIndex();
        levelCombo.setSelectedIndex(levelIndex);
        refreshAll();
    }

    private JPanel buildGrid() {
        JPanel grid = new JPanel(new GridLayout(9, 9));
        grid.setBorder(BorderFactory.createLineBorder(GRID, 2));
        for (int i = 0; i < 81; i++) {
            final int index = i;
            JTextField field = new JTextField(1);
            field.setHorizontalAlignment(SwingConstants.CENTER);
            field.setBorder(BorderFactory.createCompoundBorder(
                    boxBorder(index / 9, index % 9),
                    BorderFactory.createEmptyBorder(2, 0, 2, 0)));
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    edited(index);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    edited(index);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    edited(index);
                }
            });
            fields[i] = field;
            grid.add(field);
        }
        return grid;
    }

    /** Thicker lines on the 3x3 box boundaries. */
    private javax.swing.border.Border boxBorder(int row, int col) {
        int top = (row % 3 == 0) ? 2 : 1;
        int left = (col % 3 == 0) ? 2 : 1;
        int bottom = (row % 3 == 2) ? 2 : 1;
        int right = (col % 3 == 2) ? 2 : 1;
        return BorderFactory.createMatteBorder(top, left, bottom, right, GRID);
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        levelCombo.addActionListener(e -> newPuzzleAt(levelCombo.getSelectedIndex()));

        javax.swing.JButton newButton = new javax.swing.JButton("New");
        newButton.addActionListener(e -> newPuzzleAt(levelCombo.getSelectedIndex()));
        javax.swing.JButton hintButton = new javax.swing.JButton("Hint");
        hintButton.addActionListener(e -> hint());
        javax.swing.JButton solveButton = new javax.swing.JButton("Solve");
        solveButton.addActionListener(e -> solve());
        javax.swing.JButton resetButton = new javax.swing.JButton("Reset");
        resetButton.addActionListener(e -> reset());

        controls.add(new JLabel("Level:"));
        controls.add(levelCombo);
        controls.add(newButton);
        controls.add(hintButton);
        controls.add(solveButton);
        controls.add(resetButton);
        return controls;
    }

    // ------------------------------------------------------------------
    // Interaction (invoked by the widgets and, headlessly, by the tests)
    // ------------------------------------------------------------------

    private void edited(int i) {
        if (updating) {
            return;
        }
        editCell(i, fields[i].getText());
    }

    /**
     * Applies a typed digit (or blank) to cell {@code i}. A fixed given, or a
     * value that is not blank / {@code 1..9}, is rejected and the field is
     * snapped back to the model's value.
     */
    void editCell(int i, String text) {
        if (i < 0 || i > 80 || model.isFixed(i)) {
            return;
        }
        int value = parse(text);
        if (value < 0) {
            snapBack(i);
            return;
        }
        model.setValue(i, value);
        updateHighlights();
    }

    private int parse(String text) {
        String t = (text == null) ? "" : text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        if (t.length() == 1 && t.charAt(0) >= '1' && t.charAt(0) <= '9') {
            return t.charAt(0) - '0';
        }
        return -1;
    }

    private void snapBack(int i) {
        updating = true;
        fields[i].setText(valueText(model.value(i)));
        updating = false;
        updateHighlights();
    }

    /** Generates a fresh puzzle at difficulty {@code levelIndex}. */
    public void newPuzzleAt(int levelIndex) {
        int idx = Math.max(0, Math.min(levelIndex, SudokuModel.LEVELS.length - 1));
        this.levelIndex = idx;
        model.newPuzzle(SudokuModel.LEVELS[idx]);
        levelCombo.setSelectedIndex(idx);
        refreshAll();
    }

    /** Fills one incorrect / blank cell from the stored solution. */
    public void hint() {
        int filled = model.hint();
        if (filled >= 0) {
            updating = true;
            fields[filled].setText(valueText(model.value(filled)));
            updating = false;
        }
        updateHighlights();
    }

    /** Fills every remaining cell from the stored solution. */
    public void solve() {
        model.solve();
        refreshAll();
    }

    /** Clears every non-given cell back to blank. */
    public void reset() {
        model.resetToPuzzle();
        refreshAll();
    }

    // ------------------------------------------------------------------
    // Painting / status
    // ------------------------------------------------------------------

    private void refreshAll() {
        updating = true;
        for (int i = 0; i < 81; i++) {
            fields[i].setText(valueText(model.value(i)));
            boolean fixed = model.isFixed(i);
            fields[i].setEditable(!fixed);
            fields[i].setFont(fixed ? GIVEN_FONT : DIGIT_FONT);
        }
        updating = false;
        updateHighlights();
    }

    private void updateHighlights() {
        for (int i = 0; i < 81; i++) {
            if (model.conflicts(i)) {
                fields[i].setForeground(CONFLICT);
            } else {
                fields[i].setForeground(model.isFixed(i) ? GIVEN : EDITABLE);
            }
        }
        status.setText(statusText());
    }

    private static String valueText(int v) {
        return v == 0 ? "" : Integer.toString(v);
    }

    /** The one-line state message shown above the grid. */
    String statusText() {
        if (model.isSolved()) {
            return "Solved! Well done.";
        }
        if (model.hasAnyConflict()) {
            return "Conflicts remain (shown in red)";
        }
        if (model.isComplete()) {
            return "Grid complete";
        }
        return "Level: " + SudokuModel.LEVEL_NAMES[levelIndex];
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    SudokuModel model() {
        return model;
    }

    int levelIndex() {
        return levelIndex;
    }

    JTextField fieldAt(int i) {
        return fields[i];
    }
}
