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
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
 * desktops play identical chess. Here the board is a custom-painted {@link JPanel}
 * (the same approach {@code SolitairePanel} uses): the 8x8 tiles, the selection /
 * legal-target / last-move highlights and the pieces are all drawn directly, so
 * the board never depends on a look-and-feel's button chrome. Click a white piece
 * to select it (its legal destinations light up green), then click a destination
 * to move; the AI answers as Black. Pieces are the Unicode chess glyphs, hollow
 * for White and filled for Black, so no piece artwork beyond the platform font is
 * needed.
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
    private final Board board = new Board();
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JComboBox<String> depthCombo = new JComboBox<String>(DEPTH_LABELS);

    private int selected = -1;
    private final List<Integer> targets = new ArrayList<Integer>();

    public ChessPanel() {
        super(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        board.setPreferredSize(new Dimension(WIDTH_PX - 20, WIDTH_PX - 20));
        add(status, BorderLayout.NORTH);
        add(board, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        depthCombo.setSelectedIndex(1);   // Normal
        refresh();
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
    // Interaction (invoked by the board and, headlessly, by the tests)
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
        board.repaint();
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
    // The custom-painted board
    // ------------------------------------------------------------------

    /** Edge length of one square, derived from the (square) board area. */
    private int cell() {
        return Math.min(board.getWidth(), board.getHeight()) / 8;
    }

    private int originX() {
        return (board.getWidth() - cell() * 8) / 2;
    }

    private int originY() {
        return (board.getHeight() - cell() * 8) / 2;
    }

    /** Maps a board pixel to a square index (0..63), or -1 outside the grid. */
    int squareAt(int x, int y) {
        int c = cell();
        if (c <= 0) {
            return -1;
        }
        int ox = originX();
        int oy = originY();
        if (x < ox || y < oy || x >= ox + 8 * c || y >= oy + 8 * c) {
            return -1;
        }
        int col = (x - ox) / c;
        int row = (y - oy) / c;
        return row * 8 + col;
    }

    private final class Board extends JPanel {

        Board() {
            setBackground(PIECE);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    clickSquare(squareAt(e.getX(), e.getY()));
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            int c = cell();
            if (c <= 0) {
                return;
            }
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int ox = originX();
            int oy = originY();
            ChessModel.Move last = model.getLastMove();
            for (int i = 0; i < 64; i++) {
                int x = ox + (i % 8) * c;
                int y = oy + (i / 8) * c;
                g.setColor(squareColor(i, last));
                g.fillRect(x, y, c, c);
                String piece = glyph(model.pieceAt(i));
                if (!piece.isEmpty()) {
                    g.setColor(PIECE);
                    g.setFont(PIECE_FONT.deriveFont((float) (c * 0.72)));
                    FontMetrics fm = g.getFontMetrics();
                    int tx = x + (c - fm.stringWidth(piece)) / 2;
                    int ty = y + (c - fm.getHeight()) / 2 + fm.getAscent();
                    g.drawString(piece, tx, ty);
                }
                if (targets.contains(i)) {
                    // A clear dot so an empty legal target is still obvious.
                    g.setColor(new Color(0x2E, 0x7D, 0x32));
                    int d = Math.max(6, c / 5);
                    g.fillOval(x + (c - d) / 2, y + (c - d) / 2, d, d);
                }
            }
            g.setColor(PIECE);
            g.drawRect(ox, oy, c * 8, c * 8);
        }
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

    /** The Unicode glyph currently shown on square {@code i} ("" when empty). */
    String glyphAt(int i) {
        return glyph(model.pieceAt(i));
    }

    /**
     * Test hook: gives the board a real pixel size so the click-to-square
     * geometry ({@link #squareAt}) can be exercised without a display.
     */
    void sizeBoardForTest(int w, int h) {
        board.setSize(w, h);
    }
}
