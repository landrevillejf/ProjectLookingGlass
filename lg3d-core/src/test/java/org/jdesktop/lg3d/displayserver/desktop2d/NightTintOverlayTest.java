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
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.jdesktop.lg3d.utils.schedule.DayNightCurve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the 2D {@link NightTintOverlay}: its blend factor is
 * clamped, it never claims input, and it paints the cool night veil whose
 * opacity tracks the factor (and nothing at all in full daylight). The overlay
 * is a lightweight {@code JComponent}, so it constructs and paints into an
 * offscreen image with no display.
 */
class NightTintOverlayTest {

    @Test
    @DisplayName("the overlay constructs headless, starts invisible and never eats input")
    void constructsAndIsClickThrough() {
        NightTintOverlay overlay = assertDoesNotThrow(NightTintOverlay::new);
        assertEquals(0.0f, overlay.factor(), 1e-6f);
        assertFalse(overlay.contains(0, 0), "the veil must never be the mouse target");
        assertFalse(overlay.contains(500, 500));
    }

    @Test
    @DisplayName("setFactor clamps to 0..1 and is reflected by factor()")
    void setFactorClamps() {
        NightTintOverlay overlay = new NightTintOverlay();
        overlay.setFactor(0.4f);
        assertEquals(0.4f, overlay.factor(), 1e-6f);
        overlay.setFactor(3.0f);
        assertEquals(1.0f, overlay.factor(), 1e-6f);
        overlay.setFactor(-2.0f);
        assertEquals(0.0f, overlay.factor(), 1e-6f);
    }

    @Test
    @DisplayName("at full night the veil paints the tint colour at full opacity")
    void paintsNightVeil() {
        NightTintOverlay overlay = new NightTintOverlay();
        overlay.setBounds(0, 0, 8, 8);
        overlay.setFactor(1.0f);

        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            overlay.paintComponent(g);
        } finally {
            g.dispose();
        }

        int[] rgb = DayNightCurve.NIGHT_TINT_RGB;
        int pixel = img.getRGB(4, 4);
        // The alpha carries through exactly; Java2D composites the translucent
        // wash in premultiplied alpha, so each RGB channel can round by one
        // against the literal tint colour.
        assertEquals(DayNightCurve.NIGHT_TINT_MAX_ALPHA, (pixel >>> 24) & 0xFF,
                "the veil is painted at full night opacity");
        assertChannelNear(rgb[0], (pixel >> 16) & 0xFF, "red");
        assertChannelNear(rgb[1], (pixel >> 8) & 0xFF, "green");
        assertChannelNear(rgb[2], pixel & 0xFF, "blue");
    }

    private static void assertChannelNear(int expected, int actual, String channel) {
        assertTrue(Math.abs(expected - actual) <= 2,
                channel + " channel should track the night tint (expected ~"
                        + expected + ", was " + actual + ")");
    }

    @Test
    @DisplayName("in full daylight the veil paints nothing")
    void paintsNothingByDay() {
        NightTintOverlay overlay = new NightTintOverlay();
        overlay.setBounds(0, 0, 8, 8);
        overlay.setFactor(0.0f);

        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            overlay.paintComponent(g);
        } finally {
            g.dispose();
        }

        assertEquals(0, img.getRGB(4, 4), "a zero factor leaves the desktop untouched");
    }
}
