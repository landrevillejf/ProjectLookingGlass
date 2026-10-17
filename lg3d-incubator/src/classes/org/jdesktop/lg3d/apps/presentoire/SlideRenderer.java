/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.apps.presentoire;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;

/**
 * Paints one {@link SlideModel} into a {@link BufferedImage} with Java 2D.
 *
 * <p>This is the 2D desktop's slide rasteriser: the same AWT-free model the
 * native-3D {@link Slide} builds {@code Component3D} nodes from is drawn here as
 * a bitmap, so {@link PresentoirePanel} needs no Java 3D and runs on a machine
 * without the 3D desktop. It is headless-safe (a {@code BufferedImage} and its
 * {@code Graphics2D} need no display), which is why the tests can render a slide
 * and assert on its pixels.</p>
 *
 * <p>The mapping reproduces the 3D anchoring exactly. A slide spans
 * {@code -0.1 .. 0.1} on both axes ({@link PresentoireModel#SLIDE_HALF}); x grows
 * right and y grows up, so slide-y is flipped into pixel-y. A {@code <text>} or
 * {@code <ul>} is anchored bottom-left like {@code GlassyText2D}: line {@code i}
 * of an {@code n}-line block occupies the slide-y band
 * {@code [y - h/n*i, y - h/n*(i-1)]}, the first line sitting <em>above</em> the
 * anchor exactly as the 3D quad does. An {@code <img>} is centred on its
 * {@code (x, y)} like {@code ImagePanel}, spanning {@code +-width/2, +-height/2}.
 * Text is drawn in the document's own colour (default black); the slide surface is
 * a white page, so the rendered bitmap is document content - the panel chrome
 * around it stays in the look-and-feel's own colours.</p>
 */
final class SlideRenderer {

    /** The natural slide bitmap edge, before any panel zoom is applied. */
    static final int BASE_SIDE_PX = 520;

    /** A slide's full width (and height) in document units: {@code 2 * 0.1}. */
    private static final float SLIDE_SPAN = 2f * PresentoireModel.SLIDE_HALF;

    private SlideRenderer() {
        // no instances
    }

    /**
     * Renders {@code slide} into a square bitmap {@code sidePx} pixels on a side.
     *
     * @param slide  the slide to draw; null renders a blank page
     * @param doc    the owning deck, used to resolve {@code <img>} pixels
     * @param sidePx the bitmap edge in pixels (clamped to a sane minimum)
     * @return the rendered slide, never null
     */
    static BufferedImage render(SlideModel slide, PresentoireModel doc, int sidePx) {
        int side = Math.max(16, sidePx);
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            // The slide surface: a white page, like any document viewer's page.
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, side, side);
            if (slide != null) {
                for (SlideElement element : slide.elements()) {
                    drawElement(g, element, doc, side);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void drawElement(Graphics2D g, SlideElement element,
            PresentoireModel doc, int side) {
        if (element instanceof SlideElement.Text text) {
            drawLines(g, text.argb(), text.lines(), element, side, false);
        } else if (element instanceof SlideElement.ListElement list) {
            drawLines(g, list.argb(), list.items().toArray(new String[0]), element, side, true);
        } else if (element instanceof SlideElement.Image image) {
            drawImage(g, image, doc, side);
        }
    }

    /**
     * Draws a text or bullet-list block: one line per band of {@code height/count},
     * stacked downward from the anchor, each line shrunk to fit its box width.
     * Bullet lists prefix every line with a filled dot in the same colour.
     */
    private static void drawLines(Graphics2D g, int argb, String[] lines,
            SlideElement element, int side, boolean bullets) {
        int count = Math.max(1, lines.length);
        float perLine = element.height() / count;
        Color color = new Color(argb, true);
        for (int idx = 0; idx < count; idx++) {
            float boxBottom = element.y() - perLine * idx;
            float boxTop = boxBottom + perLine;
            int pxTop = Math.round(mapY(boxTop, side));
            int pxHeight = Math.round(scale(perLine, side));
            if (pxHeight <= 0) {
                continue;
            }
            int pxLeft = Math.round(mapX(element.x(), side));
            int pxRight = pxLeft + Math.round(scale(element.width(), side));
            String line = (idx < lines.length) ? lines[idx] : "";

            int textLeft = pxLeft;
            if (bullets && !line.isEmpty()) {
                int diameter = Math.max(3, Math.round(pxHeight * 0.24f));
                int inset = Math.round(pxHeight * 0.12f);
                int bulletX = pxLeft + inset;
                int bulletY = pxTop + (pxHeight - diameter) / 2;
                g.setColor(color);
                g.fillOval(bulletX, bulletY, diameter, diameter);
                textLeft = bulletX + diameter + inset;
            }

            int available = Math.max(8, pxRight - textLeft);
            int fontSize = fitFontSize(g, line, pxHeight, available);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
            g.setColor(color);
            FontMetrics fm = g.getFontMetrics();
            int baseline = pxTop + (pxHeight - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(line, textLeft, baseline);
        }
    }

    /** The largest sans-serif point size (capped by the line box) that fits. */
    private static int fitFontSize(Graphics2D g, String line, int pxHeight, int available) {
        int size = Math.max(4, Math.round(pxHeight * 0.72f));
        if (line == null || line.isEmpty()) {
            return size;
        }
        while (size > 4) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size));
            if (g.getFontMetrics().stringWidth(line) <= available) {
                break;
            }
            size--;
        }
        return size;
    }

    /** Draws an {@code <img>} centred on its anchor, scaled to its box. */
    private static void drawImage(Graphics2D g, SlideElement.Image image,
            PresentoireModel doc, int side) {
        BufferedImage picture = doc.loadImage(image.src());
        if (picture == null) {
            return;
        }
        float left = image.x() - image.width() / 2f;
        float top = image.y() + image.height() / 2f;
        int pxLeft = Math.round(mapX(left, side));
        int pxTop = Math.round(mapY(top, side));
        int pxWidth = Math.max(1, Math.round(scale(image.width(), side)));
        int pxHeight = Math.max(1, Math.round(scale(image.height(), side)));
        g.drawImage(picture, pxLeft, pxTop, pxWidth, pxHeight, null);
    }

    /** Slide-x (right positive) to pixel-x. */
    private static float mapX(float slideX, int side) {
        return (slideX + PresentoireModel.SLIDE_HALF) / SLIDE_SPAN * side;
    }

    /** Slide-y (up positive) to pixel-y (down positive). */
    private static float mapY(float slideY, int side) {
        return (PresentoireModel.SLIDE_HALF - slideY) / SLIDE_SPAN * side;
    }

    /** A slide-unit length to pixels. */
    private static float scale(float units, int side) {
        return units / SLIDE_SPAN * side;
    }
}
