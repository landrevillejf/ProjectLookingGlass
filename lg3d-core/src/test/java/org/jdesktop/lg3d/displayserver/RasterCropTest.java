/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link RasterCrop}, the pure image arithmetic the 3D
 * {@link ScreenCaptureBehavior} uses to honour a selection region. It builds no
 * scene-graph object, so it is asserted directly against a
 * {@link BufferedImage} standing in for the read-back raster.
 */
class RasterCropTest {

    private static BufferedImage source() {
        BufferedImage image = new BufferedImage(4, 4,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                image.setRGB(x, y, pixel(x, y));
            }
        }
        return image;
    }

    @Test
    @DisplayName("crop keeps just the requested pixels")
    void cropPixels() {
        BufferedImage cropped = RasterCrop.crop(source(),
                new Rectangle(1, 1, 2, 2));
        assertNotNull(cropped);
        assertEquals(2, cropped.getWidth());
        assertEquals(2, cropped.getHeight());
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                assertEquals(pixel(x + 1, y + 1), cropped.getRGB(x, y));
            }
        }
    }

    @Test
    @DisplayName("crop clamps a region hanging off the raster")
    void cropClamps() {
        BufferedImage cropped = RasterCrop.crop(source(),
                new Rectangle(3, 3, 10, 10));
        assertNotNull(cropped);
        assertEquals(1, cropped.getWidth());
        assertEquals(1, cropped.getHeight());
        assertEquals(pixel(3, 3), cropped.getRGB(0, 0));
    }

    @Test
    @DisplayName("crop of the whole bounds is the whole image")
    void cropWhole() {
        BufferedImage cropped = RasterCrop.crop(source(),
                new Rectangle(0, 0, 4, 4));
        assertEquals(4, cropped.getWidth());
        assertEquals(4, cropped.getHeight());
    }

    @Test
    @DisplayName("crop returns null for null inputs or a disjoint region")
    void cropRejects() {
        assertNull(RasterCrop.crop(null, new Rectangle(0, 0, 2, 2)));
        assertNull(RasterCrop.crop(source(), null));
        assertNull(RasterCrop.crop(source(), new Rectangle(10, 10, 2, 2)));
        assertNull(RasterCrop.crop(source(), new Rectangle(0, 0, 0, 0)));
    }

    private static int pixel(int x, int y) {
        return (0xFF << 24) | (x << 16) | (y << 8) | (x + y);
    }
}
