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
package org.jdesktop.lg3d.widgets.hud;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;
import org.jdesktop.lg3d.displayserver.desktop2d.Notification;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.SwingNode;

/**
 * One notification toast card in the 3D desktop: a fixed-size, opaque Swing card
 * (accent stripe by {@link Notification.Kind}, bold title, dimmed body) hosted on
 * a {@link SwingNode}. The size is fixed so the pool never has to resize a live
 * texture - {@link ToastOverlay3D} shows/hides and repositions cards instead.
 *
 * <p>The card is opaque (no per-pixel alpha) so it renders reliably through the
 * SwingNode texture, matching the built-in widget panels. Mouse events are
 * disabled so a transient toast never blocks clicks to the desktop behind it.</p>
 */
public class ToastCard3D extends Component3D {

    /** Card pixel size; fixed for the life of the card. */
    static final int CARD_WIDTH_PX = 300;
    static final int CARD_HEIGHT_PX = 66;

    private final CardPanel panel;
    private final SwingNode swingNode;

    public ToastCard3D() {
        setName("ToastCard3D");
        panel = new CardPanel(CARD_WIDTH_PX, CARD_HEIGHT_PX);
        swingNode = new SwingNode();
        swingNode.setJPanel(panel);
        swingNode.setTransparency(0.05f);
        addChild(swingNode);
        // A transient toast must not swallow clicks meant for the desktop.
        setMouseEventEnabled(false);
        setVisible(false);
    }

    /** Sets the notification this card shows and repaints it (EDT-safe). */
    public void setNotification(Notification n) {
        panel.setNotification(n);
        panel.repaint();
    }

    /** The card's width in world units (0 until the SwingNode has captured). */
    public float cardWidth() {
        return swingNode.getLocalWidth();
    }

    /** The card's height in world units (0 until the SwingNode has captured). */
    public float cardHeight() {
        return swingNode.getLocalHeight();
    }

    /** Releases the SwingNode's offscreen resources. */
    public void dispose() {
        swingNode.dispose();
    }

    /** The accent colour for a notification kind. */
    static Color accentFor(Notification.Kind kind) {
        if (kind == null) {
            return CardPanel.INFO;
        }
        switch (kind) {
            case ERROR:   return CardPanel.ERROR;
            case WARNING: return CardPanel.WARNING;
            case INFO:
            default:      return CardPanel.INFO;
        }
    }

    /** Truncates {@code text} with an ellipsis so it fits {@code maxWidthPx}. */
    static String clip(String text, FontMetrics fm, int maxWidthPx) {
        if (text == null || text.isEmpty() || fm.stringWidth(text) <= maxWidthPx) {
            return text;
        }
        String ellipsis = "\u2026";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidthPx) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    /** The opaque Swing card painted into the SwingNode texture. */
    static final class CardPanel extends JPanel {
        static final Color CARD = new Color(26, 32, 44);
        static final Color BORDER = new Color(96, 148, 214, 190);
        static final Color TEXT = new Color(226, 232, 240);
        static final Color TEXT_DIM = new Color(150, 165, 185);
        static final Color INFO = new Color(96, 148, 214);
        static final Color WARNING = new Color(224, 170, 80);
        static final Color ERROR = new Color(214, 96, 96);

        private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        private static final Font MSG_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

        private volatile Notification notification;

        CardPanel(int w, int h) {
            Dimension d = new Dimension(w, h);
            setPreferredSize(d);
            setMinimumSize(d);
            setSize(d);
            setOpaque(true);
            setBackground(CARD);
        }

        void setNotification(Notification n) {
            this.notification = n;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();

                g2.setColor(CARD);
                g2.fillRoundRect(0, 0, w, h, 14, 14);

                Notification n = notification;
                g2.setColor(accentFor(n == null ? null : n.kind()));
                g2.fillRoundRect(0, 0, 7, h, 14, 14);

                g2.setColor(BORDER);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

                int tx = 18;
                int maxTextW = w - tx - 12;

                g2.setFont(TITLE_FONT);
                g2.setColor(TEXT);
                FontMetrics tfm = g2.getFontMetrics();
                int ty = tfm.getAscent() + 10;
                g2.drawString(clip(n == null ? "" : n.title(), tfm, maxTextW), tx, ty);

                String msg = (n == null) ? null : n.message();
                if (msg != null) {
                    g2.setFont(MSG_FONT);
                    g2.setColor(TEXT_DIM);
                    FontMetrics mfm = g2.getFontMetrics();
                    g2.drawString(clip(msg, mfm, maxTextW), tx, ty + mfm.getAscent() + 5);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
