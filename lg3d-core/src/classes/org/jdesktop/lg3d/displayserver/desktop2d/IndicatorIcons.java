/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle / JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.Kind;

/**
 * Procedurally-drawn pictorial icons for the taskbar's system-indicator tray
 * (master volume and network link), the 2D desktop's counterpart of a
 * conventional system tray's glyphs.
 *
 * <p>{@code IconManager} ships a fixed {@code Volume} GIF but no network /
 * wi-fi / mute artwork, and a tray icon has to reflect live <em>state</em>
 * (a level, a mute, a link kind, an offline slash) rather than a static
 * picture. So these icons are drawn with {@link Graphics2D} into a transparent
 * {@link BufferedImage} - the same offscreen technique
 * {@link NotificationTray#badgedIcon} uses - which renders headlessly, scales
 * to any edge and stays crisp. The pure {@code *Image} factories return the
 * {@code BufferedImage} (directly usable as an AWT {@code TrayIcon} image);
 * the {@code *Icon} wrappers adapt them for a {@code JLabel}.</p>
 *
 * <p>Every factory clamps its size and percentage and never throws, so a
 * missing sound card or link simply yields the offline / muted picture.</p>
 */
final class IndicatorIcons {

    /** Default icon edge, in pixels, matching the taskbar gauge icons. */
    static final int SIZE = 16;
    /** Smallest edge we will draw; below this the shapes degenerate. */
    private static final int MIN_SIZE = 8;

    /** Speaker body and sound-wave colour. */
    private static final Color VOLUME_COLOR = new Color(0x33, 0x33, 0x33);
    /** The mute cross, matching the notification badge red. */
    private static final Color MUTE_COLOR = new Color(0xc0, 0x39, 0x2b);
    /** An online link (wi-fi arcs / ethernet plug). */
    private static final Color ONLINE_COLOR = new Color(0x2f, 0x8f, 0x4e);
    /** An offline link, drawn grey with a red slash. */
    private static final Color OFFLINE_COLOR = Color.GRAY;

    private IndicatorIcons() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Volume
    // ------------------------------------------------------------------

