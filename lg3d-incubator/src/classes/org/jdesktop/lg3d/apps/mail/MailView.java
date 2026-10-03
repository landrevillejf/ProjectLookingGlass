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
 * The native 3D mail client's main surface, rasterised entirely at runtime into a
 * single live texture (the {@code Histogram3D} / {@code AgendaGrid} recipe): a
 * message list on the left and a reading / quick-reply pane on the right, under a
 * slim account / folder header.
 *
 * <p>One fixed-size {@link ImageComponent2D} with {@code ALLOW_IMAGE_WRITE} is
 * attached to a {@link Texture2D} once, off-live; every later change only repaints
 * the {@link BufferedImage} and calls {@link ImageComponent2D#set} in place, so no
 * texture is ever re-attached to the live scene graph.</p>
 *
 * <p>It renders the same shared {@link MailMessage} model the 2D {@link MailPanel}
 * uses - now carrying real IMAP envelopes - with richer rows (an unread dot, a
 * flag, an attachment glyph, sender, subject and date) and a header showing the
 * account, folder and unread count. A left click in the list column maps the pick's
 * local intersection to a row and reports the clicked message through
 * {@link MailListener} so the host can open it.</p>
 */
public class MailView extends Component3D {

    /** Notified when a click selects a message in the list. */
    public interface MailListener {
        void messageSelected(MailMessage message);
    }

    // Power-of-two texture the mail surface is rasterized into.
    private static final int TW = 1024;
    private static final int TH = 512;

    // Image-space layout: header on top, list column on the left.
    private static final int TOP_PX = 40;
    private static final int LIST_W = 420;
    private static final int MAX_ROWS = 8;
    private static final int ROW_H = (TH - TOP_PX) / MAX_ROWS;
    private static final int READER_X = LIST_W + 2;

    private static final Color BG = new Color(0x0E, 0x14, 0x20, 0xF2);
    private static final Color TOP_BG = new Color(0x10, 0x1A, 0x2E, 0xFF);
    private static final Color LIST_BG = new Color(0x14, 0x1E, 0x30, 0xF6);
    private static final Color ROW_SEL = new Color(0x2A, 0x4A, 0x74, 0xF0);
    private static final Color ROW_ALT = new Color(255, 255, 255, 8);
    private static final Color READER_BG = new Color(0x10, 0x18, 0x26, 0xF6);
    private static final Color DIVIDER = new Color(255, 255, 255, 40);
    private static final Color TEXT = new Color(226, 236, 248, 255);
    private static final Color TEXT_DIM = new Color(168, 184, 204, 235);
    private static final Color TEXT_FAINT = new Color(140, 154, 172, 210);
    private static final Color UNREAD_DOT = new Color(120, 180, 255, 255);
    private static final Color FLAG = new Color(255, 200, 90, 255);
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
    private MailMessage draft;      // non-null while composing a quick reply
    private String accountLabel = "";
    private String folder = MailMessage.FOLDER_INBOX;
    private int unreadCount;
    private String statusLine = "";
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
        this.list = (messages == null) ? new ArrayList<MailMessage>() : messages;
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

    /** Sets the account name shown in the header. */
    public void setAccount(String accountLabel) {
        this.accountLabel = (accountLabel == null) ? "" : accountLabel;
        refresh();
    }

    /** A one-line status / error message drawn under the header. */
    public void setStatus(String statusLine) {
        this.statusLine = (statusLine == null) ? "" : statusLine;
        refresh();
    }

    /** Enters (draft != null) or leaves (draft == null) quick-reply mode. */
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
        String title = accountLabel.isEmpty() ? "Mail 3D" : "Mail 3D \u2014 " + accountLabel;
        g.drawString(clip(g, title, TITLE_FONT, TW - 260), 12,
                (TOP_PX - fm.getHeight()) / 2 + fm.getAscent());

        String folderLabel = folder + "  (" + unreadCount + " unread)";
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

            // Flag + attachment glyphs sit just right of the sender.
            g.setFont(FROM_FONT);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(sel ? Color.WHITE : TEXT);
            String from = clip(g, m.getFrom().display(),
                    m.isRead() ? FROM_READ_FONT : FROM_FONT, avail - 90);
            g.setFont(m.isRead() ? FROM_READ_FONT : FROM_FONT);
            g.drawString(from, tx, y + 22);

            int gx = LIST_W - 60;
            if (m.isFlagged()) {
                g.setColor(FLAG);
                g.setFont(HDR_FONT);
                g.drawString("\u2691", gx, y + 22);
                gx -= 20;
            }
            if (m.hasAttachments()) {
                g.setColor(TEXT_DIM);
                g.setFont(HDR_FONT);
                g.drawString("\uD83D\uDCCE", gx, y + 22);
            }

            g.setFont(DATE_FONT);
            FontMetrics dm = g.getFontMetrics();
            String when = DATE_FMT.format(new Date(m.getWhen()));
            g.setColor(TEXT_FAINT);
            g.drawString(when, LIST_W - 8 - dm.stringWidth(when), y + 20);

            g.setFont(SUBJ_FONT);
            g.setColor(sel ? TEXT : TEXT_DIM);
            g.drawString(clip(g, m.getSubject().isEmpty() ? "(no subject)"
                    : m.getSubject(), SUBJ_FONT, avail), tx, y + 42);

            g.setColor(new Color(255, 255, 255, 18));
            g.fillRect(0, y + ROW_H - 1, LIST_W, 1);
        }

        if (list.isEmpty()) {
            g.setFont(SUBJ_FONT);
            g.setColor(TEXT_FAINT);
            String msg = statusLine.isEmpty()
                    ? "No messages in this folder." : statusLine;
            g.drawString(clip(g, msg, SUBJ_FONT, LIST_W - 32), 26, TOP_PX + 30);
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
            String hint = statusLine.isEmpty()
                    ? "Select a message on the left to read it." : statusLine;
            g.drawString(clip(g, hint, SUBJ_FONT, w), x, y);
            return;
        }

        if (draft != null) {
            g.setFont(TITLE_FONT);
            g.setColor(ACCENT);
            g.drawString("Quick reply", x, y);
            y += 26;
        }

        g.setFont(HDR_FONT);
        FontMetrics hm = g.getFontMetrics();
        y = drawHeaderLine(g, "From: ", m.getFrom().format(), x, y, w);
        y = drawHeaderLine(g, "To: ", m.toLine(), x, y, w);
        if (!m.ccLine().isEmpty()) {
            y = drawHeaderLine(g, "Cc: ", m.ccLine(), x, y, w);
        }
        y = drawHeaderLine(g, "Subject: ", m.getSubject(), x, y, w);
        y = drawHeaderLine(g, "Date: ",
                DATE_FMT.format(new Date(m.getWhen())), x, y, w);
        y += 6;

        g.setColor(DIVIDER);
        g.fillRect(x, y, w, 1);
        y += 12;

        // Body, word-wrapped and clipped to the reader pane. Plain text only; an
        // HTML-only message falls back to its stripped text (no remote content).
        Shape oldClip = g.getClip();
        g.clipRect(x, y - 4, w, TH - y - 8);
        g.setFont(BODY_FONT);
        FontMetrics bm = g.getFontMetrics();
        g.setColor(TEXT);
        String body = m.getTextBody().isEmpty() && m.hasHtmlBody()
                ? MessageReader.stripTags(m.getHtmlBody()) : m.getTextBody();
        for (String line : wrap(g, body, w)) {
            g.drawString(line, x, y + bm.getAscent());
            y += bm.getHeight();
            if (y > TH - 10) {
                break;
            }
        }
        g.setClip(oldClip);
    }

    private int drawHeaderLine(Graphics2D g, String label, String value,
            int x, int y, int w) {
        g.setFont(HDR_FONT);
        FontMetrics hm = g.getFontMetrics();
        g.setColor(TEXT_DIM);
        g.drawString(label, x, y);
        g.setColor(TEXT);
        int lw = hm.stringWidth(label);
        g.drawString(clip(g, value, HDR_FONT, w - lw), x + lw, y);
        return y + hm.getHeight() + 2;
    }

    /** Truncates {@code s} with an ellipsis so it fits {@code maxWidth}. */
    private static String clip(Graphics2D g, String s, Font font, int maxWidth) {
        if (s == null) {
            return "";
        }
        Font old = g.getFont();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        String out = s;
        if (fm.stringWidth(out) > maxWidth) {
            while (out.length() > 1 && fm.stringWidth(out + "\u2026") > maxWidth) {
                out = out.substring(0, out.length() - 1);
            }
            out = out + "\u2026";
        }
        g.setFont(old);
        return out;
    }

    /** Word-wraps {@code text} (honouring explicit newlines) to {@code maxWidth}. */
    private static List<String> wrap(Graphics2D g, String text, int maxWidth) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
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
