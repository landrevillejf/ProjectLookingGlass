/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.games.tetris;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;

/**
 * The well: 10&times;20 cells of settled blocks, the piece now falling, a ghost
 * of where it would land, and the two overlay messages a run has for the
 * player.
 *
 * <p>Paint-only. It reads a {@link TetrisEngine} and draws it; every keystroke
 * and button is turned into an engine call by {@link TetrisPanel}, which owns
 * the loop. That is what keeps the rules testable without a canvas and the
 * canvas free to be re-shaded without touching a rule.</p>
 *
 * <p>The palette is the one exception to the desktop's "the look and feel owns
 * the colours" rule, exactly as it is for the shipped Chess / Solitaire /
 * Sudoku boards: the seven block colours and the dark well are the game's own
 * content, not window chrome, and a Tetris whose pieces wear the theme's
 * colours is not recognisable as Tetris. Every <em>widget</em> around this
 * canvas -- the sidebar's labels, buttons and sliders -- still wears the
 * active look and feel.</p>
 */
public class TetrisBoard extends JPanel {

    /** Pixel edge of one cell, at the window's natural size. */
    public static final int CELL_PX = 26;

    /** The classic well: near-black, so the seven block colours read clearly. */
    private static final Color WELL = new Color(12, 12, 16);
    /** Cell separators, one step up from the well. */
    private static final Color GRID_LINE = new Color(34, 34, 42);
    /** Where the current piece would land, drawn as an outline only. */
    private static final Color GHOST = new Color(255, 255, 255, 60);
    private static final Color GHOST_EDGE = new Color(255, 255, 255, 110);
    /** The veil the overlays sit behind. */
    private static final Color VEIL = new Color(0, 0, 0, 170);
    private static final Color OVERLAY_TEXT = Color.WHITE;

    private final TetrisEngine engine;

    public TetrisBoard(TetrisEngine engine) {
        this.engine = engine;
        setOpaque(true);
        setBackground(WELL);
        setFocusable(true);
        setRequestFocusEnabled(true);
        setPreferredSize(new Dimension(
                TetrisEngine.WIDTH * CELL_PX, TetrisEngine.HEIGHT * CELL_PX));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        paintGrid(g);
        paintSettled(g);
        Tetromino piece = engine.getCurrent();
        if (piece != null) {
            // The landing preview only while a run is actually playable: a
            // paused or finished board has no drop worth previewing. The piece
            // itself is drawn either way, so a game-over frame still shows the
            // block that did not fit.
            if (engine.isRunning()) {
                paintGhost(g);
            }
            paintCurrent(g);
        }
        paintOverlay(g);
    }

    private void paintGrid(Graphics2D g) {
        g.setColor(GRID_LINE);
        for (int x = 1; x < TetrisEngine.WIDTH; x++) {
            g.drawLine(x * CELL_PX, 0, x * CELL_PX, getHeight());
        }
        for (int y = 1; y < TetrisEngine.HEIGHT; y++) {
            g.drawLine(0, y * CELL_PX, getWidth(), y * CELL_PX);
        }
    }

    private void paintSettled(Graphics2D g) {
        for (int y = 0; y < TetrisEngine.HEIGHT; y++) {
            for (int x = 0; x < TetrisEngine.WIDTH; x++) {
                Color color = engine.getCell(x, y);
                if (color != null) {
                    paintBlock(g, x * CELL_PX, y * CELL_PX, color);
                }
            }
        }
    }

    private void paintCurrent(Graphics2D g) {
        Tetromino piece = engine.getCurrent();
        for (int r = 0; r < piece.height(); r++) {
            for (int c = 0; c < piece.width(); c++) {
                if (piece.covers(r, c)) {
                    paintBlock(g, (piece.x() + c) * CELL_PX,
                            (piece.y() + r) * CELL_PX, piece.color());
                }
            }
        }
    }

    /** The landing preview: the current shape, dropped to the floor. */
    private void paintGhost(Graphics2D g) {
        Tetromino piece = engine.getCurrent();
        Tetromino landed = piece.movedBy(0, engine.getDropDistance());
        if (landed.y() == piece.y()) {
            return;  // already resting: the block itself is the preview
        }
        g.setColor(GHOST);
        g.setStroke(new BasicStroke(1f));
        for (int r = 0; r < landed.height(); r++) {
            for (int c = 0; c < landed.width(); c++) {
                if (landed.covers(r, c)) {
                    int px = (landed.x() + c) * CELL_PX;
                    int py = (landed.y() + r) * CELL_PX;
                    g.fillRect(px + 1, py + 1, CELL_PX - 2, CELL_PX - 2);
                    g.setColor(GHOST_EDGE);
                    g.drawRect(px, py, CELL_PX - 1, CELL_PX - 1);
                    g.setColor(GHOST);
                }
            }
        }
    }

    /**
     * One cell of one block: the flat colour with a lit top-left edge and a
     * shaded bottom-right edge, the bevel that makes a stack of squares read
     * as a stack of blocks rather than a checkerboard.
     */
    private void paintBlock(Graphics2D g, int px, int py, Color color) {
        g.setColor(color);
        g.fillRect(px, py, CELL_PX - 1, CELL_PX - 1);
        g.setColor(color.brighter());
        g.drawLine(px, py, px + CELL_PX - 2, py);
        g.drawLine(px, py, px, py + CELL_PX - 2);
        g.setColor(color.darker());
        g.drawLine(px + CELL_PX - 2, py, px + CELL_PX - 2, py + CELL_PX - 2);
        g.drawLine(px, py + CELL_PX - 2, px + CELL_PX - 2, py + CELL_PX - 2);
    }

    /**
     * The two messages a run has no button for. A finished game says so on the
     * board rather than in a modal dialog: a dialog would leave the 3D desktop
     * for its own top-level window, and it would cover the stack the player
     * just lost, which is the only interesting thing on the screen.
     */
    private void paintOverlay(Graphics2D g) {
        String headline = null;
        String detail = null;
        if (engine.isPaused()) {
            headline = "PAUSED";
            detail = "Press P or click Resume";
        } else if (engine.isGameOver()) {
            headline = "GAME OVER";
            detail = "Score " + engine.getScore()
                    + "  -  click New game";
        }
        if (headline == null) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        g.setColor(VEIL);
        g.fillRect(0, 0, width, height);

        g.setColor(OVERLAY_TEXT);
        g.setFont(new Font(getFont().getFamily(), Font.BOLD, 22));
        FontMetrics bold = g.getFontMetrics();
        int headY = height / 2;
        g.drawString(headline, (width - bold.stringWidth(headline)) / 2, headY);

        g.setFont(new Font(getFont().getFamily(), Font.PLAIN, 12));
        FontMetrics plain = g.getFontMetrics();
        g.drawString(detail, (width - plain.stringWidth(detail)) / 2,
                headY + plain.getAscent() + 8);
    }
}
