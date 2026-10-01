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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link ToolEngine} drawing primitives: fill paints every pixel,
 * shapes touch the pixels they cross and leave the rest alone, and a null image
 * is a safe no-op. {@link BufferedImage} works headless, so this runs in CI.
 */
class ToolEngineTest {

    private static BufferedImage blank(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    @Test
    @DisplayName("fill paints every pixel the given colour")
    void fillCoversAll() {
        BufferedImage img = blank(5, 5);
        ToolEngine.fill(img, Color.GREEN);
        assertEquals(Color.GREEN.getRGB(), img.getRGB(0, 0));
        assertEquals(Color.GREEN.getRGB(), img.getRGB(4, 4));
    }

    @Test
    @DisplayName("a filled rectangle covers its interior")
    void filledRect() {
        BufferedImage img = blank(10, 10);
        ToolEngine.drawRect(img, Color.RED, 2, 2, 4, 4, 1f, true);
        assertEquals(Color.RED.getRGB(), img.getRGB(3, 3));
    }

    @Test
    @DisplayName("a normalised rectangle paints the same for reversed corners")
    void rectNormalises() {
        BufferedImage a = blank(10, 10);
        BufferedImage b = blank(10, 10);
        ToolEngine.drawRect(a, Color.BLUE, 2, 2, 5, 5, 1f, true);
        ToolEngine.drawRect(b, Color.BLUE, 7, 7, -5, -5, 1f, true);
        assertEquals(a.getRGB(4, 4), b.getRGB(4, 4));
    }

    @Test
    @DisplayName("a filled ellipse covers its centre but not the far corner")
    void ellipseCentre() {
        BufferedImage img = blank(20, 20);
        ToolEngine.drawEllipse(img, Color.RED, 2, 2, 10, 10, 1f, true);
        assertEquals(Color.RED.getRGB(), img.getRGB(7, 7));
        assertEquals(0, img.getRGB(19, 19), "outside the ellipse stays clear");
    }

    @Test
    @DisplayName("a line touches an endpoint")
    void lineTouchesEndpoint() {
        BufferedImage img = blank(10, 10);
        ToolEngine.drawLine(img, Color.BLACK, 1, 1, 8, 8, 3f);
        assertNotEquals(0, img.getRGB(1, 1) & 0xff000000, "the start pixel is painted");
    }

    @Test
    @DisplayName("erase clears pixels back to transparent")
    void eraseClears() {
        BufferedImage img = blank(6, 6);
        ToolEngine.fill(img, Color.RED);
        ToolEngine.erase(img, 0, 3, 5, 3, 3f);
        assertEquals(0, img.getRGB(3, 3) & 0xff000000, "erased pixels are transparent");
    }

    @Test
    @DisplayName("drawText paints some glyph pixels")
    void textPaints() {
        BufferedImage img = blank(60, 30);
        ToolEngine.drawText(img, Color.BLACK, "Hi", 4, 20, 16f);
        boolean anyPainted = false;
        for (int y = 0; y < 30 && !anyPainted; y++) {
            for (int x = 0; x < 60; x++) {
                if ((img.getRGB(x, y) & 0xff000000) != 0) {
                    anyPainted = true;
                    break;
                }
            }
        }
        assertEquals(true, anyPainted);
    }

    @Test
    @DisplayName("a null image is a safe no-op for every primitive")
    void nullImageIsSafe() {
        ToolEngine.fill(null, Color.RED);
        ToolEngine.drawLine(null, Color.RED, 0, 0, 1, 1, 1f);
        ToolEngine.drawRect(null, Color.RED, 0, 0, 1, 1, 1f, true);
        ToolEngine.drawEllipse(null, Color.RED, 0, 0, 1, 1, 1f, false);
        ToolEngine.erase(null, 0, 0, 1, 1, 1f);
        ToolEngine.drawText(null, Color.RED, "x", 0, 0, 12f);
    }
}
