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
package org.jdesktop.lg3d.apps.paint;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;

/**
 * Runtime-drawn toolbox icons. Every glyph is painted into a small ARGB
 * {@link BufferedImage} on first use and cached, so the app ships no image assets
 * and the icons always match the active look-and-feel-independent foreground.
 * {@link #iconFor(String)} maps a {@link Tool#getName()} to its glyph.
 */
public final class PaintIcons {

    private static final int SIZE = 20;
    private static final Color INK = new Color(0x33, 0x33, 0x33);
    private static final Color ACCENT = new Color(0x2a, 0x6f, 0xdb);
    private static final Map<String, Icon> CACHE = new HashMap<String, Icon>();

    private PaintIcons() {
    }

    /** Returns (and caches) the icon for the named tool. */
    public static synchronized Icon iconFor(String name) {
        Icon cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        BufferedImage img = new BufferedImage(SIZE, SIZE,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.setColor(INK);
        drawGlyph(g, name);
        g.dispose();
        Icon icon = new ImageIcon(img);
        CACHE.put(name, icon);
        return icon;
    }

    private static void drawGlyph(Graphics2D g, String name) {
        if ("Brush".equals(name)) {
            g.setColor(ACCENT);
            g.fill(new Rectangle2D.Double(3, 11, 8, 6));
            g.setColor(INK);
            g.draw(new Line2D.Double(9, 11, 16, 4));
            g.draw(new Line2D.Double(12, 14, 17, 9));
        } else if ("Pencil".equals(name)) {
            g.setColor(ACCENT);
            g.fill(new Rectangle2D.Double(4, 12, 4, 4));
            g.setColor(INK);
            g.draw(new Line2D.Double(6, 14, 16, 4));
            g.draw(new Line2D.Double(14, 6, 16, 4));
        } else if ("Eraser".equals(name)) {
            g.setColor(ACCENT);
            g.fill(new Rectangle2D.Double(3, 9, 10, 7));
            g.setColor(INK);
            g.draw(new Rectangle2D.Double(3, 9, 14, 7));
            g.draw(new Line2D.Double(10, 9, 10, 16));
        } else if ("Spray".equals(name)) {
            g.setColor(INK);
            g.draw(new Rectangle2D.Double(7, 9, 6, 8));
            g.setColor(ACCENT);
            int[] xs = { 8, 11, 14, 10, 13, 16, 12 };
            int[] ys = { 3, 5, 3, 7, 6, 5, 2 };
            for (int i = 0; i < xs.length; i++) {
                g.fillOval(xs[i], ys[i], 2, 2);
            }
        } else if ("Line".equals(name)) {
            g.setColor(INK);
            g.draw(new Line2D.Double(4, 16, 16, 4));
        } else if ("Rectangle".equals(name)) {
            g.setColor(INK);
            g.draw(new Rectangle2D.Double(4, 6, 12, 9));
        } else if ("Ellipse".equals(name)) {
            g.setColor(INK);
            g.draw(new Ellipse2D.Double(4, 5, 12, 11));
        } else if ("Polygon".equals(name)) {
            g.setColor(INK);
            GeneralPath p = new GeneralPath();
            p.moveTo(10, 3);
            p.lineTo(17, 15);
            p.lineTo(3, 15);
            p.closePath();
            g.draw(p);
        } else if ("Freeform".equals(name)) {
            g.setColor(INK);
            GeneralPath p = new GeneralPath();
            p.moveTo(3, 14);
            p.curveTo(7, 3, 11, 17, 17, 6);
            g.draw(p);
        } else if ("Fill".equals(name)) {
            g.setColor(INK);
            GeneralPath p = new GeneralPath();
            p.moveTo(5, 9);
            p.lineTo(11, 3);
            p.lineTo(16, 9);
            p.lineTo(11, 15);
            p.closePath();
            g.draw(p);
            g.setColor(ACCENT);
            p.closePath();
            g.fill(p);
            g.setColor(INK);
            g.fillOval(4, 15, 3, 3);
        } else if ("Eyedropper".equals(name)) {
            g.setColor(INK);
            g.draw(new Line2D.Double(6, 15, 13, 8));
            g.draw(new Line2D.Double(12, 5, 16, 9));
            g.setColor(ACCENT);
            g.fill(new Rectangle2D.Double(4, 14, 3, 3));
        } else if ("Text".equals(name)) {
            g.setColor(INK);
            g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 15f));
            g.drawString("A", 5, 16);
            g.draw(new Line2D.Double(3, 4, 17, 4));
        } else if ("Select".equals(name)) {
            g.setColor(INK);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 1.0f, new float[] { 3f, 2f }, 0f));
            g.draw(new Rectangle2D.Double(4, 5, 12, 10));
        } else if ("Lasso".equals(name)) {
            g.setColor(INK);
            GeneralPath p = new GeneralPath();
            p.append(new Ellipse2D.Double(3, 4, 14, 10), false);
            g.draw(p);
            g.draw(new Line2D.Double(10, 13, 8, 18));
        } else {
            g.setColor(INK);
            g.draw(new Rectangle2D.Double(4, 4, 12, 12));
        }
    }
}
