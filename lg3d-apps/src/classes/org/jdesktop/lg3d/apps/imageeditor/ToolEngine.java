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
package org.jdesktop.lg3d.apps.imageeditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The image editor's drawing primitives. Each method paints a completed shape
 * or stroke directly into a {@link BufferedImage} (the active layer) through a
 * {@link Graphics2D} with anti-aliasing on. The panel turns mouse drags into
 * these calls; keeping them here as pure statics makes the geometry testable
 * headless.
 *
 * <p>The {@link Tool} enum names the toolbar's modes so the panel and tests
 * share one vocabulary.</p>
 */
public final class ToolEngine {

    /** The toolbar's drawing modes. */
    public enum Tool {
        /** Freehand brush strokes. */
        BRUSH,
        /** Straight line between two points. */
        LINE,
        /** Rectangle outline or filled. */
        RECT,
        /** Ellipse outline or filled. */
        ELLIPSE,
        /** Flood the whole layer with a colour. */
        FILL,
        /** Erase (paint transparent). */
        ERASER,
        /** Place a text string. */
        TEXT
    }

    private ToolEngine() {
        // no instances
    }

    /**
     * Draws a straight line (used for both {@link Tool#LINE} and each brush
     * segment).
     *
     * @param img    the target image
     * @param color  the stroke colour
     * @param width  the stroke width in pixels (at least 1)
     */
    public static void drawLine(BufferedImage img, Color color,
                                int x1, int y1, int x2, int y2, float width) {
        Graphics2D g = prepare(img);
        if (g == null) {
            return;
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(Math.max(1f, width),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x1, y1, x2, y2);
        g.dispose();
    }

    /**
     * Draws a rectangle outline, or a filled rectangle when {@code filled}.
     */
    public static void drawRect(BufferedImage img, Color color,
                                int x, int y, int w, int h,
                                float width, boolean filled) {
        Graphics2D g = prepare(img);
        if (g == null) {
            return;
        }
        g.setColor(color);
        int nx = Math.min(x, x + w);
        int ny = Math.min(y, y + h);
        int nw = Math.abs(w);
        int nh = Math.abs(h);
        if (filled) {
            g.fillRect(nx, ny, nw, nh);
        } else {
            g.setStroke(new BasicStroke(Math.max(1f, width)));
            g.drawRect(nx, ny, nw, nh);
        }
        g.dispose();
    }

    /**
     * Draws an ellipse outline, or a filled ellipse when {@code filled}.
     */
    public static void drawEllipse(BufferedImage img, Color color,
                                   int x, int y, int w, int h,
                                   float width, boolean filled) {
        Graphics2D g = prepare(img);
        if (g == null) {
            return;
        }
        g.setColor(color);
        int nx = Math.min(x, x + w);
        int ny = Math.min(y, y + h);
        int nw = Math.abs(w);
        int nh = Math.abs(h);
        if (filled) {
            g.fillOval(nx, ny, nw, nh);
        } else {
            g.setStroke(new BasicStroke(Math.max(1f, width)));
            g.drawOval(nx, ny, nw, nh);
        }
        g.dispose();
    }

    /** Fills the whole image with {@code color} (used by {@link Tool#FILL}). */
    public static void fill(BufferedImage img, Color color) {
        Graphics2D g = prepare(img);
        if (g == null) {
            return;
        }
        g.setColor(color);
        g.setComposite(java.awt.AlphaComposite.Src);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
    }

    /**
     * Draws {@code text} at ({@code x}, {@code y}) with the given font size.
     * The y coordinate is the baseline, matching {@link Graphics2D#drawString}.
     */
    public static void drawText(BufferedImage img, Color color, String text,
                                int x, int y, float fontSize) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Graphics2D g = prepare(img);
        if (g == null) {
            return;
        }
        g.setColor(color);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(Math.max(1f, fontSize))));
        g.drawString(text, x, y);
        g.dispose();
    }

    /**
     * Erases a line segment by painting it fully transparent (used by
     * {@link Tool#ERASER}).
     */
    public static void erase(BufferedImage img, int x1, int y1, int x2, int y2,
                             float width) {
        Graphics2D g = prepare(img);
        if (g == null) {
            return;
        }
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.setStroke(new BasicStroke(Math.max(1f, width),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x1, y1, x2, y2);
        g.dispose();
    }

    /** A {@link Graphics2D} for {@code img} with anti-aliasing, or null. */
    private static Graphics2D prepare(BufferedImage img) {
        if (img == null) {
            return null;
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }
}
