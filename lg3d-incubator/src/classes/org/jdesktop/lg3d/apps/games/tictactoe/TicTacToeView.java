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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.Geometry;
import org.jdesktop.lg3d.sg.GeometryArray;
import org.jdesktop.lg3d.sg.ImageComponent2D;
import org.jdesktop.lg3d.sg.PolygonAttributes;
import org.jdesktop.lg3d.sg.QuadArray;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.TextureAttributes;
import org.jdesktop.lg3d.sg.TransparencyAttributes;
import org.jdesktop.lg3d.utils.action.ActionFloat3;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.wg.event.MouseEvent3D;

/**
 * The tic-tac-toe board, rasterised into a single live texture following the
 * {@code AgendaGrid} / {@code MailView} recipe: one fixed-size
 * {@link ImageComponent2D} ({@code FORMAT_RGBA}, {@code ALLOW_IMAGE_WRITE}) is
 * attached to a {@link Texture2D} once, off-live; every later change only
 * repaints the {@code BufferedImage} and calls {@link ImageComponent2D#set} in
 * place, so no texture is ever re-attached to the live scene graph.
 *
 * <p>A left click maps the pick's local intersection to one of the nine cells
 * and reports it through {@link CellListener}; the host decides whether the
 * move is legal. The status band along the top shows whose turn it is and the
 * result, and the winning line (if any) is drawn through the three cells.</p>
 */
public class TicTacToeView extends Component3D {

    /** Notified with the 0..8 cell index of a left click on the board. */
    public interface CellListener {
        void cellClicked(int cell);
    }

    private static final int TW = 512;
    private static final int TH = 512;
    private static final int TOP_PX = 72;              // status band height
    private static final int BOARD_PX = TH - TOP_PX;   // square board area
    private static final int CELL = BOARD_PX / 3;

    private static final Color BG = new Color(0x0E, 0x14, 0x20, 0xF2);
    private static final Color TOP_BG = new Color(0x10, 0x1A, 0x2E, 0xFF);
    private static final Color BOARD_BG = new Color(0x14, 0x1E, 0x30, 0xF6);
    private static final Color CELL_ALT = new Color(255, 255, 255, 8);
    private static final Color GRID = new Color(150, 180, 220, 200);
    private static final Color X_COLOR = new Color(120, 200, 255, 255);
    private static final Color O_COLOR = new Color(255, 170, 120, 255);
    private static final Color TEXT = new Color(226, 236, 248, 255);
    private static final Color TEXT_DIM = new Color(168, 184, 204, 235);
    private static final Color ACCENT = new Color(120, 180, 255, 255);
    private static final Color WIN = new Color(120, 255, 160, 220);

    private static final Font STATUS_FONT = new Font("SansSerif", Font.BOLD, 26);
    private static final Font MARK_FONT = new Font("SansSerif", Font.BOLD, 96);

    private final float width;
    private final float height;
    private final BufferedImage canvas;
    private final ImageComponent2D imageComponent;

    private TicTacToeModel model = new TicTacToeModel();
    private CellListener listener;

    public TicTacToeView(float width, float height) {
        this.width = width;
        this.height = height;

        canvas = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
        imageComponent = new ImageComponent2D(
                ImageComponent2D.FORMAT_RGBA, TW, TH, false, true);
        imageComponent.setCapability(ImageComponent2D.ALLOW_IMAGE_WRITE);

        Texture2D texture = new Texture2D(
                Texture2D.BASE_LEVEL, Texture2D.RGBA, TW, TH);
        texture.setMinFilter(Texture2D.BASE_LEVEL_LINEAR);
        texture.setMagFilter(Texture2D.BASE_LEVEL_LINEAR);
        texture.setBoundaryModeS(Texture2D.CLAMP);
        texture.setBoundaryModeT(Texture2D.CLAMP);
        texture.setImage(0, imageComponent);

        Appearance appearance = new Appearance();
        TextureAttributes texAttr = new TextureAttributes();
        texAttr.setTextureMode(TextureAttributes.REPLACE);
        appearance.setTextureAttributes(texAttr);
        appearance.setTexture(texture);
        appearance.setPolygonAttributes(new PolygonAttributes(
                PolygonAttributes.POLYGON_FILL, PolygonAttributes.CULL_NONE,
                0.0f, false, 0.0f));
        appearance.setTransparencyAttributes(new TransparencyAttributes(
                TransparencyAttributes.BLENDED, 0.0f,
                TransparencyAttributes.BLEND_SRC_ALPHA,
                TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA));

        float hx = width * 0.5f;
        float hy = height * 0.5f;
        QuadArray quad = new QuadArray(4,
                GeometryArray.COORDINATES | GeometryArray.TEXTURE_COORDINATE_2);
        quad.setCoordinates(0, new float[] {
            -hx, -hy, 0.0f,  hx, -hy, 0.0f,  hx, hy, 0.0f,  -hx, hy, 0.0f });
        quad.setTextureCoordinates(0, 0, new float[] {
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f });
        // PickEngine runs PICK_GEOMETRY + CLOSEST_INTERSECTION_POINT; the quad
        // needs ALLOW_INTERSECT for getLocalIntersection to report a point.
        quad.setCapability(Geometry.ALLOW_INTERSECT);
        addChild(new Shape3D(quad, appearance));

        addListener(new MouseClickedEventAdapter(
                MouseEvent3D.ButtonId.BUTTON1, false, null,
                new ActionFloat3() {
                    public void performAction(LgEventSource source,
                            float x, float y, float z) {
                        handleClick(x, y);
                    }
                }));
        setCursor(Cursor3D.SMALL_CURSOR);

        refresh();
    }

