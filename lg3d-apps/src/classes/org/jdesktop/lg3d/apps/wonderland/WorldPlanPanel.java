/*
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * The browser's vignette: the {@link WorldPlan} of whichever world the picker
 * holds, painted as a top-down floor plan. This is what answers "what does the
 * clinic look like?" from the 2D desktop, where no 3D canvas exists &mdash;
 * every room as a labelled box at its real relative spot, the whole world
 * fitted to the panel.
 *
 * <p>Like every other face in this panel it is LAF-neutral: boxes are outlined
 * in the component foreground and labeled in it too, with a faint translucent
 * <em>fill derived from the foreground</em> so overlapping rooms stay readable
 * &mdash; no palette color is invented here, so the panel paints the same in
 * the 3D desktop's SwingNode offscreen buffer as in the 2D one. The plan
 * arrives fully computed; this class only maps normalized units to pixels and
 * never re-derives geometry.</p>
 */
final class WorldPlanPanel extends JComponent {

    /** Pixels kept clear around the drawing, so no box touches the border. */
    private static final int MARGIN_PX = 6;

    private WorldPlan plan = WorldPlan.EMPTY;

    WorldPlanPanel() {
        setPreferredSize(new Dimension(260, 150));
        setToolTipText("Top-down plan of the selected world, from its own "
                + "cell placement data.");
    }

    /** Show another world's plan; repaints immediately. Call on the EDT. */
    void setPlan(WorldPlan plan) {
        this.plan = (plan == null) ? WorldPlan.EMPTY : plan;
        repaint();
    }

    /** What is currently shown. For the tests. */
    WorldPlan getPlan() {
        return plan;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 2 * MARGIN_PX || h <= 2 * MARGIN_PX) {
            return; // no room to fit anything yet
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (plan.isEmpty()) {
                paintPlaceholder(g2, w, h);
                return;
            }
            // One uniform pixel scale, centered: normalized units are already
            // aspect-preserved by WorldPlan, so only the fit happens here.
            int inner = Math.min(w, h) - 2 * MARGIN_PX;
            int offX = (w - inner) / 2;
            int offY = (h - inner) / 2;
            Color fill = new Color(getForeground().getRed(),
                    getForeground().getGreen(), getForeground().getBlue(), 28);
            FontMetrics fm = g2.getFontMetrics();
            for (WorldPlan.Item item : plan.items()) {
                int x = offX + (int) Math.round(item.x() * inner);
                int y = offY + (int) Math.round(item.y() * inner);
                int boxW = Math.max(2, (int) Math.round(item.w() * inner));
                int boxH = Math.max(2, (int) Math.round(item.h() * inner));
                g2.setColor(fill);
                g2.fillRect(x, y, boxW, boxH);
                g2.setColor(getForeground());
                g2.drawRect(x, y, boxW, boxH);
                String label = item.label();
                if (fm.stringWidth(label) <= boxW - 2 && fm.getHeight() <= boxH) {
                    g2.drawString(label, x + 1,
                            y + (boxH + fm.getAscent() - fm.getDescent()) / 2);
                } else {
                    g2.drawString(label, x + 1, y - 2 > MARGIN_PX + fm.getAscent()
                            ? y - 2 : y + boxH + fm.getAscent());
                }
            }
        } finally {
            g2.dispose();
        }
    }

    /** The honest empty state: a world with no placement has no plan. */
    private void paintPlaceholder(Graphics2D g2, int w, int h) {
        String text = "no spatial preview";
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(getForeground());
        g2.drawString(text, (w - fm.stringWidth(text)) / 2,
                (h + fm.getAscent() - fm.getDescent()) / 2);
    }
}
