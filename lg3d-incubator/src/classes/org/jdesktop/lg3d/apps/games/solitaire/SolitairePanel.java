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
package org.jdesktop.lg3d.apps.games.solitaire;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The 2D/Swing counterpart of the native-3D {@link Solitaire3D}. It reuses the
 * very same {@link SolitaireModel} (an AWT-free Klondike engine: recycling stock,
 * four foundations, seven tableau piles, run dragging, auto-finish, undo and
 * hints) that the {@code Frame3D} host drives, so both desktops play identical
 * solitaire. Here the table is a custom-painted {@link JPanel} with click-to-
 * select / click-to-move: click the stock to draw, click a face-up card (or run)
 * to pick it up, click a destination pile or foundation to drop it, and
 * double-click a card to send it to its foundation. Cards are drawn with vector
 * suit glyphs, so no card artwork is needed.
 *
 * <p>Registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code Solitaire3D} main class, so the one shared start-menu descriptor
 * launches this panel as an MDI internal frame in the 2D/Swing desktop while the
 * 3D desktop keeps building the {@code Frame3D}. The panel loads no Java 3D
 * class, so it also runs where the 3D desktop is unavailable.</p>
 */
public class SolitairePanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 560;
    public static final int HEIGHT_PX = 620;

    private static final int CARD_W = 62;
    private static final int CARD_H = 86;
    private static final int GAP = 12;
    private static final int MARGIN = 12;
    private static final int DOWN_OFF = 16;
    private static final int UP_OFF = 26;
    private static final int TABLEAU_TOP_GAP = 26;

    private static final Color FELT = new Color(0x1B, 0x5E, 0x20);
    private static final Color CARD_FACE = Color.WHITE;
    private static final Color CARD_BACK = new Color(0x2C, 0x3E, 0x8C);
    private static final Color RED = new Color(0xC0, 0x1B, 0x1B);
    private static final Color BLACK = new Color(0x1A, 0x1A, 0x1A);
    private static final Color OUTLINE = new Color(0x9E, 0x9E, 0x9E);
    private static final Color HIGHLIGHT = new Color(0xFF, 0xD5, 0x4F);
    private static final Font RANK_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 15);
    private static final Font SUIT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 26);

    private final SolitaireModel model = new SolitaireModel();
    private final Board board = new Board();
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);

    private int selZone = SolitaireModel.ZONE_NONE;
    private int selPile = -1;
    private int selPos = -1;
    private SolitaireModel.Hint hint;

    public SolitairePanel() {
        super(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        status.setForeground(Color.WHITE);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        header.add(status, BorderLayout.CENTER);
        header.setBackground(FELT);
        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        board.setBackground(FELT);
        board.setPreferredSize(new Dimension(WIDTH_PX - 24, HEIGHT_PX - 90));

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(FELT);
        wrap.add(header, BorderLayout.NORTH);
        wrap.add(board, BorderLayout.CENTER);

        add(wrap, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        refreshStatus();
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        JButton newGame = new JButton("New Game");
        newGame.addActionListener(e -> newGame());
        JButton undo = new JButton("Undo");
        undo.addActionListener(e -> undo());
        JButton hintButton = new JButton("Hint");
        hintButton.addActionListener(e -> hint());
        JButton auto = new JButton("Auto-finish");
        auto.addActionListener(e -> autoFinish());
        controls.add(newGame);
        controls.add(undo);
        controls.add(hintButton);
        controls.add(auto);
        return controls;
    }

    // ------------------------------------------------------------------
    // Interaction (invoked by the mouse and, headlessly, by the tests)
    // ------------------------------------------------------------------

    /**
     * Handles a single click on a zone. {@code zone} is one of the model's
     * {@code ZONE_*} constants; {@code pile} the foundation / tableau index; and
     * {@code pos} the tableau card position ({@code -1} for an empty pile or a
     * non-tableau zone).
     */
    public void handleClick(int zone, int pile, int pos) {
        hint = null;
        switch (zone) {
            case SolitaireModel.ZONE_STOCK:
                model.drawStock();
                clearSelection();
                break;
            case SolitaireModel.ZONE_WASTE:
                if (model.wasteTop() >= 0) {
                    selZone = SolitaireModel.ZONE_WASTE;
                    selPile = -1;
                    selPos = -1;
                }
                break;
            case SolitaireModel.ZONE_FOUNDATION:
                if (hasSelection()) {
                    moveToFoundation(pile);
                    clearSelection();
                }
                break;
            case SolitaireModel.ZONE_TABLEAU:
                if (hasSelection()) {
                    moveToTableau(pile);
                    clearSelection();
                } else if (pos >= 0 && model.isFaceUp(pile, pos)
                        && model.isMovableRun(pile, pos)) {
                    selZone = SolitaireModel.ZONE_TABLEAU;
                    selPile = pile;
                    selPos = pos;
                }
                break;
            default:
                break;
        }
        refresh();
    }

    /** Sends the clicked card straight to its foundation, if one accepts it. */
    public void handleDoubleClick(int zone, int pile) {
        hint = null;
        if (zone == SolitaireModel.ZONE_WASTE) {
            model.sendWasteToFoundation();
        } else if (zone == SolitaireModel.ZONE_TABLEAU) {
            model.sendTableauToFoundation(pile);
        }
        clearSelection();
        refresh();
    }

    private void moveToFoundation(int f) {
        if (selZone == SolitaireModel.ZONE_WASTE) {
            model.moveWasteToFoundation(f);
        } else if (selZone == SolitaireModel.ZONE_TABLEAU
                && selPos == model.tableauSize(selPile) - 1) {
            model.moveTableauToFoundation(selPile, f);
        }
    }

    private void moveToTableau(int dest) {
        if (selZone == SolitaireModel.ZONE_WASTE) {
            model.moveWasteToTableau(dest);
        } else if (selZone == SolitaireModel.ZONE_TABLEAU) {
            model.moveTableauToTableau(selPile, selPos, dest);
        }
    }

    /** Starts a fresh deal. */
    public void newGame() {
        model.newGame();
        clearSelection();
        hint = null;
        refresh();
    }

    /** Rolls back exactly one move. */
    public void undo() {
        if (model.undo()) {
            clearSelection();
            hint = null;
            refresh();
        } else {
            status.setText("Nothing to undo");
        }
    }

    /** Highlights one suggested move (or prompts to draw from the stock). */
    public void hint() {
        hint = model.hint();
        if (hint == null) {
            status.setText("No moves available — draw from the stock");
        } else {
            status.setText("Hint: " + describe(hint));
        }
        board.repaint();
    }

    /** Greedily builds every available card onto the foundations. */
    public void autoFinish() {
        if (!model.canAutoComplete()) {
            status.setText("Not ready to auto-finish yet");
            return;
        }
        int moved = model.autoComplete();
        clearSelection();
        hint = null;
        refresh();
        status.setText(moved > 0
                ? "Auto-finished " + moved + " card" + (moved == 1 ? "" : "s")
                : "No cards to auto-finish");
    }

    private String describe(SolitaireModel.Hint h) {
        String card = SolitaireModel.rankLabel(h.card)
                + suitChar(SolitaireModel.suit(h.card));
        String dest = (h.destZone == SolitaireModel.ZONE_FOUNDATION)
                ? "the foundation" : "column " + (h.destIndex + 1);
        return card + " onto " + dest;
    }

    private boolean hasSelection() {
        return selZone != SolitaireModel.ZONE_NONE;
    }

    private void clearSelection() {
        selZone = SolitaireModel.ZONE_NONE;
        selPile = -1;
        selPos = -1;
    }

    private void refresh() {
        board.repaint();
        refreshStatus();
    }

    private void refreshStatus() {
        if (model.isWon()) {
            status.setText("You win! Finished in " + model.getMoves() + " moves");
        } else {
            status.setText("Moves: " + model.getMoves());
        }
    }

    // ------------------------------------------------------------------
    // Geometry / hit-testing
    // ------------------------------------------------------------------

    private static int colX(int col) {
        return MARGIN + col * (CARD_W + GAP);
    }

    private int tableauY() {
        return MARGIN + CARD_H + TABLEAU_TOP_GAP;
    }

    /** The y offset of card {@code pos} in tableau pile {@code p}. */
    private int cardY(int p, int pos) {
        int y = tableauY();
        for (int i = 0; i < pos; i++) {
            y += model.isFaceUp(p, i) ? UP_OFF : DOWN_OFF;
        }
        return y;
    }

    private Rectangle stockRect() {
        return new Rectangle(colX(0), MARGIN, CARD_W, CARD_H);
    }

    private Rectangle wasteRect() {
        return new Rectangle(colX(1), MARGIN, CARD_W, CARD_H);
    }

    private Rectangle foundationRect(int f) {
        return new Rectangle(colX(3 + f), MARGIN, CARD_W, CARD_H);
    }

    private Rectangle tableauRect(int p, int pos) {
        return new Rectangle(colX(p), cardY(p, pos), CARD_W, CARD_H);
    }

    /** Maps a board point to {@code {zone, pile, pos}} (pos = -1 when n/a). */
    int[] hitTest(int x, int y) {
        Point pt = new Point(x, y);
        if (stockRect().contains(pt)) {
            return new int[] { SolitaireModel.ZONE_STOCK, -1, -1 };
        }
        if (wasteRect().contains(pt)) {
            return new int[] { SolitaireModel.ZONE_WASTE, -1, -1 };
        }
        for (int f = 0; f < 4; f++) {
            if (foundationRect(f).contains(pt)) {
                return new int[] { SolitaireModel.ZONE_FOUNDATION, f, -1 };
            }
        }
        for (int p = 0; p < 7; p++) {
            int size = model.tableauSize(p);
            if (size == 0) {
                if (tableauRect(p, 0).contains(pt)) {
                    return new int[] { SolitaireModel.ZONE_TABLEAU, p, -1 };
                }
                continue;
            }
            for (int pos = size - 1; pos >= 0; pos--) {
                if (tableauRect(p, pos).contains(pt)) {
                    return new int[] { SolitaireModel.ZONE_TABLEAU, p, pos };
                }
            }
        }
        return new int[] { SolitaireModel.ZONE_NONE, -1, -1 };
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    private static char suitChar(int suit) {
        switch (suit) {
            case SolitaireModel.HEARTS:   return '\u2665';
            case SolitaireModel.DIAMONDS: return '\u2666';
            case SolitaireModel.CLUBS:    return '\u2663';
            default:                      return '\u2660';   // SPADES
        }
    }

    private final class Board extends JPanel {

        Board() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int[] hit = hitTest(e.getX(), e.getY());
                    if (hit[0] == SolitaireModel.ZONE_NONE) {
                        clearSelection();
                        hint = null;
                        refresh();
                        return;
                    }
                    handleClick(hit[0], hit[1], hit[2]);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() >= 2) {
                        int[] hit = hitTest(e.getX(), e.getY());
                        if (hit[0] == SolitaireModel.ZONE_WASTE
                                || hit[0] == SolitaireModel.ZONE_TABLEAU) {
                            handleDoubleClick(hit[0], hit[1]);
                        }
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawStock(g);
            drawWaste(g);
            for (int f = 0; f < 4; f++) {
                drawFoundation(g, f);
            }
            for (int p = 0; p < 7; p++) {
                drawTableau(g, p);
            }
        }

        private void drawStock(Graphics2D g) {
            Rectangle r = stockRect();
            if (model.stockSize() > 0) {
                drawCardBack(g, r);
                g.setColor(Color.WHITE);
                g.setFont(RANK_FONT);
                g.drawString(Integer.toString(model.stockSize()),
                        r.x + 6, r.y + r.height - 8);
            } else {
                drawEmpty(g, r, "\u21BB");
            }
        }

        private void drawWaste(Graphics2D g) {
            Rectangle r = wasteRect();
            int top = model.wasteTop();
            if (top >= 0) {
                drawCardFace(g, r, top, isSelectedWaste());
            } else {
                drawEmpty(g, r, "");
            }
        }

        private void drawFoundation(Graphics2D g, int f) {
            Rectangle r = foundationRect(f);
            int top = model.foundationTop(f);
            if (top >= 0) {
                drawCardFace(g, r, top, isHintDestFoundation(f));
            } else {
                drawEmpty(g, r, String.valueOf(suitChar(f)));
            }
        }

        private void drawTableau(Graphics2D g, int p) {
            int size = model.tableauSize(p);
            if (size == 0) {
                drawEmpty(g, tableauRect(p, 0), "");
                return;
            }
            for (int pos = 0; pos < size; pos++) {
                Rectangle r = tableauRect(p, pos);
                int card = model.tableauCard(p, pos);
                if (!model.isFaceUp(p, pos)) {
                    drawCardBack(g, r);
                } else {
                    drawCardFace(g, r, card, isSelectedTableau(p, pos));
                }
            }
        }

        private void drawEmpty(Graphics2D g, Rectangle r, String label) {
            g.setColor(new Color(0xFF, 0xFF, 0xFF, 40));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setColor(OUTLINE);
            g.setStroke(new BasicStroke(1.4f));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            if (!label.isEmpty()) {
                g.setColor(new Color(0xFF, 0xFF, 0xFF, 150));
                g.setFont(SUIT_FONT);
                g.drawString(label, r.x + r.width / 2 - 8, r.y + r.height / 2 + 9);
            }
        }

        private void drawCardBack(Graphics2D g, Rectangle r) {
            g.setColor(CARD_BACK);
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setColor(new Color(0xFF, 0xFF, 0xFF, 90));
            g.setStroke(new BasicStroke(1.2f));
            g.drawRoundRect(r.x + 5, r.y + 5, r.width - 10, r.height - 10, 8, 8);
        }

        private void drawCardFace(Graphics2D g, Rectangle r, int card,
                boolean selected) {
            g.setColor(CARD_FACE);
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            boolean red = SolitaireModel.isRed(card);
            g.setColor(red ? RED : BLACK);
            g.setFont(RANK_FONT);
            g.drawString(SolitaireModel.rankLabel(card), r.x + 6, r.y + 18);
            g.setFont(SUIT_FONT);
            g.drawString(String.valueOf(suitChar(SolitaireModel.suit(card))),
                    r.x + r.width / 2 - 9, r.y + r.height / 2 + 12);
            g.setColor(selected ? HIGHLIGHT : OUTLINE);
            g.setStroke(new BasicStroke(selected ? 2.6f : 1.2f));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        }
    }

    private boolean isSelectedWaste() {
        return selZone == SolitaireModel.ZONE_WASTE;
    }

    private boolean isSelectedTableau(int p, int pos) {
        return selZone == SolitaireModel.ZONE_TABLEAU && selPile == p
                && pos >= selPos;
    }

    private boolean isHintDestFoundation(int f) {
        return hint != null && hint.destZone == SolitaireModel.ZONE_FOUNDATION
                && hint.destIndex == f;
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    SolitaireModel model() {
        return model;
    }

    int selectionZone() {
        return selZone;
    }

    int selectionPile() {
        return selPile;
    }

    int selectionPos() {
        return selPos;
    }

    String statusText() {
        return status.getText();
    }
}
