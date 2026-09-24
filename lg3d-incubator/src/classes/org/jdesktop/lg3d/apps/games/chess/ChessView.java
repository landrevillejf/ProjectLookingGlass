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
package org.jdesktop.lg3d.apps.games.chess;

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
 * The chess board, rasterised into a single live texture with the same
 * {@code ImageComponent2D.set} recipe as {@code AgendaGrid} / {@code MailView}.
 *
 * <p>Pieces are drawn as discs bearing a letter (K / Q / R / B / N / P) rather
 * than the Unicode chess glyphs, which are not reliably present in a headless
 * JRE's logical fonts. A left click maps the pick's local intersection to a
 * board square and reports it through {@link SquareListener}; the host drives
 * selection and move-making and pushes highlight state back via
 * {@link #setSelected}, {@link #setLegalTargets} and {@link #setFlipped}. The
 * view reads piece placement, the last move and check state straight from the
 * model.</p>
 */
public class ChessView extends Component3D {

    /** Notified with the 0..63 model-space square index of a left click. */
    public interface SquareListener {
        void squareClicked(int square);
    }

    private static final int TW = 512;
    private static final int TH = 512;
    private static final int TOP_PX = 52;
    private static final int CELL = (TH - TOP_PX) / 8;
    private static final int BOARD = CELL * 8;
    private static final int BOARD_X = (TW - BOARD) / 2;
    private static final int BOARD_Y = TOP_PX;

    private static final Color BG = new Color(0x0E, 0x14, 0x20, 0xF2);
    private static final Color TOP_BG = new Color(0x10, 0x1A, 0x2E, 0xFF);
    private static final Color LIGHT_SQ = new Color(0x9A, 0xB2, 0xD0);
    private static final Color DARK_SQ = new Color(0x46, 0x5C, 0x82);
    private static final Color BORDER = new Color(0x1B, 0x27, 0x3C, 0xFF);
    private static final Color SELECTED = new Color(255, 214, 90, 150);
    private static final Color LAST_MOVE = new Color(120, 200, 255, 95);
    private static final Color CHECK = new Color(255, 70, 70, 165);
    private static final Color DOT = new Color(20, 26, 38, 120);
    private static final Color CAP_RING = new Color(255, 90, 90, 190);
    private static final Color COORD = new Color(255, 255, 255, 120);

    private static final Color WHITE_DISC = new Color(246, 249, 253, 255);
    private static final Color WHITE_RING = new Color(40, 52, 74, 255);
    private static final Color WHITE_LETTER = new Color(28, 38, 58, 255);
    private static final Color BLACK_DISC = new Color(30, 36, 50, 255);
    private static final Color BLACK_RING = new Color(205, 216, 232, 255);
    private static final Color BLACK_LETTER = new Color(233, 240, 250, 255);

    private static final Color TEXT = new Color(226, 236, 248, 255);
    private static final Color TEXT_DIM = new Color(168, 184, 204, 235);
    private static final Color ACCENT = new Color(120, 180, 255, 255);
    private static final Color WARN = new Color(255, 190, 90, 255);
    private static final Color WIN = new Color(120, 255, 160, 255);
    private static final Color LOSE = new Color(255, 120, 120, 255);

    private static final Font STATUS_FONT = new Font("SansSerif", Font.BOLD, 22);
    private static final Font SUB_FONT = new Font("SansSerif", Font.PLAIN, 16);
    private static final Font COORD_FONT = new Font("SansSerif", Font.BOLD, 11);

    private static final char[] GLYPH = {
        '?', 'P', 'N', 'B', 'R', 'Q', 'K'
    };

    private final float width;
    private final float height;
    private final BufferedImage canvas;
    private final ImageComponent2D imageComponent;

    private ChessModel model = new ChessModel();
    private final boolean[] legal = new boolean[64];
    private int selected = -1;
    private boolean flipped = false;
    private SquareListener listener;

    public ChessView(float width, float height) {
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

    public void setSquareListener(SquareListener listener) {
        this.listener = listener;
    }

    public void setModel(ChessModel model) {
        this.model = model;
        clearSelection();
        refresh();
    }

    /** Highlights {@code square} and marks {@code targets} as legal replies. */
    public void setSelection(int square, int[] targets) {
        this.selected = square;
        java.util.Arrays.fill(legal, false);
        if (targets != null) {
            for (int t : targets) {
                if (t >= 0 && t < 64) {
                    legal[t] = true;
                }
            }
        }
        refresh();
    }

    public void clearSelection() {
        selected = -1;
        java.util.Arrays.fill(legal, false);
    }

    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
        refresh();
    }

    public boolean isFlipped() {
        return flipped;
    }

    public final void refresh() {
        redraw();
        imageComponent.set(canvas);
    }

    // ------------------------------------------------------------------
    // Click mapping
    // ------------------------------------------------------------------

    private void handleClick(float x, float y) {
        int square = pickSquare(x, y);
        if (square >= 0 && listener != null) {
            listener.squareClicked(square);
        }
    }

    /** Maps a local intersection to a model-space square, honouring flip. */
    private int pickSquare(float x, float y) {
        float nx = x / width + 0.5f;
        float ny = 0.5f - y / height;
        float px = nx * TW;
        float py = ny * TH;
        int dcol = (int) ((px - BOARD_X) / CELL);
        int drow = (int) ((py - BOARD_Y) / CELL);
        if (px < BOARD_X || px >= BOARD_X + BOARD || dcol < 0 || dcol > 7) {
            return -1;
        }
        if (py < BOARD_Y || py >= BOARD_Y + BOARD || drow < 0 || drow > 7) {
            return -1;
        }
        int r = flipped ? 7 - drow : drow;
        int c = flipped ? 7 - dcol : dcol;
        return r * 8 + c;
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

        g.setFont(STATUS_FONT);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(TEXT);
        g.drawString("Chess", 14, (TOP_PX - fm.getHeight()) / 2 + fm.getAscent());

        String msg;
        Color mc;
        if (model.isCheckmate()) {
            int winner = model.getWinner();
            if (winner == ChessModel.WHITE) {
                msg = "Checkmate — you win!";
                mc = WIN;
            } else {
                msg = "Checkmate — you lose";
                mc = LOSE;
            }
        } else if (model.isStalemate()) {
            msg = "Stalemate — draw";
            mc = WARN;
        } else if (model.isDraw()) {
            msg = "Draw";
            mc = WARN;
        } else if (model.inCheck()) {
            msg = "Check! Your move";
            mc = LOSE;
        } else {
            msg = "Your move (White)";
            mc = TEXT_DIM;
        }
        g.setFont(SUB_FONT);
        FontMetrics sm = g.getFontMetrics();
        g.setColor(mc);
        g.drawString(msg, TW - 14 - sm.stringWidth(msg),
                (TOP_PX - sm.getHeight()) / 2 + sm.getAscent());
    }

    private void drawBoard(Graphics2D g) {
        // Squares.
        for (int drow = 0; drow < 8; drow++) {
            for (int dcol = 0; dcol < 8; dcol++) {
                int r = flipped ? 7 - drow : drow;
                int c = flipped ? 7 - dcol : dcol;
                int sq = r * 8 + c;
                int x = BOARD_X + dcol * CELL;
                int y = BOARD_Y + drow * CELL;
                g.setColor(((r + c) & 1) == 0 ? LIGHT_SQ : DARK_SQ);
                g.fillRect(x, y, CELL, CELL);
                drawCoord(g, drow, dcol, r, c, x, y);
            }
        }

        // Last-move highlight.
        ChessModel.Move last = model.getLastMove();
        if (last != null) {
            g.setColor(LAST_MOVE);
            fillSquareAt(g, last.getFrom());
            fillSquareAt(g, last.getTo());
        }

        // Check highlight on the side-to-move king.
        if (model.inCheck()) {
            int king = kingSquare(model.whiteToMove());
            if (king >= 0) {
                g.setColor(CHECK);
                fillSquareAt(g, king);
            }
        }

        // Selection highlight.
        if (selected >= 0) {
            g.setColor(SELECTED);
            fillSquareAt(g, selected);
        }

        // Legal-move markers.
        for (int sq = 0; sq < 64; sq++) {
            if (!legal[sq]) {
                continue;
            }
            int[] xy = squareXY(sq);
            int cx = xy[0] + CELL / 2;
            int cy = xy[1] + CELL / 2;
            if (model.pieceAt(sq) != 0) {
                g.setColor(CAP_RING);
                g.setStroke(new BasicStroke(3.5f));
                g.drawOval(xy[0] + 3, xy[1] + 3, CELL - 6, CELL - 6);
            } else {
                g.setColor(DOT);
                int rad = Math.max(5, CELL / 7);
                g.fillOval(cx - rad, cy - rad, rad * 2, rad * 2);
            }
        }

        // Pieces (on top of markers so they stay legible).
        for (int sq = 0; sq < 64; sq++) {
            int piece = model.pieceAt(sq);
            if (piece != 0) {
                drawPiece(g, sq, piece);
            }
        }

        // Border.
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(3.0f));
        g.drawRect(BOARD_X - 1, BOARD_Y - 1, BOARD + 2, BOARD + 2);
    }

    private void drawCoord(Graphics2D g, int drow, int dcol, int r, int c,
            int x, int y) {
        g.setFont(COORD_FONT);
        FontMetrics cm = g.getFontMetrics();
        boolean dark = ((r + c) & 1) != 0;
        g.setColor(dark ? new Color(210, 222, 238, 130) : new Color(40, 54, 78, 150));
        if (dcol == 0) {                       // rank number, top-left
            String s = Integer.toString(8 - r);
            g.drawString(s, x + 3, y + cm.getAscent() + 2);
        }
        if (drow == 7) {                       // file letter, bottom-right
            String s = Character.toString((char) ('a' + c));
            g.drawString(s, x + CELL - cm.stringWidth(s) - 3,
                    y + CELL - 3);
        }
    }

    private void drawPiece(Graphics2D g, int sq, int piece) {
        int[] xy = squareXY(sq);
        int cx = xy[0] + CELL / 2;
        int cy = xy[1] + CELL / 2;
        int rad = (int) (CELL * 0.42f);
        boolean white = piece > 0;
        int type = Math.abs(piece);

        g.setColor(white ? WHITE_DISC : BLACK_DISC);
        g.fillOval(cx - rad, cy - rad, rad * 2, rad * 2);
        g.setColor(white ? WHITE_RING : BLACK_RING);
        g.setStroke(new BasicStroke(2.0f));
        g.drawOval(cx - rad, cy - rad, rad * 2, rad * 2);

        Font f = new Font("SansSerif", Font.BOLD, (int) (CELL * 0.62f));
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String s = Character.toString(GLYPH[type]);
        g.setColor(white ? WHITE_LETTER : BLACK_LETTER);
        g.drawString(s, cx - fm.stringWidth(s) / 2,
                cy - fm.getHeight() / 2 + fm.getAscent());
    }

    /** Pixel top-left of a model-space square, honouring flip. */
    private int[] squareXY(int sq) {
        int r = sq >>> 3;
        int c = sq & 7;
        int drow = flipped ? 7 - r : r;
        int dcol = flipped ? 7 - c : c;
        return new int[] { BOARD_X + dcol * CELL, BOARD_Y + drow * CELL };
    }

    private void fillSquareAt(Graphics2D g, int sq) {
        int[] xy = squareXY(sq);
        g.fillRect(xy[0], xy[1], CELL, CELL);
    }

    private int kingSquare(boolean white) {
        int king = white ? ChessModel.KING : -ChessModel.KING;
        for (int i = 0; i < 64; i++) {
            if (model.pieceAt(i) == king) {
                return i;
            }
        }
        return -1;
    }
}
