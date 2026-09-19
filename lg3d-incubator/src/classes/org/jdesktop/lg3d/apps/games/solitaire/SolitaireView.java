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
package org.jdesktop.lg3d.apps.games.solitaire;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
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
 * The Klondike table, rasterised into a single live texture with the same
 * {@code ImageComponent2D.set} recipe as {@code AgendaGrid} / {@code MailView}.
 *
 * <p>The layout is the classic one: stock and waste at the top-left, four
 * foundations across the top-right, and seven fanned tableau columns below.
 * Suit pips are drawn as vector shapes (never a font glyph) so the cards render
 * identically in any JRE; only the ASCII rank labels use a font. A left click
 * maps the pick's local intersection to a {@code {zone, pile, position}} triple
 * reported through {@link PickListener}; the host owns selection and
 * move-making and pushes highlight state back via {@link #setSelection} and
 * {@link #setHint}.</p>
 */
public class SolitaireView extends Component3D {

    /** Notified with the model-space zone / pile / position of a left click. */
    public interface PickListener {
        void picked(int zone, int pile, int pos);
    }

    private static final int TW = 1024;
    private static final int TH = 1024;
    private static final int PAD = 20;
    private static final int STATUS_H = 46;
    private static final int COLS = 7;
    private static final int COL_W = (TW - 2 * PAD) / COLS;
    private static final int CARD_W = 118;
    private static final int CARD_H = 168;
    private static final int TOP_Y = STATUS_H + 8;
    private static final int TAB_Y = TOP_Y + CARD_H + 30;
    private static final int DOWN_DY = 24;
    private static final int UP_DY = 40;
    private static final int ARC = 14;

    private static final Color BG = new Color(0x0C, 0x1A, 0x14, 0xF4);   // felt green-black
    private static final Color TOP_BG = new Color(0x0A, 0x14, 0x10, 0xFF);
    private static final Color FACE = new Color(250, 250, 248, 255);
    private static final Color FACE_BORDER = new Color(120, 128, 140, 255);
    private static final Color RED = new Color(206, 44, 54, 255);
    private static final Color BLACK = new Color(28, 32, 42, 255);
    private static final Color BACK = new Color(38, 74, 140, 255);
    private static final Color BACK_LINE = new Color(120, 170, 235, 130);
    private static final Color BACK_BORDER = new Color(210, 226, 246, 255);
    private static final Color SLOT = new Color(255, 255, 255, 22);
    private static final Color SLOT_BORDER = new Color(255, 255, 255, 70);
    private static final Color SLOT_MARK = new Color(255, 255, 255, 46);
    private static final Color SELECT = new Color(255, 214, 90, 255);
    private static final Color HINT_SRC = new Color(120, 220, 255, 255);
    private static final Color HINT_DST = new Color(120, 255, 160, 255);
    private static final Color TEXT = new Color(228, 240, 234, 255);
    private static final Color TEXT_DIM = new Color(158, 186, 172, 235);
    private static final Color ACCENT = new Color(110, 220, 170, 255);
    private static final Color WIN = new Color(130, 255, 170, 255);
    private static final Color MSG = new Color(255, 206, 110, 255);

    private static final Font STATUS_FONT = new Font("SansSerif", Font.BOLD, 24);
    private static final Font SUB_FONT = new Font("SansSerif", Font.PLAIN, 18);
    private static final Font RANK_FONT = new Font("SansSerif", Font.BOLD, 26);
    private static final Font CORNER_FONT = new Font("SansSerif", Font.BOLD, 20);

    private final float width;
    private final float height;
    private final BufferedImage canvas;
    private final ImageComponent2D imageComponent;

    private SolitaireModel model = new SolitaireModel();
    private int selZone = SolitaireModel.ZONE_NONE;
    private int selPile = -1;
    private int selPos = -1;
    private SolitaireModel.Hint hint;
    private String message;
    private PickListener listener;

    public SolitaireView(float width, float height) {
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

    public void setPickListener(PickListener listener) {
        this.listener = listener;
    }

    public void setModel(SolitaireModel model) {
        this.model = model;
        clearSelection();
        hint = null;
        message = null;
        refresh();
    }

    public void setSelection(int zone, int pile, int pos) {
        this.selZone = zone;
        this.selPile = pile;
        this.selPos = pos;
        refresh();
    }

    public void clearSelection() {
        selZone = SolitaireModel.ZONE_NONE;
        selPile = -1;
        selPos = -1;
        refresh();
    }

    public void setHint(SolitaireModel.Hint hint) {
        this.hint = hint;
        refresh();
    }

    public void setMessage(String message) {
        this.message = message;
        refresh();
    }

    /**
     * Sets selection, hint and message together and repaints once, so a single
     * interaction costs one texture upload rather than several.
     */
    public void setState(int selZone, int selPile, int selPos,
            SolitaireModel.Hint hint, String message) {
        this.selZone = selZone;
        this.selPile = selPile;
        this.selPos = selPos;
        this.hint = hint;
        this.message = message;
        refresh();
    }

    public final void refresh() {
        redraw();
        imageComponent.set(canvas);
    }

    // ------------------------------------------------------------------
    // Click mapping
    // ------------------------------------------------------------------

    private void handleClick(float x, float y) {
        int[] pick = pick(x, y);
        if (pick != null && listener != null) {
            listener.picked(pick[0], pick[1], pick[2]);
        }
    }

    /** Maps a local intersection to {@code {zone, pile, pos}} or null. */
    private int[] pick(float x, float y) {
        float nx = x / width + 0.5f;
        float ny = 0.5f - y / height;
        float px = nx * TW;
        float py = ny * TH;

        int col = columnAt(px);
        if (col >= 0 && py >= TOP_Y && py < TOP_Y + CARD_H) {
            switch (col) {
                case 0: return new int[] { SolitaireModel.ZONE_STOCK, -1, -1 };
                case 1: return new int[] { SolitaireModel.ZONE_WASTE, -1, -1 };
                case 3: case 4: case 5: case 6:
                    return new int[] {
                        SolitaireModel.ZONE_FOUNDATION, col - 3, -1 };
                default: return null;
            }
        }
        if (col >= 0 && py >= TAB_Y && py < TH - PAD + CARD_H) {
            return pickTableau(col, px, py);
        }
        return null;
    }

    private int[] pickTableau(int col, float px, float py) {
        int x = cardX(col);
        if (px < x || px >= x + CARD_W) {
            return null;
        }
        int n = model.tableauSize(col);
        if (n == 0) {
            if (py >= TAB_Y && py < TAB_Y + CARD_H) {
                return new int[] { SolitaireModel.ZONE_TABLEAU, col, 0 };
            }
            return null;
        }
        int[] ys = pileYs(col, n);
        for (int pos = n - 1; pos >= 0; pos--) {
            if (py >= ys[pos] && py < ys[pos] + CARD_H) {
                return new int[] { SolitaireModel.ZONE_TABLEAU, col, pos };
            }
        }
        return null;
    }

    private int columnAt(float px) {
        for (int col = 0; col < COLS; col++) {
            int x = cardX(col);
            if (px >= x && px < x + CARD_W) {
                return col;
            }
        }
        return -1;
    }

    private static int cardX(int col) {
        return PAD + col * COL_W + (COL_W - CARD_W) / 2;
    }

    /** Y position of each card in a pile, compressed to fit the table. */
    private int[] pileYs(int pile, int n) {
        int[] ys = new int[n];
        int y = TAB_Y;
        for (int pos = 0; pos < n; pos++) {
            ys[pos] = y;
            if (pos < n - 1) {
                y += model.isFaceUp(pile, pos) ? UP_DY : DOWN_DY;
            }
        }
        if (n > 0) {
            int bottom = ys[n - 1] + CARD_H;
            int limit = TH - PAD;
            int span = ys[n - 1] - TAB_Y;
            if (bottom > limit && span > 0) {
                float k = (float) (limit - CARD_H - TAB_Y) / span;
                for (int pos = 0; pos < n; pos++) {
                    ys[pos] = TAB_Y + Math.round((ys[pos] - TAB_Y) * k);
                }
            }
        }
        return ys;
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

            drawTableau(g);
            drawTopRow(g);
            drawOverlays(g);
            drawStatus(g);
        } finally {
            g.dispose();
        }
    }

    private void drawTopRow(Graphics2D g) {
        // Stock.
        if (model.stockSize() > 0) {
            drawCardBack(g, cardX(0), TOP_Y);
            drawCountBadge(g, cardX(0), TOP_Y, model.stockSize());
        } else {
            drawEmptySlot(g, cardX(0), TOP_Y, -1);
            drawRecycleMark(g, cardX(0), TOP_Y);
        }
        // Waste.
        int wt = model.wasteTop();
        if (wt >= 0) {
            drawCardFace(g, cardX(1), TOP_Y, wt);
        } else {
            drawEmptySlot(g, cardX(1), TOP_Y, -1);
        }
        // Foundations (cols 3..6).
        for (int f = 0; f < 4; f++) {
            int x = cardX(3 + f);
            int top = model.foundationTop(f);
            if (top >= 0) {
                drawCardFace(g, x, TOP_Y, top);
            } else {
                drawEmptySlot(g, x, TOP_Y, f);
            }
        }
    }

    private void drawTableau(Graphics2D g) {
        for (int col = 0; col < COLS; col++) {
            int x = cardX(col);
            int n = model.tableauSize(col);
            if (n == 0) {
                drawEmptySlot(g, x, TAB_Y, -1);
                continue;
            }
            int[] ys = pileYs(col, n);
            for (int pos = 0; pos < n; pos++) {
                int card = model.tableauCard(col, pos);
                if (model.isFaceUp(col, pos)) {
                    drawCardFace(g, x, ys[pos], card);
                } else {
                    drawCardBack(g, x, ys[pos]);
                }
            }
        }
    }

    private void drawOverlays(Graphics2D g) {
        // Hint: source (blue) then destination (green).
        if (hint != null) {
            outlineZone(g, hint.srcZone, hint.srcPile, hint.srcPos, HINT_SRC,
                    false);
            outlineZone(g, hint.destZone, hint.destIndex, -1, HINT_DST, true);
        }
        // Selection: whole run if a tableau run is selected.
        if (selZone == SolitaireModel.ZONE_TABLEAU && selPile >= 0
                && selPos >= 0) {
            int n = model.tableauSize(selPile);
            int[] ys = pileYs(selPile, n);
            int x = cardX(selPile);
            for (int pos = selPos; pos < n; pos++) {
                highlight(g, x, ys[pos], SELECT);
            }
        } else if (selZone == SolitaireModel.ZONE_WASTE) {
            highlight(g, cardX(1), TOP_Y, SELECT);
        }
    }

    private void outlineZone(Graphics2D g, int zone, int pile, int pos,
            Color color, boolean dest) {
        switch (zone) {
            case SolitaireModel.ZONE_WASTE:
                highlight(g, cardX(1), TOP_Y, color);
                break;
            case SolitaireModel.ZONE_FOUNDATION:
                highlight(g, cardX(3 + pile), TOP_Y, color);
                break;
            case SolitaireModel.ZONE_TABLEAU: {
                int n = model.tableauSize(pile);
                if (dest && n == 0) {
                    highlight(g, cardX(pile), TAB_Y, color);
                } else if (pos >= 0 && pos < n) {
                    int[] ys = pileYs(pile, n);
                    int end = dest ? pos : n - 1;
                    for (int p = pos; p <= end; p++) {
                        highlight(g, cardX(pile), ys[p], color);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void drawStatus(Graphics2D g) {
        g.setColor(TOP_BG);
        g.fillRect(0, 0, TW, STATUS_H);
        g.setColor(ACCENT);
        g.fillRect(0, STATUS_H - 2, TW, 2);

        g.setFont(STATUS_FONT);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(TEXT);
        g.drawString("Solitaire", 16, (STATUS_H - fm.getHeight()) / 2 + fm.getAscent());

        String right;
        Color rc;
        if (model.isWon()) {
            right = "You win!  " + model.getMoves() + " moves";
            rc = WIN;
        } else {
            right = "Moves: " + model.getMoves();
            rc = TEXT_DIM;
        }
        g.setFont(SUB_FONT);
        FontMetrics sm = g.getFontMetrics();
        g.setColor(rc);
        g.drawString(right, TW - 16 - sm.stringWidth(right),
                (STATUS_H - sm.getHeight()) / 2 + sm.getAscent());

        if (message != null && !message.isEmpty()) {
            g.setColor(MSG);
            g.drawString(message, TW / 2 - sm.stringWidth(message) / 2,
                    (STATUS_H - sm.getHeight()) / 2 + sm.getAscent());
        }
    }

    // ------------------------------------------------------------------
    // Card painting
    // ------------------------------------------------------------------

    private void drawCardFace(Graphics2D g, int x, int y, int card) {
        RoundRectangle2D rr =
                new RoundRectangle2D.Float(x, y, CARD_W, CARD_H, ARC, ARC);
        g.setColor(FACE);
        g.fill(rr);
        g.setColor(FACE_BORDER);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(rr);

        int suit = SolitaireModel.suit(card);
        Color col = SolitaireModel.isRed(card) ? RED : BLACK;
        String rank = SolitaireModel.rankLabel(card);

        // Top-left rank + pip.
        g.setFont(CORNER_FONT);
        FontMetrics cm = g.getFontMetrics();
        g.setColor(col);
        g.drawString(rank, x + 8, y + 6 + cm.getAscent());
        drawSuit(g, suit, x + 8 + cm.stringWidth(rank) / 2.0f,
                y + 10 + cm.getAscent() + 12, 20, col);

        // Bottom-right rank (right-aligned).
        g.setFont(CORNER_FONT);
        g.setColor(col);
        g.drawString(rank, x + CARD_W - 8 - cm.stringWidth(rank),
                y + CARD_H - 10);

        // Centre pip.
        drawSuit(g, suit, x + CARD_W / 2.0f, y + CARD_H / 2.0f + 6,
                CARD_W * 0.62f, col);
    }

    private void drawCardBack(Graphics2D g, int x, int y) {
        RoundRectangle2D rr =
                new RoundRectangle2D.Float(x, y, CARD_W, CARD_H, ARC, ARC);
        g.setColor(BACK);
        g.fill(rr);
        Shape old = g.getClip();
        g.setClip(rr);
        g.setColor(BACK_LINE);
        g.setStroke(new BasicStroke(1.4f));
        for (int i = -CARD_H; i < CARD_W; i += 14) {
            g.drawLine(x + i, y, x + i + CARD_H, y + CARD_H);
            g.drawLine(x + i, y + CARD_H, x + i + CARD_H, y);
        }
        g.setClip(old);
        g.setColor(BACK_BORDER);
        g.setStroke(new BasicStroke(2.2f));
        g.drawRoundRect(x + 5, y + 5, CARD_W - 10, CARD_H - 10, ARC - 4, ARC - 4);
        g.draw(rr);
    }

    private void drawEmptySlot(Graphics2D g, int x, int y, int foundation) {
        RoundRectangle2D rr =
                new RoundRectangle2D.Float(x, y, CARD_W, CARD_H, ARC, ARC);
        g.setColor(SLOT);
        g.fill(rr);
        g.setColor(SLOT_BORDER);
        g.setStroke(new BasicStroke(2.0f));
        g.draw(rr);
        if (foundation >= 0) {
            drawSuit(g, foundation, x + CARD_W / 2.0f, y + CARD_H / 2.0f,
                    CARD_W * 0.5f, SLOT_MARK);
        }
    }

    private void drawRecycleMark(Graphics2D g, int x, int y) {
        g.setColor(SLOT_MARK);
        g.setStroke(new BasicStroke(3.0f));
        int cx = x + CARD_W / 2;
        int cy = y + CARD_H / 2;
        int r = CARD_W / 4;
        g.drawArc(cx - r, cy - r, 2 * r, 2 * r, 40, 250);
        // arrow head
        int ax = cx + (int) (r * Math.cos(Math.toRadians(40)));
        int ay = cy - (int) (r * Math.sin(Math.toRadians(40)));
        g.fillPolygon(new int[] { ax, ax + 9, ax - 3 },
                new int[] { ay, ay + 8, ay + 12 }, 3);
    }

    private void drawCountBadge(Graphics2D g, int x, int y, int count) {
        String s = Integer.toString(count);
        g.setFont(SUB_FONT);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(s) + 12;
        int h = fm.getHeight() + 4;
        int bx = x + CARD_W - w - 4;
        int by = y + CARD_H - h - 4;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(bx, by, w, h, 10, 10);
        g.setColor(TEXT);
        g.drawString(s, bx + 6, by + fm.getAscent() + 2);
    }

    private void highlight(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(4.0f));
        g.drawRoundRect(x - 3, y - 3, CARD_W + 6, CARD_H + 6, ARC + 2, ARC + 2);
    }

    /** Draws a suit pip as a vector shape centred at (cx, cy), size s. */
    private void drawSuit(Graphics2D g, int suit, float cx, float cy,
            float s, Color color) {
        g.setColor(color);
        switch (suit) {
            case SolitaireModel.DIAMONDS: {
                Path2D p = new Path2D.Float();
                p.moveTo(cx, cy - s * 0.62f);
                p.lineTo(cx + s * 0.44f, cy);
                p.lineTo(cx, cy + s * 0.62f);
                p.lineTo(cx - s * 0.44f, cy);
                p.closePath();
                g.fill(p);
                break;
            }
            case SolitaireModel.HEARTS: {
                Path2D p = new Path2D.Float();
                float w = s * 0.5f, h = s * 0.55f;
                p.moveTo(cx, cy + h);
                p.curveTo(cx - w * 1.9f, cy - h * 0.15f,
                        cx - w * 0.7f, cy - h * 1.6f, cx, cy - h * 0.5f);
                p.curveTo(cx + w * 0.7f, cy - h * 1.6f,
                        cx + w * 1.9f, cy - h * 0.15f, cx, cy + h);
                p.closePath();
                g.fill(p);
                break;
            }
            case SolitaireModel.SPADES: {
                Path2D p = new Path2D.Float();
                float w = s * 0.5f, h = s * 0.55f;
                p.moveTo(cx, cy - h);
                p.curveTo(cx + w * 1.9f, cy + h * 0.15f,
                        cx + w * 0.7f, cy + h * 1.5f, cx, cy + h * 0.45f);
                p.curveTo(cx - w * 0.7f, cy + h * 1.5f,
                        cx - w * 1.9f, cy + h * 0.15f, cx, cy - h);
                p.closePath();
                g.fill(p);
                Path2D st = new Path2D.Float();
                st.moveTo(cx - s * 0.09f, cy + h * 0.95f);
                st.lineTo(cx + s * 0.09f, cy + h * 0.95f);
                st.lineTo(cx + s * 0.05f, cy + h * 0.2f);
                st.lineTo(cx - s * 0.05f, cy + h * 0.2f);
                st.closePath();
                g.fill(st);
                break;
            }
            case SolitaireModel.CLUBS:
            default: {
                float r = s * 0.21f;
                g.fill(new Ellipse2D.Float(cx - r, cy - s * 0.5f, 2 * r, 2 * r));
                g.fill(new Ellipse2D.Float(cx - s * 0.42f, cy - s * 0.1f,
                        2 * r, 2 * r));
                g.fill(new Ellipse2D.Float(cx + s * 0.42f - 2 * r, cy - s * 0.1f,
                        2 * r, 2 * r));
                Path2D st = new Path2D.Float();
                st.moveTo(cx - s * 0.1f, cy + s * 0.55f);
                st.lineTo(cx + s * 0.1f, cy + s * 0.55f);
                st.lineTo(cx + s * 0.05f, cy + s * 0.05f);
                st.lineTo(cx - s * 0.05f, cy + s * 0.05f);
                st.closePath();
                g.fill(st);
                break;
            }
        }
    }
}