    /**
     * A speaker cone with zero-to-three sound waves scaled to {@code percent}
     * (0-100), or a speaker crossed out in red when {@code muted}. A null
     * {@code percent} (no master control) draws a greyed speaker with no waves.
     */
    static BufferedImage volumeImage(Integer percent, boolean muted, int size) {
        int s = clampSize(size);
        BufferedImage image = canvas(s);
        Graphics2D g = graphics(image);
        try {
            Color body = (percent == null) ? OFFLINE_COLOR : VOLUME_COLOR;
            g.setColor(body);
            g.fillPolygon(speakerCone(s));
            if (muted) {
                drawMuteCross(g, s);
            } else if (percent != null) {
                drawWaves(g, s, percent);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** The {@link #volumeImage} picture as a Swing {@link Icon}. */
    static Icon volumeIcon(Integer percent, boolean muted, int size) {
        return new ImageIcon(volumeImage(percent, muted, size));
    }

    /** The speaker body+cone as a closed polygon spanning the left of the box. */
    private static java.awt.Polygon speakerCone(int s) {
        int[] xs = {
            px(s, 0.12f), px(s, 0.30f), px(s, 0.48f),
            px(s, 0.48f), px(s, 0.30f), px(s, 0.12f),
        };
        int[] ys = {
            py(s, 0.40f), py(s, 0.40f), py(s, 0.20f),
            py(s, 0.80f), py(s, 0.60f), py(s, 0.60f),
        };
        return new java.awt.Polygon(xs, ys, xs.length);
    }

    /** One arc per volume third, radiating from the cone's mouth. */
    private static void drawWaves(Graphics2D g, int s, int percent) {
        int level = VolumeStatus.clamp(percent);
        int waves = (level <= 0) ? 0 : (level < 34 ? 1 : (level < 67 ? 2 : 3));
        g.setColor(VOLUME_COLOR);
        g.setStroke(new BasicStroke(Math.max(1f, s * 0.07f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = px(s, 0.50f);
        int cy = py(s, 0.50f);
        for (int i = 1; i <= waves; i++) {
            int r = Math.round(s * (0.14f * i + 0.06f));
            // A 90-degree arc opening to the right, centred on the cone mouth.
            g.drawArc(cx - r, cy - r, 2 * r, 2 * r, -45, 90);
        }
    }

    /** A red cross to the right of the speaker marking the muted state. */
    private static void drawMuteCross(Graphics2D g, int s) {
        g.setColor(MUTE_COLOR);
        g.setStroke(new BasicStroke(Math.max(1.2f, s * 0.09f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int x0 = px(s, 0.60f);
        int y0 = py(s, 0.36f);
        int x1 = px(s, 0.88f);
        int y1 = py(s, 0.64f);
        g.drawLine(x0, y0, x1, y1);
        g.drawLine(x1, y0, x0, y1);
    }

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    /**
     * A link glyph for {@code kind}: three wi-fi arcs, an ethernet (RJ45) plug,
     * or - when {@link Kind#OFFLINE} - the shape drawn grey with a red slash.
     * A null {@code kind} is treated as offline.
     */
    static BufferedImage networkImage(Kind kind, int size) {
        int s = clampSize(size);
        BufferedImage image = canvas(s);
        Graphics2D g = graphics(image);
        try {
            Kind k = (kind == null) ? Kind.OFFLINE : kind;
            boolean online = k != Kind.OFFLINE;
            g.setColor(online ? ONLINE_COLOR : OFFLINE_COLOR);
            g.setStroke(new BasicStroke(Math.max(1.1f, s * 0.08f),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (k == Kind.WIFI) {
                drawWifi(g, s);
            } else {
                drawEthernet(g, s);
            }
            if (!online) {
                drawOfflineSlash(g, s);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** The {@link #networkImage} picture as a Swing {@link Icon}. */
    static Icon networkIcon(Kind kind, int size) {
        return new ImageIcon(networkImage(kind, size));
    }

    /** A dot plus three radiating arcs, the classic wi-fi fan. */
    private static void drawWifi(Graphics2D g, int s) {
        int cx = px(s, 0.50f);
        int dotY = py(s, 0.80f);
        int dot = Math.max(2, Math.round(s * 0.12f));
        g.fillOval(cx - dot / 2, dotY - dot / 2, dot, dot);
        for (int i = 1; i <= 3; i++) {
            int r = Math.round(s * (0.17f * i));
            // Upper-half arcs (45..135 degrees) centred on the dot.
            g.drawArc(cx - r, dotY - r, 2 * r, 2 * r, 45, 90);
        }
    }

    /** An RJ45 plug: a body, a top clip and three contacts beneath. */
    private static void drawEthernet(Graphics2D g, int s) {
        int x = px(s, 0.28f);
        int y = py(s, 0.30f);
        int w = px(s, 0.72f) - x;
        int h = py(s, 0.60f) - y;
        g.drawRoundRect(x, y, w, h, Math.max(2, s / 8), Math.max(2, s / 8));
        // The retaining clip on top.
        g.drawLine(px(s, 0.42f), py(s, 0.30f), px(s, 0.42f), py(s, 0.18f));
        g.drawLine(px(s, 0.58f), py(s, 0.30f), px(s, 0.58f), py(s, 0.18f));
        g.drawLine(px(s, 0.42f), py(s, 0.18f), px(s, 0.58f), py(s, 0.18f));
        // Three contacts below the body.
        for (float fx : new float[] {0.38f, 0.50f, 0.62f}) {
            g.drawLine(px(s, fx), py(s, 0.60f), px(s, fx), py(s, 0.76f));
        }
    }

    /** A red diagonal slash across the glyph marking "no link". */
    private static void drawOfflineSlash(Graphics2D g, int s) {
        g.setColor(MUTE_COLOR);
        g.setStroke(new BasicStroke(Math.max(1.2f, s * 0.09f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(px(s, 0.20f), py(s, 0.80f), px(s, 0.80f), py(s, 0.20f));
    }

    // ------------------------------------------------------------------
    // Drawing helpers
    // ------------------------------------------------------------------

    /** A transparent square canvas of edge {@code s}. */
    private static BufferedImage canvas(int s) {
        return new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
    }

    /** An antialiased {@link Graphics2D} for {@code image}. */
    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** Clamps an icon edge into a drawable range. */
    private static int clampSize(int size) {
        return Math.max(MIN_SIZE, Math.min(128, size));
    }

    /** The x pixel for a fractional position across the icon. */
    private static int px(int s, float fraction) {
        return Math.round(s * fraction);
    }

    /** The y pixel for a fractional position down the icon. */
    private static int py(int s, float fraction) {
        return Math.round(s * fraction);
    }
}
