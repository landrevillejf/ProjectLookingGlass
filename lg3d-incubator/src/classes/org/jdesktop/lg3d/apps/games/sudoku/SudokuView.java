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
 * The sudoku board, rasterised into a single live texture with the same
 * {@code ImageComponent2D.set} recipe as {@code AgendaGrid} / {@code MailView}.
 *
 * <p>A left click maps the pick's local intersection to a cell and selects it,
 * reporting the index through {@link CellListener}; the host enters digits into
 * the selected cell from its button strip. Givens, player entries and cells in
 * conflict are drawn in distinct colours, the selected cell and its row /
 * column / box are highlighted, and a status band reports the level, progress
 * and solved state.</p>
 */
public class SudokuView extends Component3D {

    /** Notified with the 0..80 cell index of a left click on the board. */
    public interface CellListener {
        void cellClicked(int cell);
    }

    private static final int TW = 512;
    private static final int TH = 512;
    private static final int TOP_PX = 56;
    private static final int CELL = (TH - TOP_PX) / 9;
    private static final int BOARD = CELL * 9;
    private static final int BOARD_X = (TW - BOARD) / 2;
    private static final int BOARD_Y = TOP_PX;

    private static final Color BG = new Color(0x0E, 0x14, 0x20, 0xF2);
    private static final Color TOP_BG = new Color(0x10, 0x1A, 0x2E, 0xFF);
    private static final Color BOARD_BG = new Color(0x16, 0x20, 0x33, 0xF8);
    private static final Color PEER = new Color(120, 180, 255, 26);
    private static final Color SELECTED = new Color(120, 180, 255, 90);
    private static final Color CONFLICT_BG = new Color(255, 90, 90, 70);
    private static final Color GRID_MINOR = new Color(255, 255, 255, 45);
    private static final Color GRID_MAJOR = new Color(190, 210, 235, 210);
    private static final Color GIVEN = new Color(210, 224, 240, 255);
    private static final Color ENTRY = new Color(120, 200, 255, 255);
    private static final Color BAD = new Color(255, 120, 120, 255);
    private static final Color TEXT = new Color(226, 236, 248, 255);
    private static final Color TEXT_DIM = new Color(168, 184, 204, 235);
    private static final Color ACCENT = new Color(120, 180, 255, 255);
    private static final Color WIN = new Color(120, 255, 160, 255);

    private static final Font STATUS_FONT = new Font("SansSerif", Font.BOLD, 22);
    private static final Font SUB_FONT = new Font("SansSerif", Font.PLAIN, 16);
    private static final Font DIGIT_FONT = new Font("SansSerif", Font.BOLD, 30);

    private final float width;
    private final float height;
    private final BufferedImage canvas;
    private final ImageComponent2D imageComponent;

    private SudokuModel model = new SudokuModel();
    private int selected = -1;
    private CellListener listener;

