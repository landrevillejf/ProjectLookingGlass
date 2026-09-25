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

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.displayserver.desktop2d.BatteryStatus;
import org.jdesktop.lg3d.displayserver.desktop2d.BrightnessStatus;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus;
import org.jdesktop.lg3d.displayserver.desktop2d.VolumeStatus;

/**
 * The system-indicator cluster - network link, master volume, screen brightness
 * and battery charge - as a glassy widget card. It is the native 3D desktop's
 * counterpart of the 2D/Swing taskbar's {@code TaskbarIndicators}, reusing the
 * very same pure platform seams ({@link NetworkStatus}, {@link VolumeStatus},
 * {@link BrightnessStatus}, {@link BatteryStatus}) so both desktops read and
 * format the hardware identically.
 *
 * <p>Each indicator hides itself when its probe reports nothing (no sound card,
 * no backlight, no battery, non-Linux host), so the card degrades gracefully to
 * just the rows that exist - and always keeps at least the network row, which
 * reports "offline" rather than vanishing. The row-assembly logic is pure
 * ({@link #rows}) and unit-tested; {@link #tick()} is the thin platform poll that
 * publishes to {@code volatile} fields and repaints.</p>
 *
 * <p>Pure Swing: the 3D {@link SystemIndicatorsWidget} and the 2D desktop layer
 * both host this same card.</p>
 */
public class SystemIndicatorsCard extends WidgetCard {
    public static final String ID = "indicators";

    /** Network/battery/brightness change slowly, so poll on the 2D cluster's cadence. */
    private static final long POLL_MILLIS = 5000L;

    /** Accent for an online link. */
    private static final Color NETWORK_ON = new Color(110, 210, 150);
    /** Accent for the master volume. */
    private static final Color VOLUME_ON = new Color(96, 168, 240);
    /** Accent for the backlight gauge (matches the 2D brightness fill). */
    private static final Color BRIGHTNESS_ON = new Color(0xF1, 0xC4, 0x0F);
    /** Track behind a percentage bar. */
    private static final Color BAR_TRACK = new Color(255, 255, 255, 22);

    private volatile NetworkStatus.State network = NetworkStatus.State.offline();
    private volatile VolumeStatus.Level volume;
    private volatile BrightnessStatus.Level brightness;
    private volatile BatteryStatus.Level battery;

    public SystemIndicatorsCard() {
        super("System", 160, 130);
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
        // Every seam guards its own probe and returns empty/offline on failure,
        // so a host with no battery/backlight/sound simply omits that row.
        network = NetworkStatus.read();
        volume = VolumeStatus.read().orElse(null);
        brightness = BrightnessStatus.read().orElse(null);
        battery = BatteryStatus.read().orElse(null);
        repaint();
    }

    /**
     * One indicator row: the compact {@code glyph} text, its accent colour, and a
     * 0-100 percentage to draw as a bar - or {@code -1} when the row has no gauge
     * (network, or a muted volume).
     */
    record Row(String text, Color color, int percent) {
    }

    /** Sentinel percentage for a row with no gauge bar. */
    static final int NO_GAUGE = -1;

    /**
     * Pure row assembly from already-read platform values, in cluster order
     * (network, volume, brightness, battery). A null reading is omitted; when
     * nothing is present a single dim "No indicators" row keeps the card legible.
     */
    static List<Row> rows(NetworkStatus.State network, VolumeStatus.Level volume,
            BrightnessStatus.Level brightness, BatteryStatus.Level battery) {
        List<Row> out = new ArrayList<>(4);
        if (network != null) {
            out.add(new Row(NetworkStatus.glyph(network),
                    network.online() ? NETWORK_ON : TEXT_DIM, NO_GAUGE));
        }
        if (volume != null) {
            out.add(new Row(VolumeStatus.glyph(volume),
                    volume.muted() ? TEXT_DIM : VOLUME_ON,
                    volume.muted() ? NO_GAUGE : volume.percent()));
        }
        if (brightness != null) {
            out.add(new Row(BrightnessStatus.glyph(brightness),
                    BRIGHTNESS_ON, brightness.percent()));
        }
        if (battery != null) {
            out.add(new Row(BatteryStatus.glyph(battery),
                    BatteryStatus.color(battery), battery.percent()));
        }
        if (out.isEmpty()) {
            out.add(new Row("No indicators", TEXT_DIM, NO_GAUGE));
        }
        return out;
    }

    @Override
    protected void paintContent(Graphics2D g2, int w, int h, int top) {
        List<Row> rs = rows(network, volume, brightness, battery);
        int x = 12;
        int rowW = w - 24;
        int availH = Math.max(16, h - top - 8);
        int slot = Math.max(14, availH / rs.size());

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g2.getFontMetrics();

        for (int i = 0; i < rs.size(); i++) {
            Row r = rs.get(i);
            int yTop = top + 4 + i * slot;

            // Accent bullet + glyph text.
            g2.setColor(r.color());
            g2.fillRoundRect(x, yTop + 3, 6, 6, 3, 3);
            g2.setColor(TEXT);
            g2.drawString(r.text(), x + 12, yTop + fm.getAscent());

            if (r.percent() >= 0) {
                int barY = yTop + fm.getHeight() + 1;
                int barH = 4;
                g2.setColor(BAR_TRACK);
                g2.fillRoundRect(x, barY, rowW, barH, 4, 4);
                int pct = Math.max(0, Math.min(100, r.percent()));
                int fw = (int) Math.round(rowW * pct / 100.0);
                if (fw > 0) {
                    g2.setColor(r.color());
                    g2.fillRoundRect(x, barY, fw, barH, 4, 4);
                }
            }
        }
    }
}
