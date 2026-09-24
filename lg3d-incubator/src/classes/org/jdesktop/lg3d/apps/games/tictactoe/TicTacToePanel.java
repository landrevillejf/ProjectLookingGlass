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
package org.jdesktop.lg3d.apps.games.tictactoe;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The 2D/Swing counterpart of the native-3D {@link TicTacToe3D}. It reuses the
 * very same {@link TicTacToeModel} (an AWT-free engine with an unbeatable
 * full-width minimax opponent) that the {@code Frame3D} host drives, so both
 * desktops play identical rules — but here the board is a real 3x3 grid of
 * keyboard-focusable {@link JButton}s instead of a click-picked live texture.
 *
 * <p>Registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code TicTacToe3D} main class, so the one shared start-menu descriptor
 * launches this panel as an MDI internal frame in the 2D/Swing desktop while the
 * 3D desktop keeps building the {@code Frame3D}. The panel loads no Java 3D
 * class, so it also runs where the 3D desktop is unavailable.</p>
 */
public class TicTacToePanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 340;
    public static final int HEIGHT_PX = 420;

    private static final Color WIN_HIGHLIGHT = new Color(0xC8, 0xE6, 0xC9);
    private static final Font MARK_FONT =
            new Font(Font.SANS_SERIF, Font.BOLD, 40);

    private final TicTacToeModel model = new TicTacToeModel();

    /** Board snapshots (nine cells + side to move) for Undo. */
    private final Deque<int[]> history = new ArrayDeque<int[]>();

    private final JButton[] cells = new JButton[9];
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JButton undoButton = new JButton("Undo");

    public TicTacToePanel() {
        super(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        add(status, BorderLayout.NORTH);
        add(buildBoard(), BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        refresh();
    }

    private JPanel buildBoard() {
        JPanel board = new JPanel(new GridLayout(3, 3, 5, 5));
        for (int i = 0; i < 9; i++) {
            final int index = i;
            JButton cell = new JButton("");
            cell.setFont(MARK_FONT);
            cell.setFocusPainted(false);
            cell.addActionListener(e -> playCell(index));
            cells[i] = cell;
            board.add(cell);
        }
        return board;
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        JButton newGame = new JButton("New Game");
        newGame.addActionListener(e -> newGame(true));
        JButton aiFirst = new JButton("AI First");
        aiFirst.addActionListener(e -> newGame(false));
        undoButton.addActionListener(e -> undo());
        controls.add(newGame);
        controls.add(aiFirst);
        controls.add(undoButton);
        return controls;
    }

    // ------------------------------------------------------------------
    // Interaction (invoked by the buttons and, headlessly, by the tests)
    // ------------------------------------------------------------------

    /** Drops the human {@code X} at {@code i} and lets the AI answer. */
    public void playCell(int i) {
        if (i < 0 || i > 8 || !model.isHumanTurn()
                || model.cell(i) != TicTacToeModel.EMPTY) {
            return;
        }
        pushHistory();
        model.play(i);
        if (!model.isGameOver()) {
            model.aiMove();
        }
        refresh();
    }

    /** Clears the board; {@code humanFirst} decides who opens. */
    public void newGame(boolean humanFirst) {
        model.reset(humanFirst);
        history.clear();
        if (!humanFirst) {
            model.aiMove();
        }
        refresh();
    }

    /** Rolls back the last human move (and the AI's reply). */
    public void undo() {
        int[] entry = history.pollLast();
        if (entry == null) {
            return;
        }
        int[] board = new int[9];
        System.arraycopy(entry, 0, board, 0, 9);
        model.restore(board, entry[9]);
        refresh();
    }

    private void pushHistory() {
        int[] snapshot = model.snapshot();
        int[] entry = new int[10];
        System.arraycopy(snapshot, 0, entry, 0, 9);
        entry[9] = model.getTurn();
        history.addLast(entry);
        if (history.size() > 64) {
            history.pollFirst();
        }
    }

    // ------------------------------------------------------------------
    // Painting / status
    // ------------------------------------------------------------------

    private void refresh() {
        int[] winLine = model.getWinLine();
        boolean live = model.isHumanTurn();
        for (int i = 0; i < 9; i++) {
            int value = model.cell(i);
            cells[i].setText(value == TicTacToeModel.HUMAN ? "X"
                    : value == TicTacToeModel.AI ? "O" : "");
            cells[i].setEnabled(value == TicTacToeModel.EMPTY && live);
            boolean winning = winLine != null
                    && (winLine[0] == i || winLine[1] == i || winLine[2] == i);
            cells[i].setOpaque(winning);
            cells[i].setContentAreaFilled(!winning);
            if (winning) {
                cells[i].setBackground(WIN_HIGHLIGHT);
            }
        }
        status.setText(statusText());
        undoButton.setEnabled(!history.isEmpty());
    }

    /** The one-line result / turn message shown above the board. */
    String statusText() {
        if (model.getWinner() == TicTacToeModel.HUMAN) {
            return "You win!";
        }
        if (model.getWinner() == TicTacToeModel.AI) {
            return "The computer wins";
        }
        if (model.isDraw()) {
            return "Draw";
        }
        return live() ? "Your turn (X)" : "Computer's turn (O)";
    }

    private boolean live() {
        return model.isHumanTurn();
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    TicTacToeModel model() {
        return model;
    }

    JButton cellButton(int i) {
        return cells[i];
    }
}
