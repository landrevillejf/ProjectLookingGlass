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
import java.awt.geom.Arc2D;
import java.util.List;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.system.ThermalService;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.WidgetContext;

/**
 * Shows CPU/system temperature from {@link ThermalService}. Polls the sensors
 * periodically; clicking the widget cycles through the available zones. When no
 * thermal sensor is exposed (common in VMs) it shows "n/a" rather than failing.
 */
public class TemperatureWidget extends AbstractWidget {
    public static final String ID = "temperature";

    private static final long POLL_MILLIS = 2500L;

    /** Colour thresholds: cool / warm / hot. */
    private static final double WARM = 60.0;
    private static final double HOT = 80.0;

    private volatile List<ThermalService.Sensor> sensors = List.of();
    private volatile int selected = 0;

    public TemperatureWidget() {
        super(ID, "Temperature");
    }

    @Override
    public void init(WidgetContext context) {
        super.init(context);
        setSwingPanel(new TemperaturePanel());
        // Cycle to the next zone on click.
        addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            @Override
            public void performAction(LgEventSource source) {
                List<ThermalService.Sensor> s = sensors;
                if (s.size() > 1) {
                    selected = (selected + 1) % s.size();
                    setDirty();
                }
            }
        }));
    }

    @Override
    public void start() {
        tick();
        scheduleTick(POLL_MILLIS, this::tick);
    }

    private void tick() {
        try {
            List<ThermalService.Sensor> read = ThermalService.read();
            sensors = read;
            if (selected >= read.size()) {
                selected = 0;
            }
        } catch (RuntimeException e) {
            sensors = List.of();
        }
        setDirty();
    }

    private ThermalService.Sensor current() {
        List<ThermalService.Sensor> s = sensors;
        if (s.isEmpty()) {
            return null;
        }
        int idx = Math.min(selected, s.size() - 1);
        return s.get(idx);
    }

    static Color colorFor(double celsius) {
        if (celsius >= HOT) {
            return new Color(240, 110, 90);
        }
        if (celsius >= WARM) {
            return new Color(240, 190, 90);
        }
        return new Color(110, 210, 150);
    }

    private final class TemperaturePanel extends WidgetPanel {
        TemperaturePanel() {
            super("Temperature", 150, 120);
        }

        @Override
        protected void paintContent(Graphics2D g2, int w, int h, int top) {
            ThermalService.Sensor sensor = current();
            int availH = h - top - 8;
            if (sensor == null) {
                g2.setColor(TEXT_DIM);
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
                FontMetrics fm = g2.getFontMetrics();
                String msg = "no sensor";
                g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, top + availH / 2);
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
                fm = g2.getFontMetrics();
                String sub = "(n/a on this machine)";
                g2.drawString(sub, (w - fm.stringWidth(sub)) / 2, top + availH / 2 + fm.getHeight() + 2);
                return;
            }

            double c = sensor.getCelsius();
            Color col = colorFor(c);

            // Gauge arc across the top of the content area.
            int arcD = Math.min(w - 40, availH);
            if (arcD < 24) {
                arcD = 24;
            }
            int cx = (w - arcD) / 2;
            int cy = top;
            g2.setStroke(new BasicStroke(6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(255, 255, 255, 28));
            g2.draw(new Arc2D.Double(cx, cy, arcD, arcD, 180, 180, Arc2D.OPEN));
            // Map 30C..100C onto 0..180 degrees.
            double frac = Math.max(0.0, Math.min(1.0, (c - 30.0) / 70.0));
            g2.setColor(col);
            g2.draw(new Arc2D.Double(cx, cy, arcD, arcD, 180, -180.0 * frac, Arc2D.OPEN));

            // Big reading.
            String txt = String.format("%.0f\u00B0C", c);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, Math.max(18f, arcD * 0.36f)));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(col);
            g2.drawString(txt, (w - fm.stringWidth(txt)) / 2, cy + arcD / 2 + fm.getAscent() / 2);

            // Sensor label + zone index.
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
            fm = g2.getFontMetrics();
            g2.setColor(TEXT_DIM);
            String label = sensor.getLabel();
            if (label.length() > 18) {
                label = label.substring(0, 17) + "\u2026";
            }
            int count = sensors.size();
            String zone = (count > 1) ? (label + " (" + (Math.min(selected, count - 1) + 1) + "/" + count + ")") : label;
            int zy = Math.min(h - 8, cy + arcD + fm.getAscent() + 2);
            g2.drawString(zone, (w - fm.stringWidth(zone)) / 2, zy);

            Double crit = sensor.getCritical();
            if (crit != null) {
                String critTxt = String.format("crit %.0f\u00B0C", crit);
                g2.setColor(new Color(150, 165, 185, 160));
                g2.drawString(critTxt, (w - fm.stringWidth(critTxt)) / 2, zy + fm.getHeight());
            }
        }
    }
}
