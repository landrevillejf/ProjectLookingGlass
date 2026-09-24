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
package org.jdesktop.lg3d.widgets.builtin;

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
 * Shared base panel for the built-in widgets: paints a dark, rounded, glassy card
 * with a small title, then delegates the body to {@link #paintContent}.
 *
 * <p>The panel is opaque with a solid dark background so it renders reliably
 * through the SwingNode texture regardless of per-pixel alpha support; the
 * rounded inner card and border are drawn on top. Subclasses read volatile model
 * fields (updated on the widget's scheduler tick) and paint them here, then call
 * {@code repaint()} - so no Swing-thread handoff is required.</p>
 */
abstract class WidgetPanel extends JPanel {

    /** Card background. */
    protected static final Color CARD = new Color(26, 32, 44);
    /** Outer (corner) background. */
    protected static final Color OUTER = new Color(16, 20, 28);
    /** Border colour. */
    protected static final Color BORDER = new Color(96, 148, 214, 190);
    /** Title colour. */
    protected static final Color TITLE_COLOR = new Color(140, 185, 245);
    /** Primary text colour. */
    protected static final Color TEXT = new Color(226, 232, 240);
    /** Dimmed text colour. */
    protected static final Color TEXT_DIM = new Color(150, 165, 185);

    private final String title;

    protected WidgetPanel(String title, int width, int height) {
        this.title = title;
        setPreferredSize(new Dimension(width, height));
        setOpaque(true);
        setBackground(OUTER);
    }

    protected String title() {
        return title;
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
            g2.fillRoundRect(3, 3, w - 6, h - 6, 16, 16);
            g2.setColor(BORDER);
            g2.setStroke(new BasicStroke(1.3f));
            g2.drawRoundRect(3, 3, w - 7, h - 7, 16, 16);

            g2.setColor(TITLE_COLOR);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            FontMetrics fm = g2.getFontMetrics();
            int titleBaseline = 5 + fm.getAscent();
            g2.drawString(title, 12, titleBaseline);

            int contentTop = 5 + fm.getHeight() + 2;
            paintContent(g2, w, h, contentTop);
        } finally {
            g2.dispose();
        }
    }

    /**
     * Paints the widget body.
     *
     * @param g2   antialiased graphics with the card and title already drawn
     * @param w    panel width
     * @param h    panel height
     * @param top  y coordinate where the content area begins (below the title)
     */
    protected abstract void paintContent(Graphics2D g2, int w, int h, int top);
}
