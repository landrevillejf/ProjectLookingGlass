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
package org.jdesktop.lg3d.apps.games.chess;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The 2D/Swing counterpart of the native-3D {@link Chess3D}. It reuses the very
 * same {@link ChessModel} (an AWT-free engine with full rules — castling, en
 * passant, promotion, check / mate / stalemate / insufficient material — and a
 * negamax + alpha-beta opponent) that the {@code Frame3D} host drives, so both
 * desktops play identical chess. Here the board is an 8x8 grid of
 * keyboard-focusable {@link JButton}s: click a white piece to select it (its
 * legal destinations light up), then click a destination to move; the AI answers
 * as Black. Pieces are the Unicode chess glyphs, hollow for White and filled for
 * Black, so no piece artwork or font beyond the platform default is needed.
 *
 * <p>Registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code Chess3D} main class, so the one shared start-menu descriptor launches
 * this panel as an MDI internal frame in the 2D/Swing desktop while the 3D
 * desktop keeps building the {@code Frame3D}. The panel loads no Java 3D class,
 * so it also runs where the 3D desktop is unavailable.</p>
 */
public class ChessPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 480;
    public static final int HEIGHT_PX = 560;

    private static final Color LIGHT = new Color(0xF0, 0xD9, 0xB5);
    private static final Color DARK = new Color(0xB5, 0x88, 0x63);
    private static final Color SELECTED = new Color(0xF7, 0xEC, 0x6A);
    private static final Color TARGET = new Color(0x9C, 0xC9, 0x9C);
    private static final Color LAST_MOVE = new Color(0xC9, 0xD6, 0xE8);
    private static final Color PIECE = new Color(0x1A, 0x1A, 0x1A);
    private static final Font PIECE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 30);

    private static final String[] DEPTH_LABELS = { "Easy", "Normal", "Hard" };
    private static final int[] DEPTHS = { 1, 3, 4 };

    private final ChessModel model = new ChessModel();
    private final JButton[] squares = new JButton[64];
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JComboBox<String> depthCombo = new JComboBox<String>(DEPTH_LABELS);

    private int selected = -1;
    private final List<Integer> targets = new ArrayList<Integer>();

    public ChessPanel() {
        super(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        add(status, BorderLayout.NORTH);
        add(buildBoard(), BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        depthCombo.setSelectedIndex(1);   // Normal
        refresh();
    }

    private JPanel buildBoard() {
        JPanel board = new JPanel(new GridLayout(8, 8));
        board.setBorder(BorderFactory.createLineBorder(PIECE, 2));
        for (int i = 0; i < 64; i++) {
            final int index = i;
            JButton square = new JButton("");
            square.setFont(PIECE_FONT);
            square.setForeground(PIECE);
            square.setOpaque(true);
            square.setContentAreaFilled(false);
            square.setBorderPainted(false);
            square.setFocusPainted(false);
            square.addActionListener(e -> clickSquare(index));
            squares[i] = square;
            board.add(square);
        }
        return board;
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        depthCombo.addActionListener(e ->
                model.setDepth(DEPTHS[depthCombo.getSelectedIndex()]));

        JButton newGame = new JButton("New Game");
        newGame.addActionListener(e -> newGame());
        JButton undo = new JButton("Undo");
        undo.addActionListener(e -> undo());

        controls.add(new JLabel("Strength:"));
        controls.add(depthCombo);
        controls.add(newGame);
        controls.add(undo);
        return controls;
    }

    // ------------------------------------------------------------------
    // Interaction (invoked by the buttons and, headlessly, by the tests)
    // ------------------------------------------------------------------

    /**
     * Handles a click on square {@code idx}: selects a white piece, moves the
     * selected piece to a legal destination, or clears the selection.
     */
    public void clickSquare(int idx) {
        if (idx < 0 || idx > 63 || !model.isHumanTurn()) {
            return;
        }
        if (selected >= 0) {
            if (idx == selected) {
                clearSelection();
                return;
            }
            if (targets.contains(idx)) {
                doMove(selected, idx);
                return;
            }
        }
        if (model.whiteToMove() && model.sideOf(idx) == ChessModel.WHITE) {
            List<ChessModel.Move> moves = model.legalMovesFrom(idx);
            if (!moves.isEmpty()) {
                selected = idx;
                targets.clear();
                for (ChessModel.Move m : moves) {
                    targets.add(m.getTo());
                }
                refresh();
                return;
            }
        }
        clearSelection();
    }

    private void doMove(int from, int to) {
        ChessModel.Move move = model.makeHumanMove(from, to);
        clearSelection();
        if (move != null && !model.isGameOver()) {
            model.aiMove();
        }
        refresh();
    }

    private void clearSelection() {
        selected = -1;
        targets.clear();
        refresh();
    }

    /** Starts a fresh game (White to move, full material). */
    public void newGame() {
        model.newGame();
        clearSelection();
    }

    /** Rolls back the AI's reply and the human move that provoked it. */
    public void undo() {
        model.undo();
        if (!model.whiteToMove()) {
            model.undo();
        }
        clearSelection();
    }

    // ------------------------------------------------------------------
    // Painting / status
    // ------------------------------------------------------------------

    private void refresh() {
        ChessModel.Move last = model.getLastMove();
        for (int i = 0; i < 64; i++) {
            squares[i].setText(glyph(model.pieceAt(i)));
            squares[i].setBackground(squareColor(i, last));
        }
        status.setText(statusText());
    }

    private Color squareColor(int i, ChessModel.Move last) {
        if (i == selected) {
            return SELECTED;
        }
        if (targets.contains(i)) {
            return TARGET;
        }
        if (last != null && (last.getFrom() == i || last.getTo() == i)) {
            return LAST_MOVE;
        }
        int row = i / 8;
        int col = i % 8;
        return ((row + col) % 2 == 0) ? LIGHT : DARK;
    }

    private static String glyph(int piece) {
        if (piece == 0) {
            return "";
        }
        boolean white = piece > 0;
        switch (Math.abs(piece)) {
            case ChessModel.KING:   return white ? "\u2654" : "\u265A";
            case ChessModel.QUEEN:  return white ? "\u2655" : "\u265B";
            case ChessModel.ROOK:   return white ? "\u2656" : "\u265C";
            case ChessModel.BISHOP: return white ? "\u2657" : "\u265D";
            case ChessModel.KNIGHT: return white ? "\u2658" : "\u265E";
            default:                return white ? "\u2659" : "\u265F";
        }
    }

    /** The one-line state message shown above the board. */
    String statusText() {
        if (model.isCheckmate()) {
            return model.getWinner() == ChessModel.WHITE
                    ? "Checkmate — you win!" : "Checkmate — the computer wins";
        }
        if (model.isStalemate()) {
            return "Stalemate — draw";
        }
        if (model.isDraw()) {
            return "Draw";
        }
        String turn = model.whiteToMove()
                ? "Your move (White)" : "Computer's move (Black)";
        return model.inCheck() ? turn + " — Check!" : turn;
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    ChessModel model() {
        return model;
    }

    int selectedSquare() {
        return selected;
    }

    List<Integer> legalTargets() {
        return targets;
    }

    JButton squareButton(int i) {
        return squares[i];
    }
}
