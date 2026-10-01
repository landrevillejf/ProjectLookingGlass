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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link FilterEngine} pixel operations: each returns a new image of
 * the same size, never mutates its source, and produces the expected colour
 * transform. {@link BufferedImage} works headless, so this runs in CI.
 */
class FilterEngineTest {

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, c.getRGB());
            }
        }
        return img;
    }

    @Test
    @DisplayName("grayscale collapses a colour to equal RGB channels")
    void grayscale() {
        BufferedImage out = FilterEngine.grayscale(solid(4, 4, Color.RED));
        int rgb = out.getRGB(2, 2);
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        assertEquals(r, g);
        assertEquals(g, b);
    }

    @Test
    @DisplayName("invert is its own inverse")
    void invertIsInvolution() {
        BufferedImage src = solid(3, 3, new Color(30, 100, 200));
        BufferedImage once = FilterEngine.invert(src);
        BufferedImage twice = FilterEngine.invert(once);
        assertEquals(src.getRGB(1, 1) & 0xffffff, twice.getRGB(1, 1) & 0xffffff);
        assertNotEquals(src.getRGB(1, 1) & 0xffffff, once.getRGB(1, 1) & 0xffffff);
    }

    @Test
    @DisplayName("brightness of factor 1 leaves the image unchanged")
    void brightnessIdentity() {
        BufferedImage src = solid(3, 3, new Color(10, 20, 30));
        assertEquals(src.getRGB(0, 0), FilterEngine.brightness(src, 1f).getRGB(0, 0));
    }

    @Test
    @DisplayName("brightness of factor 0 blacks out the RGB channels")
    void brightnessZero() {
        BufferedImage out = FilterEngine.brightness(solid(2, 2, Color.WHITE), 0f);
        assertEquals(0, out.getRGB(0, 0) & 0xffffff);
    }

    @Test
    @DisplayName("threshold binarises to pure black or white")
    void threshold() {
        BufferedImage out = FilterEngine.threshold(solid(2, 2, new Color(200, 200, 200)), 128);
        assertEquals(0xffffff, out.getRGB(0, 0) & 0xffffff);
        BufferedImage dark = FilterEngine.threshold(solid(2, 2, new Color(10, 10, 10)), 128);
        assertEquals(0, dark.getRGB(0, 0) & 0xffffff);
    }

    @Test
    @DisplayName("blur, sharpen, edges and sepia keep the size and do not mutate")
    void shapePreserving() {
        BufferedImage src = solid(6, 6, new Color(40, 80, 120));
        int before = src.getRGB(3, 3);
        for (BufferedImage out : new BufferedImage[] {
            FilterEngine.blur(src, 2),
            FilterEngine.sharpen(src),
            FilterEngine.edges(src),
            FilterEngine.sepia(src) }) {
            assertEquals(6, out.getWidth());
            assertEquals(6, out.getHeight());
        }
        assertEquals(before, src.getRGB(3, 3), "the source must not be mutated");
    }

    @Test
    @DisplayName("sepia warms a mid grey toward red/yellow")
    void sepiaTone() {
        BufferedImage out = FilterEngine.sepia(solid(2, 2, Color.GRAY));
        int rgb = out.getRGB(0, 0);
        int r = (rgb >> 16) & 0xff;
        int b = rgb & 0xff;
        assertTrue(r >= b, "sepia is warmer (red >= blue)");
    }

    @Test
    @DisplayName("a null source is rejected")
    void nullRejected() {
        assertThrows(IllegalArgumentException.class, () -> FilterEngine.grayscale(null));
    }
}
