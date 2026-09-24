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
package org.jdesktop.lg3d.apps.mail;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
 * The native 3D mail client's main surface, rasterised entirely at runtime into
 * a single live texture (the {@code Histogram3D} / {@code AgendaGrid} recipe):
 * a message list on the left and a reading / compose pane on the right, under a
 * slim folder header.
 *
 * <p>One fixed-size {@link ImageComponent2D} with {@code ALLOW_IMAGE_WRITE} is
 * attached to a {@link Texture2D} once, off-live; every later change only
 * repaints the {@code BufferedImage} and calls {@link ImageComponent2D#set} in
 * place, so no texture is ever re-attached to the live scene graph.</p>
 *
 * <p>A left click in the list column maps the pick's local intersection to a
 * row and reports the clicked {@link MailMessage} through {@link MailListener}
 * so the host can open it (and mark it read).</p>
 */
public class MailView extends Component3D {

    /** Notified when a click selects a message in the list. */
    public interface MailListener {
        void messageSelected(MailMessage message);
    }

    // Power-of-two texture the mail surface is rasterized into.
    private static final int TW = 1024;
    private static final int TH = 512;

    // Image-space layout: folder header on top, list column on the left.
    private static final int TOP_PX = 40;
    private static final int LIST_W = 400;
    private static final int MAX_ROWS = 8;
    private static final int ROW_H = (TH - TOP_PX) / MAX_ROWS;
    private static final int READER_X = LIST_W + 2;

    private static final Color BG = new Color(0x0E, 0x14, 0x20, 0xF2);
    private static final Color TOP_BG = new Color(0x10, 0x1A, 0x2E, 0xFF);
    private static final Color LIST_BG = new Color(0x14, 0x1E, 0x30, 0xF6);
    private static final Color ROW_SEL = new Color(0x2A, 0x4A, 0x74, 0xF0);
    private static final Color ROW_ALT = new Color(255, 255, 255, 8);
    private static final Color READER_BG = new Color(0x10, 0x18, 0x26, 0xF6);
    private static final Color AXIS_LINE = new Color(255, 255, 255, 80);
    private static final Color DIVIDER = new Color(255, 255, 255, 40);
    private static final Color TEXT = new Color(226, 236, 248, 255);
    private static final Color TEXT_DIM = new Color(168, 184, 204, 235);
    private static final Color TEXT_FAINT = new Color(140, 154, 172, 210);
    private static final Color UNREAD_DOT = new Color(120, 180, 255, 255);
    private static final Color ACCENT = new Color(120, 180, 255, 255);

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 18);
    private static final Font FROM_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Font FROM_READ_FONT = new Font("SansSerif", Font.PLAIN, 16);
    private static final Font SUBJ_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font DATE_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font HDR_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font BODY_FONT = new Font("SansSerif", Font.PLAIN, 15);

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("MMM d, HH:mm");

    private final float width;
    private final float height;
    private final BufferedImage canvas;
    private final ImageComponent2D imageComponent;

    private List<MailMessage> list = new ArrayList<MailMessage>();
    private MailMessage selected;
    private MailMessage draft;      // non-null while composing
    private String folder = MailMessage.FOLDER_INBOX;
    private int unreadCount;
    private MailListener listener;

    public MailView(float width, float height) {
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
        // PICK_GEOMETRY needs this to report an intersection point for clicks.
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

    public void setMailListener(MailListener listener) {
        this.listener = listener;
    }

    /** Sets the messages shown in the list column (already folder-filtered). */
    public void setList(List<MailMessage> messages, int unreadCount) {
        this.list = messages;
        this.unreadCount = unreadCount;
        refresh();
    }

    public void setSelected(MailMessage selected) {
        this.selected = selected;
        refresh();
    }

    public void setFolder(String folder) {
        this.folder = folder;
        refresh();
    }

    /** Enters (draft != null) or leaves (draft == null) compose mode. */
    public void setDraft(MailMessage draft) {
        this.draft = draft;
        refresh();
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
        MailMessage m = pickRow(x, y);
        if (m != null && listener != null) {
            listener.messageSelected(m);
        }
    }

    /** Maps a local intersection point to the message row under it, or null. */
    private MailMessage pickRow(float x, float y) {
        float nx = x / width + 0.5f;   // 0 at left edge, 1 at right edge
        float ny = 0.5f - y / height;  // 0 at top edge, 1 at bottom edge
        float px = nx * TW;
        float py = ny * TH;
        if (px >= LIST_W || py < TOP_PX) {
            return null;
        }
        int row = (int) ((py - TOP_PX) / ROW_H);
        if (row < 0 || row >= list.size()) {
            return null;
        }
        return list.get(row);
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

            drawTopBar(g);
            drawList(g);
            drawReader(g);

            g.setColor(DIVIDER);
            g.fillRect(LIST_W, TOP_PX, 2, TH - TOP_PX);
        } finally {
            g.dispose();
        }
    }

    private void drawTopBar(Graphics2D g) {
        g.setColor(TOP_BG);
        g.fillRect(0, 0, TW, TOP_PX);
        g.setColor(ACCENT);
        g.fillRect(0, TOP_PX - 2, TW, 2);

        g.setFont(TITLE_FONT);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(TEXT);
        g.drawString("Mail 3D", 12, (TOP_PX - fm.getHeight()) / 2 + fm.getAscent());

        String folderLabel = (MailMessage.FOLDER_SENT.equals(folder)
                ? "Sent" : "Inbox")
                + (MailMessage.FOLDER_INBOX.equals(folder)
                        ? "  (" + unreadCount + " unread)" : "");
        g.setFont(HDR_FONT);
        FontMetrics hm = g.getFontMetrics();
        g.setColor(TEXT_DIM);
        g.drawString(folderLabel, TW - 12 - hm.stringWidth(folderLabel),
                (TOP_PX - hm.getHeight()) / 2 + hm.getAscent());
    }

    private void drawList(Graphics2D g) {
        g.setColor(LIST_BG);
        g.fillRect(0, TOP_PX, LIST_W, TH - TOP_PX);

        for (int i = 0; i < list.size() && i < MAX_ROWS; i++) {
            MailMessage m = list.get(i);
            int y = TOP_PX + i * ROW_H;
            boolean sel = (m == selected);

            if (sel) {
                g.setColor(ROW_SEL);
                g.fillRect(0, y, LIST_W, ROW_H);
            } else if (i % 2 == 1) {
                g.setColor(ROW_ALT);
                g.fillRect(0, y, LIST_W, ROW_H);
            }
            if (!m.isRead()) {
                g.setColor(UNREAD_DOT);
                g.fillOval(10, y + 12, 9, 9);
            }

            int tx = 26;
            int avail = LIST_W - tx - 8;

            g.setFont(m.isRead() ? FROM_READ_FONT : FROM_FONT);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(sel ? Color.WHITE : TEXT);
            g.drawString(clip(g, m.getFrom(), avail - 70), tx, y + 22);

            g.setFont(DATE_FONT);
            FontMetrics dm = g.getFontMetrics();
            String when = DATE_FMT.format(new Date(m.getWhen()));
            g.setColor(TEXT_FAINT);
            g.drawString(when, LIST_W - 8 - dm.stringWidth(when), y + 20);

            g.setFont(SUBJ_FONT);
            FontMetrics sm = g.getFontMetrics();
            g.setColor(sel ? TEXT : TEXT_DIM);
            g.drawString(clip(g, m.getSubject(), avail), tx, y + 42);

            g.setColor(new Color(255, 255, 255, 18));
            g.fillRect(0, y + ROW_H - 1, LIST_W, 1);
        }

        if (list.isEmpty()) {
            g.setFont(SUBJ_FONT);
            g.setColor(TEXT_FAINT);
            g.drawString("No messages in this folder.", 26, TOP_PX + 30);
        }
    }

    private void drawReader(Graphics2D g) {
        g.setColor(READER_BG);
        g.fillRect(READER_X, TOP_PX, TW - READER_X, TH - TOP_PX);

        MailMessage m = (draft != null) ? draft : selected;
        int x = READER_X + 16;
        int w = TW - READER_X - 32;
        int y = TOP_PX + 26;

        if (m == null) {
            g.setFont(SUBJ_FONT);
            g.setColor(TEXT_FAINT);
            g.drawString("Select a message on the left to read it.", x, y);
            return;
        }

        if (draft != null) {
            g.setFont(TITLE_FONT);
            g.setColor(ACCENT);
            g.drawString("New message", x, y);
            y += 26;
        }

        g.setFont(HDR_FONT);
        FontMetrics hm = g.getFontMetrics();
        g.setColor(TEXT_DIM);
        g.drawString("From: ", x, y);
        g.setColor(TEXT);
        g.drawString(m.getFrom() + "  <" + m.getFromEmail() + ">",
                x + hm.stringWidth("From: "), y);
        y += hm.getHeight() + 2;
        g.setColor(TEXT_DIM);
        g.drawString("To: ", x, y);
        g.setColor(TEXT);
        g.drawString(m.getTo() + "  <" + m.getToEmail() + ">",
                x + hm.stringWidth("To: "), y);
        y += hm.getHeight() + 2;
        g.setColor(TEXT_DIM);
        g.drawString("Subject: ", x, y);
        g.setColor(TEXT);
        g.drawString(m.getSubject(), x + hm.stringWidth("Subject: "), y);
        y += hm.getHeight() + 2;
        g.setColor(TEXT_DIM);
        g.drawString("Date: ", x, y);
        g.setColor(TEXT);
        g.drawString(DATE_FMT.format(new Date(m.getWhen())),
                x + hm.stringWidth("Date: "), y);
        y += hm.getHeight() + 6;

        g.setColor(DIVIDER);
        g.fillRect(x, y, w, 1);
        y += 12;

        // Body, word-wrapped and clipped to the reader pane.
        Shape oldClip = g.getClip();
        g.clipRect(x, y - 4, w, TH - y - 8);
        g.setFont(BODY_FONT);
        FontMetrics bm = g.getFontMetrics();
        g.setColor(TEXT);
        for (String line : wrap(g, m.getBody(), w)) {
            g.drawString(line, x, y + bm.getAscent());
            y += bm.getHeight();
            if (y > TH - 10) {
                break;
            }
        }
        g.setClip(oldClip);
    }

    /** Truncates {@code s} with an ellipsis so it fits {@code maxWidth}. */
    private static String clip(Graphics2D g, String s, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxWidth) {
            return s;
        }
        String ell = s;
        while (ell.length() > 1 && fm.stringWidth(ell + "\u2026") > maxWidth) {
            ell = ell.substring(0, ell.length() - 1);
        }
        return ell + "\u2026";
    }

    /** Word-wraps {@code text} (honouring explicit newlines) to {@code maxWidth}. */
    private static List<String> wrap(Graphics2D g, String text, int maxWidth) {
        List<String> out = new ArrayList<String>();
        FontMetrics fm = g.getFontMetrics();
        String[] paras = text.split("\n", -1);
        for (int p = 0; p < paras.length; p++) {
            String para = paras[p];
            if (para.length() == 0) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            String[] words = para.split(" ");
            for (int i = 0; i < words.length; i++) {
                String trial = line.length() == 0
                        ? words[i] : line + " " + words[i];
                if (line.length() == 0 || fm.stringWidth(trial) <= maxWidth) {
                    line = new StringBuilder(trial);
                } else {
                    out.add(line.toString());
                    line = new StringBuilder(words[i]);
                }
            }
            out.add(line.toString());
        }
        return out;
    }
}
