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
package org.jdesktop.lg3d.widgets.builtin;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import org.jdesktop.lg3d.utils.system.ProcessService;
import org.jdesktop.lg3d.utils.system.SystemInfoService;

/**
 * Used/total RAM plus swap, from {@link SystemInfoService#memory()}. Draws two
 * horizontal bars and refreshes every couple of seconds.
 *
 * <p>Pure Swing: the 3D {@link MemoryWidget} and the 2D desktop layer both host
 * this same card.</p>
 */
public class MemoryCard extends WidgetCard {
    public static final String ID = "memory";

    private static final long POLL_MILLIS = 2000L;

    private volatile long totalKb = 0;
    private volatile long usedKb = 0;
    private volatile long swapTotalKb = 0;
    private volatile long swapUsedKb = 0;

    public MemoryCard() {
        super("Memory", 160, 110);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long tickPeriodMillis() {
        return POLL_MILLIS;
    }

    @Override
    public void tick() {
        try {
            SystemInfoService.Memory m = SystemInfoService.memory();
            totalKb = m.getTotalKb();
            usedKb = m.getUsedKb();
            swapTotalKb = m.getSwapTotalKb();
            swapUsedKb = m.getSwapUsedKb();
        } catch (RuntimeException e) {
            totalKb = 0;
        }
        repaint();
    }

    @Override
    protected void paintContent(Graphics2D g2, int w, int h, int top) {
        int barX = 12;
        int barW = w - 24;
        int y = top + 4;

        y = paintBar(g2, barX, y, barW, "RAM", usedKb, totalKb,
                new Color(96, 168, 240));
        paintBar(g2, barX, y + 4, barW, "Swap", swapUsedKb, swapTotalKb,
                new Color(150, 130, 220));
    }

    private int paintBar(Graphics2D g2, int x, int y, int w, String label,
                         long usedKb, long totalKb, Color fill) {
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(TEXT_DIM);
        String caption;
        if (totalKb <= 0) {
            caption = label + ": none";
        } else {
            caption = label + ": " + ProcessService.formatBytes(usedKb * 1024L)
                    + " / " + ProcessService.formatBytes(totalKb * 1024L);
        }
        g2.drawString(caption, x, y + fm.getAscent());
        y += fm.getHeight() + 2;

        int barH = 10;
        g2.setColor(new Color(255, 255, 255, 22));
        g2.fillRoundRect(x, y, w, barH, 8, 8);
        double frac = (totalKb > 0) ? Math.max(0.0, Math.min(1.0, usedKb / (double) totalKb)) : 0.0;
        int fw = (int) (w * frac);
        if (fw > 0) {
            g2.setColor(fill);
            g2.fillRoundRect(x, y, fw, barH, 8, 8);
        }
        g2.setColor(new Color(96, 148, 214, 70));
        g2.drawRoundRect(x, y, w, barH, 8, 8);
        if (totalKb > 0) {
            String pct = String.format("%.0f%%", frac * 100.0);
            FontMetrics fm2 = g2.getFontMetrics(g2.getFont().deriveFont(Font.BOLD, 9f));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 9f));
            g2.setColor(TEXT);
            g2.drawString(pct, x + w - fm2.stringWidth(pct), y + barH + fm2.getAscent() - 1);
        }
        return y + barH + 2;
    }
}
