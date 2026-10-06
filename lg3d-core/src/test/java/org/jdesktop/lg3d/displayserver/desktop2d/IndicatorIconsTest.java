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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import javax.swing.Icon;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link IndicatorIcons}' procedural volume/network glyphs. Drawing into
 * a {@link BufferedImage} is fully headless, so every factory is asserted
 * directly: it returns a transparent ARGB image of the requested (clamped) edge
 * with actual painted pixels, and never throws on a null level or link kind.
 */
class IndicatorIconsTest {

    /** Counts pixels that are not fully transparent. */
    private static int paintedPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xff) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    @DisplayName("a volume image is a square ARGB bitmap with painted pixels")
    void volumeImage() {
        BufferedImage image = IndicatorIcons.volumeImage(50, false, IndicatorIcons.SIZE);
        assertNotNull(image);
        assertEquals(IndicatorIcons.SIZE, image.getWidth());
        assertEquals(IndicatorIcons.SIZE, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        assertTrue(paintedPixels(image) > 0, "the speaker cone is drawn");
    }

    @Test
    @DisplayName("mute, a level and no-master each draw a distinct non-empty glyph")
    void volumeStates() {
        assertTrue(paintedPixels(IndicatorIcons.volumeImage(80, false, 16)) > 0);
        assertTrue(paintedPixels(IndicatorIcons.volumeImage(80, true, 16)) > 0);
        assertTrue(paintedPixels(IndicatorIcons.volumeImage(null, false, 16)) > 0,
                "a null level still draws a greyed speaker");
    }

    @Test
    @DisplayName("a louder level paints more sound-wave pixels than a quiet one")
    void volumeWavesScaleWithLevel() {
        int quiet = paintedPixels(IndicatorIcons.volumeImage(10, false, 32));
        int loud = paintedPixels(IndicatorIcons.volumeImage(100, false, 32));
        assertTrue(loud > quiet, "three waves cover more pixels than one");
    }

    @Test
    @DisplayName("the icon edge is clamped into a drawable range")
    void sizeIsClamped() {
        assertEquals(8, IndicatorIcons.volumeImage(50, false, 1).getWidth(),
                "a too-small size clamps up to the minimum edge");
        assertEquals(128, IndicatorIcons.networkImage(Kind.WIFI, 9999).getWidth(),
                "a too-large size clamps down to the maximum edge");
    }

    @Test
    @DisplayName("every network kind draws a non-empty glyph")
    void networkKinds() {
        for (Kind kind : new Kind[] {Kind.WIFI, Kind.ETHERNET, Kind.OFFLINE}) {
            BufferedImage image = IndicatorIcons.networkImage(kind, 16);
            assertNotNull(image);
            assertEquals(16, image.getWidth());
            assertTrue(paintedPixels(image) > 0, kind + " draws a link glyph");
        }
    }

    @Test
    @DisplayName("a null link kind is treated as offline and still draws")
    void nullKindIsOffline() {
        BufferedImage image = IndicatorIcons.networkImage(null, 16);
        assertNotNull(image);
        assertTrue(paintedPixels(image) > 0);
    }

    @Test
    @DisplayName("the Icon wrappers carry the same edge as the images")
    void iconWrappers() {
        Icon volume = IndicatorIcons.volumeIcon(40, false, 24);
        Icon network = IndicatorIcons.networkIcon(Kind.WIFI, 24);
        assertNotNull(volume);
        assertNotNull(network);
        assertEquals(24, volume.getIconWidth());
        assertEquals(24, network.getIconHeight());
    }
}
