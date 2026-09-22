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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A desktop clock with analog and digital faces. Clicking the card toggles
 * between the two; the choice is persisted. Updates once per second.
 *
 * <p>Pure Swing: the 3D {@link ClockWidget} and the 2D desktop layer both host
 * this same card.</p>
 */
public class ClockCard extends WidgetCard {
    public static final String ID = "clock";

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy");

    private volatile boolean analog = true;
    private volatile int hour = 0;
    private volatile int minute = 0;
    private volatile int second = 0;
    private volatile String dateStr = "";

    public ClockCard() {
        super("Clock", 150, 150);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long tickPeriodMillis() {
        return 1000L;
    }

    @Override
    protected void onAttach() {
        analog = !"digital".equalsIgnoreCase(getOption("mode", "analog"));
    }

    @Override
    public void tick() {
        LocalDateTime dt = LocalDateTime.now();
        hour = dt.getHour();
        minute = dt.getMinute();
        second = dt.getSecond();
        dateStr = dt.format(DATE_FMT);
        repaint();
    }

    @Override
    public void onClick() {
        analog = !analog;
        setOption("mode", analog ? "analog" : "digital");
        repaint();
    }

    @Override
    protected void paintContent(Graphics2D g2, int w, int h, int top) {
        if (analog) {
            paintAnalog(g2, w, h, top);
        } else {
            paintDigital(g2, w, h, top);
        }
    }

    private void paintAnalog(Graphics2D g2, int w, int h, int top) {
        int availH = h - top - 6;
        int d = Math.min(w - 16, availH);
        if (d < 20) {
            return;
        }
        int cx = w / 2;
        int cy = top + availH / 2;
        double r = d / 2.0;

        g2.setColor(new Color(200, 220, 250, 40));
        g2.fill(new Ellipse2D.Double(cx - r, cy - r, d, d));
        g2.setColor(BORDER);
        g2.setStroke(new BasicStroke(1.6f));
        g2.draw(new Ellipse2D.Double(cx - r, cy - r, d, d));

        // Hour ticks.
        g2.setColor(TEXT_DIM);
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30 - 90);
            double r1 = r * 0.86;
            double r2 = r * 0.96;
            g2.setStroke(new BasicStroke(i % 3 == 0 ? 2.0f : 1.0f));
            g2.draw(new Line2D.Double(cx + r1 * Math.cos(a), cy + r1 * Math.sin(a),
                    cx + r2 * Math.cos(a), cy + r2 * Math.sin(a)));
        }

        double ha = Math.toRadians(((hour % 12) + minute / 60.0) * 30 - 90);
        double ma = Math.toRadians(minute * 6 - 90);
        double sa = Math.toRadians(second * 6 - 90);

        g2.setColor(TEXT);
        g2.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(cx, cy, cx + r * 0.50 * Math.cos(ha), cy + r * 0.50 * Math.sin(ha)));
        g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(cx, cy, cx + r * 0.74 * Math.cos(ma), cy + r * 0.74 * Math.sin(ma)));
        g2.setColor(new Color(240, 130, 110));
        g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(cx, cy, cx + r * 0.82 * Math.cos(sa), cy + r * 0.82 * Math.sin(sa)));
        g2.fill(new Ellipse2D.Double(cx - 2.5, cy - 2.5, 5, 5));
    }

    private void paintDigital(Graphics2D g2, int w, int h, int top) {
        int availH = h - top - 6;
        String hhmm = String.format("%02d:%02d", hour, minute);
        float size = Math.max(16f, Math.min(availH * 0.46f, w * 0.30f));
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, size));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(TEXT);
        int tx = (w - fm.stringWidth(hhmm)) / 2;
        int ty = top + availH / 2 - 2;
        g2.drawString(hhmm, tx, ty);

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, Math.max(10f, size * 0.34f)));
        FontMetrics fm2 = g2.getFontMetrics();
        g2.setColor(new Color(240, 130, 110));
        String ss = String.format(":%02d", second);
        g2.drawString(ss, tx + fm.stringWidth(hhmm) + 2, ty);
        g2.setColor(TEXT_DIM);
        String date = dateStr;
        g2.drawString(date, (w - fm2.stringWidth(date)) / 2, ty + fm2.getHeight() + 2);
    }
}