    // ------------------------------------------------------------------
    // Host wiring
    // ------------------------------------------------------------------

    public void setCellListener(CellListener listener) {
        this.listener = listener;
    }

    public void setModel(TicTacToeModel model) {
        this.model = model;
        refresh();
    }

    public TicTacToeModel getModel() {
        return model;
    }

    /** Repaints the canvas and uploads it in place (safe on a live graph). */
    public final void refresh() {
        redraw();
        imageComponent.set(canvas);
    }

    // ------------------------------------------------------------------
    // Click mapping
    // ------------------------------------------------------------------

    private void handleClick(float x, float y) {
        int cell = pickCell(x, y);
        if (cell >= 0 && listener != null) {
            listener.cellClicked(cell);
        }
    }

    /** Maps a local intersection point to the 0..8 cell under it, or -1. */
    private int pickCell(float x, float y) {
        float nx = x / width + 0.5f;   // 0 at left edge, 1 at right
        float ny = 0.5f - y / height;  // 0 at top edge, 1 at bottom
        float px = nx * TW;
        float py = ny * TH;
        if (py < TOP_PX || px < 0 || px >= TW) {
            return -1;
        }
        int col = (int) (px / CELL);
        int row = (int) ((py - TOP_PX) / CELL);
        if (col < 0 || col > 2 || row < 0 || row > 2) {
            return -1;
        }
        return row * 3 + col;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void redraw() {
        Graphics2D g = canvas.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(BG);
            g.fillRect(0, 0, TW, TH);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);

            drawStatus(g);
            drawBoard(g);
        } finally {
            g.dispose();
        }
    }

    private void drawStatus(Graphics2D g) {
        g.setColor(TOP_BG);
        g.fillRect(0, 0, TW, TOP_PX);
        g.setColor(ACCENT);
        g.fillRect(0, TOP_PX - 2, TW, 2);

        String msg;
        Color color;
        if (model.getWinner() == TicTacToeModel.HUMAN) {
            msg = "You win!";
            color = WIN;
        } else if (model.getWinner() == TicTacToeModel.AI) {
            msg = "AI wins";
            color = O_COLOR;
        } else if (model.isDraw()) {
            msg = "Draw";
            color = TEXT_DIM;
        } else if (model.getTurn() == TicTacToeModel.HUMAN) {
            msg = "Your turn  (X)";
            color = X_COLOR;
        } else {
            msg = "AI thinking  (O)";
            color = O_COLOR;
        }

        g.setFont(STATUS_FONT);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(color);
        g.drawString(msg, (TW - fm.stringWidth(msg)) / 2,
                (TOP_PX - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void drawBoard(Graphics2D g) {
        g.setColor(BOARD_BG);
        g.fillRect(0, TOP_PX, TW, BOARD_PX);

        // Zebra shading so the cells read clearly against the board.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if ((row + col) % 2 == 1) {
                    g.setColor(CELL_ALT);
                    g.fillRect(col * CELL, TOP_PX + row * CELL, CELL, CELL);
                }
            }
        }

        // Marks.
        g.setFont(MARK_FONT);
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < 9; i++) {
            int v = model.cell(i);
            if (v == TicTacToeModel.EMPTY) {
                continue;
            }
            int col = i % 3;
            int row = i / 3;
            String mark = (v == TicTacToeModel.HUMAN) ? "X" : "O";
            g.setColor(v == TicTacToeModel.HUMAN ? X_COLOR : O_COLOR);
            int tx = col * CELL + (CELL - fm.stringWidth(mark)) / 2;
            int ty = TOP_PX + row * CELL + (CELL - fm.getHeight()) / 2
                    + fm.getAscent();
            g.drawString(mark, tx, ty);
        }

        // Grid lines (drawn over the marks' cell borders).
        g.setColor(GRID);
        g.setStroke(new BasicStroke(3.0f));
        for (int i = 1; i < 3; i++) {
            g.drawLine(i * CELL, TOP_PX + 6, i * CELL, TH - 6);
            g.drawLine(6, TOP_PX + i * CELL, TW - 6, TOP_PX + i * CELL);
        }

        // Winning line through the three cells.
        int[] line = model.getWinLine();
        if (line != null) {
            g.setColor(WIN);
            g.setStroke(new BasicStroke(7.0f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            int x1 = (line[0] % 3) * CELL + CELL / 2;
            int y1 = TOP_PX + (line[0] / 3) * CELL + CELL / 2;
            int x2 = (line[2] % 3) * CELL + CELL / 2;
            int y2 = TOP_PX + (line[2] / 3) * CELL + CELL / 2;
            g.drawLine(x1, y1, x2, y2);
        }
    }
}
