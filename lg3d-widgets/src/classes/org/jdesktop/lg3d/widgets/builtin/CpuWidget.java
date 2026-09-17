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
import java.awt.geom.Path2D;
import org.jdesktop.lg3d.utils.system.ProcessService;
import org.jdesktop.lg3d.utils.system.SystemInfoService;
import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * Live CPU load with a small sparkline of recent history. The aggregate
 * percentage comes from {@link ProcessService#totalCpuPercent()} (successive
 * {@code /proc/stat} deltas); the core count from {@link SystemInfoService}.
 */
public class CpuWidget extends AbstractWidget {
    public static final String ID = "cpu";

    private static final long POLL_MILLIS = 1500L;
    /** Number of history samples kept for the sparkline. */
    private static final int HISTORY = 48;

    private final float[] history = new float[HISTORY];
    private int historyCount = 0;

    private volatile float percent = 0f;
    private volatile int cores = 0;

    public CpuWidget() {
        super(ID, "CPU");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        try {
            cores = SystemInfoService.cpu().getLogicalCores();
        } catch (RuntimeException e) {
            cores = Runtime.getRuntime().availableProcessors();
        }
        setSwingPanel(new CpuPanel());
    }

    @Override
    public void start() {
        // Prime the /proc/stat baseline so the first real sample is meaningful.
        ProcessService.totalCpuPercent();
        scheduleTick(POLL_MILLIS, this::tick);
    }

    private void tick() {
        percent = (float) ProcessService.totalCpuPercent();
        synchronized (history) {
            System.arraycopy(history, 1, history, 0, HISTORY - 1);
            history[HISTORY - 1] = percent;
            if (historyCount < HISTORY) {
                historyCount++;
            }
        }
        setDirty();
    }

    private final class CpuPanel extends WidgetPanel {
        CpuPanel() {
            super("CPU", 150, 110);
        }

        @Override
        protected void paintContent(Graphics2D g2, int w, int h, int top) {
            int availH = h - top - 8;
            float p = percent;

            // Big percentage.
            String txt = String.format("%.0f%%", p);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, Math.max(16f, availH * 0.34f)));
            FontMetrics fm = g2.getFontMetrics();
            Color col = TemperatureWidget.colorFor(40.0 + p * 0.5);
            g2.setColor(col);
            int tx = 12;
            int ty = top + fm.getAscent() + 2;
            g2.drawString(txt, tx, ty);

            String sub = cores + (cores == 1 ? " core" : " cores");
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.setColor(TEXT_DIM);
            g2.drawString(sub, tx, ty + fm2.getHeight() + 1);

            // Sparkline in the lower portion.
            int sparkTop = ty + 8;
            int sparkH = Math.max(16, h - sparkTop - 10);
            int sparkW = w - 24;
            if (sparkW > 10 && sparkH > 8) {
                g2.setColor(new Color(255, 255, 255, 18));
                g2.fillRect(12, sparkTop, sparkW, sparkH);
                g2.setColor(new Color(96, 148, 214, 70));
                g2.drawRect(12, sparkTop, sparkW, sparkH);

                Path2D line = new Path2D.Float();
                float[] snapshot;
                int count;
                synchronized (history) {
                    snapshot = history.clone();
                    count = historyCount;
                }
                int start = HISTORY - count;
                for (int i = start; i < HISTORY; i++) {
                    int xi = 12 + (int) ((i - start) * (sparkW / (float) Math.max(1, count - 1)));
                    if (count == 1) {
                        xi = 12 + sparkW;
                    }
                    float v = Math.max(0f, Math.min(100f, snapshot[i]));
                    int yi = sparkTop + sparkH - (int) (v / 100f * sparkH);
                    if (i == start) {
                        line.moveTo(xi, yi);
                    } else {
                        line.lineTo(xi, yi);
                    }
                }
                g2.setColor(col);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(line);
            }
        }
    }
}
