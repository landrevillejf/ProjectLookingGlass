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
package org.jdesktop.lg3d.apps.screencapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the capture engine's pure rectangle arithmetic: drag
 * normalisation, union bounds, clamping and image cropping. None of it creates a
 * peer, so it runs under {@code java.awt.headless}.
 */
class CaptureRegionsTest {

    @Test
    @DisplayName("normalise turns any drag direction into a positive rect")
    void normaliseAllDirections() {
        // Top-left to bottom-right.
        assertEquals(new Rectangle(10, 20, 30, 40),
                CaptureRegions.normalise(10, 20, 40, 60));
        // Bottom-right to top-left.
        assertEquals(new Rectangle(10, 20, 30, 40),
                CaptureRegions.normalise(40, 60, 10, 20));
        // Top-right to bottom-left.
        assertEquals(new Rectangle(10, 20, 30, 40),
                CaptureRegions.normalise(40, 20, 10, 60));
        // Bottom-left to top-right.
        assertEquals(new Rectangle(10, 20, 30, 40),
                CaptureRegions.normalise(10, 60, 40, 20));
    }

    @Test
    @DisplayName("normalise of a zero-length drag is empty")
    void normaliseDegenerate() {
        Rectangle r = CaptureRegions.normalise(5, 5, 5, 5);
        assertEquals(0, r.width);
        assertEquals(0, r.height);
        assertEquals(5, r.x);
    }

    @Test
    @DisplayName("union bounds every rect and ignores nulls and empties")
    void unionFolds() {
        List<Rectangle> rects = new ArrayList<>(Arrays.asList(
                new Rectangle(0, 0, 10, 10),
                null,
                new Rectangle(20, 30, 5, 5),
                new Rectangle(0, 0, 0, 0)));
        assertEquals(new Rectangle(0, 0, 25, 35), CaptureRegions.union(rects));
    }

    @Test
    @DisplayName("union of nothing is an empty rect, never null")
    void unionEmpty() {
        assertEquals(new Rectangle(), CaptureRegions.union(null));
        assertEquals(new Rectangle(), CaptureRegions.union(new ArrayList<>()));
    }

    @Test
    @DisplayName("clamp keeps the overlap and rejects a disjoint region")
    void clamp() {
        Rectangle bounds = new Rectangle(0, 0, 100, 100);
        // Fully inside.
        assertEquals(new Rectangle(10, 10, 20, 20),
                CaptureRegions.clamp(new Rectangle(10, 10, 20, 20), bounds));
        // Partly outside -> clipped to the overlap.
        assertEquals(new Rectangle(90, 90, 10, 10),
                CaptureRegions.clamp(new Rectangle(90, 90, 50, 50), bounds));
        // Disjoint -> empty.
        assertTrue(CaptureRegions.clamp(new Rectangle(200, 200, 5, 5), bounds)
                .isEmpty());
        // Null arguments -> empty, never an NPE.
        assertTrue(CaptureRegions.clamp(null, bounds).isEmpty());
        assertTrue(CaptureRegions.clamp(new Rectangle(0, 0, 5, 5), null)
                .isEmpty());
    }

    @Test
    @DisplayName("crop keeps just the requested pixels")
    void cropPixels() {
        BufferedImage image = new BufferedImage(4, 4,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                image.setRGB(x, y, pixel(x, y));
            }
        }
        BufferedImage cropped = CaptureRegions.crop(image,
                new Rectangle(1, 1, 2, 2));
        assertEquals(2, cropped.getWidth());
        assertEquals(2, cropped.getHeight());
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                assertEquals(pixel(x + 1, y + 1), cropped.getRGB(x, y));
            }
        }
    }

    @Test
    @DisplayName("crop clamps a region hanging off the image")
    void cropClamps() {
        BufferedImage image = new BufferedImage(4, 4,
                BufferedImage.TYPE_INT_RGB);
        BufferedImage cropped = CaptureRegions.crop(image,
                new Rectangle(2, 2, 10, 10));
        assertEquals(2, cropped.getWidth());
        assertEquals(2, cropped.getHeight());
    }

    @Test
    @DisplayName("crop returns null for a null image or a disjoint region")
    void cropRejects() {
        BufferedImage image = new BufferedImage(4, 4,
                BufferedImage.TYPE_INT_RGB);
        assertNull(CaptureRegions.crop(null, new Rectangle(0, 0, 2, 2)));
        assertNull(CaptureRegions.crop(image, new Rectangle(10, 10, 2, 2)));
        assertNull(CaptureRegions.crop(image, null));
    }

    private static int pixel(int x, int y) {
        return (0xFF << 24) | (x << 16) | (y << 8) | (x * y);
    }
}
