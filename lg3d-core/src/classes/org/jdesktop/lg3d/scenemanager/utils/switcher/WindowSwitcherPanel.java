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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Dimension;
import java.util.List;
import javax.swing.JPanel;

/**
 * The translucent card the native 3D desktop's window switcher paints while the
 * user cycles between open {@code Frame3D} windows: one row per window in
 * most-recently-used order, the highlighted row filled, exactly the visual
 * language of the 2D desktop's {@code WindowCyclerOverlay}.
 *
 * <p>It is pure Swing with no Java 3D, so the paint is headless-testable by
 * rendering into a {@link java.awt.image.BufferedImage}; {@link WindowSwitcher3D}
 * hosts it on a {@code SwingNode} on the front-most HUD layer. It paints nothing
 * while idle (no session), so an inactive switcher is invisible.</p>
 *
 * <p>Rows carry the window's title rather than a live 3D thumbnail: a
 * {@code Frame3D}'s {@code Thumbnail} is a single-parented {@code Component3D}
 * already owned by the taskbar, so it cannot be reparented onto the HUD without
 * stealing it from the bar. Titles (with the selected row highlighted) are the
 * same information the 2D overlay's icon+name rows convey.</p>
 */
public class WindowSwitcherPanel extends JPanel {

    /** Height of one window row. */
    static final int ROW_HEIGHT_PX = 34;
    /** Inner padding of the card. */
    static final int PAD_PX = 12;
    /** Corner radius of the card and of the selection fill. */
    static final int ARC_PX = 18;
    /** Upper bound on the card width, so long titles ellipsize. */
    static final int MAX_WIDTH_PX = 420;
    /**
     * Most rows the card shows. The hosted {@code SwingNode} texture is sized
     * once from the preferred size, so the panel keeps a fixed footprint of
     * {@link #MAX_ROWS} rows and a session with more windows lists the first
     * {@link #MAX_ROWS} (the most recently used).
     */
    static final int MAX_ROWS = 8;

    static final Color CARD = new Color(20, 24, 32);
    static final Color CARD_BORDER = new Color(120, 160, 220);
    static final Color SELECT_FILL = new Color(70, 120, 200);
    static final Color TEXT = Color.WHITE;
    static final Color TEXT_DIM = new Color(200, 208, 220);

    private List<String> names = List.of();
    private int selected = -1;

    public WindowSwitcherPanel() {
        setOpaque(false);
        // Fixed footprint so the hosted SwingNode texture never has to resize.
        setPreferredSize(new Dimension(MAX_WIDTH_PX, cardHeight(MAX_ROWS)));
    }

    /**
     * Shows a cycle session over {@code names} (most-recently-used first) with
     * {@code selected} highlighted, and repaints. An empty list hides the card.
     */
    public void setSession(List<String> names, int selected) {
        this.names = (names == null) ? List.of() : List.copyOf(names);
        // Keep the idle invariant: no rows means no highlighted row.
        this.selected = this.names.isEmpty() ? -1 : selected;
        repaint();
    }

    /** Hides the card (no session). */
    public void clear() {
        this.names = List.of();
        this.selected = -1;
        repaint();
    }

    /** Whether a session is being shown. */
    public boolean isActive() {
        return !names.isEmpty();
    }

    /** The window titles currently listed, most-recently-used first. */
    List<String> names() {
        return names;
    }

    /** The highlighted row, or -1 when idle. */
    int selected() {
        return selected;
    }

    /** The card height for {@code rowCount} rows; 0 when there are none. */
    static int cardHeight(int rowCount) {
        return (rowCount <= 0) ? 0 : PAD_PX * 2 + rowCount * ROW_HEIGHT_PX;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!isActive()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = resolveFont();
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();

            int rowCount = Math.min(names.size(), MAX_ROWS);
            int cardWidth = cardWidth(fm);
            int cardHeight = cardHeight(rowCount);
            int x = Math.max(0, (getWidth() - cardWidth) / 2);
            int y = 0;

            Composite saved = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 0.82f));
            g2.setColor(CARD);
            g2.fillRoundRect(x, y, cardWidth, cardHeight, ARC_PX, ARC_PX);
            g2.setComposite(saved);
            g2.setColor(CARD_BORDER);
            g2.drawRoundRect(x, y, cardWidth, cardHeight, ARC_PX, ARC_PX);

            for (int i = 0; i < rowCount; i++) {
                int rowY = y + PAD_PX + i * ROW_HEIGHT_PX;
                if (i == selected) {
                    g2.setColor(SELECT_FILL);
                    g2.fillRoundRect(x + PAD_PX / 2, rowY,
                            cardWidth - PAD_PX, ROW_HEIGHT_PX - 4, 10, 10);
                }
                paintRow(g2, names.get(i), x + PAD_PX, rowY, fm, i == selected);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintRow(Graphics2D g2, String name, int x, int y,
            FontMetrics fm, boolean isSelected) {
        g2.setColor(isSelected ? TEXT : TEXT_DIM);
        int textY = y + (ROW_HEIGHT_PX - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(ellipsize(name, fm, maxTextWidth()), x, textY);
    }

    private int cardWidth(FontMetrics fm) {
        int widest = 0;
        for (String name : names) {
            widest = Math.max(widest, fm.stringWidth(
                    ellipsize(name, fm, maxTextWidth())));
        }
        return Math.min(MAX_WIDTH_PX, PAD_PX * 2 + widest + PAD_PX);
    }

    private int maxTextWidth() {
        return MAX_WIDTH_PX - PAD_PX * 2;
    }

    /** Truncates {@code text} with an ellipsis so it fits {@code maxWidth}. */
    static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (text == null || fm.stringWidth(text) <= maxWidth) {
            return (text == null) ? "" : text;
        }
        String ellipsis = "\u2026";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (fm.stringWidth(sb.toString() + c) + ellipsisWidth > maxWidth) {
                break;
            }
            sb.append(c);
        }
        return sb + ellipsis;
    }

    /**
     * The component font, defensively: a peer-less panel in a headless test
     * returns null from {@link #getFont()}, which would NPE the font-metrics
     * calls in {@link #paintComponent}. Falls back to a plain dialog font.
     */
    private Font resolveFont() {
        Font font = getFont();
        return (font != null) ? font : new Font(Font.DIALOG, Font.PLAIN, 13);
    }
}