    public SudokuView(float width, float height) {
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

    public void setModel(SudokuModel model) {
        this.model = model;
        this.selected = -1;
        refresh();
    }

    public void setSelected(int cell) {
        this.selected = cell;
        refresh();
    }

    public int getSelected() {
        return selected;
    }

    public final void refresh() {
        redraw();
        imageComponent.set(canvas);
    }

    // ------------------------------------------------------------------
    // Click mapping
    // ------------------------------------------------------------------

    private void handleClick(float x, float y) {
        int cell = pickCell(x, y);
        if (cell >= 0) {
            selected = cell;
            refresh();
            if (listener != null) {
                listener.cellClicked(cell);
            }
        }
    }

    private int pickCell(float x, float y) {
        float nx = x / width + 0.5f;
        float ny = 0.5f - y / height;
        float px = nx * TW;
        float py = ny * TH;
        int col = (int) ((px - BOARD_X) / CELL);
        int row = (int) ((py - BOARD_Y) / CELL);
        if (px < BOARD_X || px >= BOARD_X + BOARD || col < 0 || col > 8) {
            return -1;
        }
        if (py < BOARD_Y || py >= BOARD_Y + BOARD || row < 0 || row > 8) {
            return -1;
        }
        return row * 9 + col;
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

        int filled = 0;
        for (int i = 0; i < 81; i++) {
            if (model.value(i) != 0) {
                filled++;
            }
        }
        String title = "Sudoku  " + model.getLevelName();
        g.setFont(STATUS_FONT);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(TEXT);
        g.drawString(title, 14, (TOP_PX - fm.getHeight()) / 2 + fm.getAscent());

        String right;
        Color rc;
        if (model.isSolved()) {
            right = "Solved!";
            rc = WIN;
        } else if (model.hasAnyConflict()) {
            right = "Conflict";
            rc = BAD;
        } else {
            right = filled + " / 81";
            rc = TEXT_DIM;
        }
        g.setFont(SUB_FONT);
        FontMetrics sm = g.getFontMetrics();
        g.setColor(rc);
        g.drawString(right, TW - 14 - sm.stringWidth(right),
                (TOP_PX - sm.getHeight()) / 2 + sm.getAscent());
    }

    private void drawBoard(Graphics2D g) {
        g.setColor(BOARD_BG);
        g.fillRect(BOARD_X, BOARD_Y, BOARD, BOARD);

        // Peer highlight (same row / column / box as the selection).
        if (selected >= 0) {
            int sr = selected / 9;
            int sc = selected % 9;
            g.setColor(PEER);
            for (int i = 0; i < 81; i++) {
                int r = i / 9;
                int c = i % 9;
                boolean sameBox = (r / 3 == sr / 3) && (c / 3 == sc / 3);
                if (r == sr || c == sc || sameBox) {
                    g.fillRect(BOARD_X + c * CELL, BOARD_Y + r * CELL,
                            CELL, CELL);
                }
            }
        }

        // Conflict + selection fills.
        for (int i = 0; i < 81; i++) {
            int r = i / 9;
            int c = i % 9;
            if (model.conflicts(i)) {
                g.setColor(CONFLICT_BG);
                g.fillRect(BOARD_X + c * CELL, BOARD_Y + r * CELL, CELL, CELL);
            }
        }
        if (selected >= 0) {
            g.setColor(SELECTED);
            g.fillRect(BOARD_X + (selected % 9) * CELL,
                    BOARD_Y + (selected / 9) * CELL, CELL, CELL);
        }

        // Digits.
        g.setFont(DIGIT_FONT);
        FontMetrics dm = g.getFontMetrics();
        for (int i = 0; i < 81; i++) {
            int v = model.value(i);
            if (v == 0) {
                continue;
            }
            int r = i / 9;
            int c = i % 9;
            String s = Integer.toString(v);
            if (model.conflicts(i)) {
                g.setColor(BAD);
            } else if (model.isFixed(i)) {
                g.setColor(GIVEN);
            } else {
                g.setColor(ENTRY);
            }
            int tx = BOARD_X + c * CELL + (CELL - dm.stringWidth(s)) / 2;
            int ty = BOARD_Y + r * CELL + (CELL - dm.getHeight()) / 2
                    + dm.getAscent();
            g.drawString(s, tx, ty);
        }

        // Minor grid lines.
        g.setColor(GRID_MINOR);
        g.setStroke(new BasicStroke(1.0f));
        for (int i = 1; i < 9; i++) {
            if (i % 3 == 0) {
                continue;   // drawn thicker below
            }
            g.drawLine(BOARD_X + i * CELL, BOARD_Y,
                    BOARD_X + i * CELL, BOARD_Y + BOARD);
            g.drawLine(BOARD_X, BOARD_Y + i * CELL,
                    BOARD_X + BOARD, BOARD_Y + i * CELL);
        }
        // Major (box) lines + border.
        g.setColor(GRID_MAJOR);
        g.setStroke(new BasicStroke(3.0f));
        for (int i = 0; i <= 9; i += 3) {
            g.drawLine(BOARD_X + i * CELL, BOARD_Y,
                    BOARD_X + i * CELL, BOARD_Y + BOARD);
            g.drawLine(BOARD_X, BOARD_Y + i * CELL,
                    BOARD_X + BOARD, BOARD_Y + i * CELL);
        }
    }
}
