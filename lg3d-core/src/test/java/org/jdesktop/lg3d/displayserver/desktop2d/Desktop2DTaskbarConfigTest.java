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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure geometry math the 2D taskbar uses to honour the desktop
 * configuration (thickness from {@code barScale}, chrome-icon size from
 * {@code iconScale}) and that its translucent backdrop paints without a peer.
 * These helpers are headless-safe; the Swing layout that consumes them is
 * exercised at runtime on a live 2D desktop.
 */
class Desktop2DTaskbarConfigTest {

    @AfterEach
    void leaveTheBarSolid() {
        for (TransparencySurface surface : TransparencySurface.values()) {
            DesktopTransparency.setOpacityFor(surface,
                    DesktopConfig.DEFAULT_SURFACE_OPACITY);
        }
    }

    @Test
    @DisplayName("clampScale keeps a user scale inside the config range")
    void clampScale() {
        assertEquals(DesktopConfig.MIN_SCALE,
                Desktop2DTaskbar.clampScale(0.0f), 1e-6f);
        assertEquals(DesktopConfig.MIN_SCALE,
                Desktop2DTaskbar.clampScale(-1.0f), 1e-6f);
        assertEquals(DesktopConfig.MAX_SCALE,
                Desktop2DTaskbar.clampScale(99.0f), 1e-6f);
        assertEquals(1.0f, Desktop2DTaskbar.clampScale(1.0f), 1e-6f);
        assertEquals(1.0f, Desktop2DTaskbar.clampScale(Float.NaN), 1e-6f,
                "a NaN scale falls back to 1.0 rather than poisoning the layout");
    }

    @Test
    @DisplayName("barHeightFor scales the base height and never drops below the floor")
    void barHeightFor() {
        assertEquals(Desktop2DTaskbar.BASE_BAR_HEIGHT_PX,
                Desktop2DTaskbar.barHeightFor(1.0f));
        assertEquals(Math.round(Desktop2DTaskbar.BASE_BAR_HEIGHT_PX
                        * DesktopConfig.MAX_SCALE),
                Desktop2DTaskbar.barHeightFor(DesktopConfig.MAX_SCALE));
        // Even the smallest clamped scale must leave a reachable bar.
        assertTrue(Desktop2DTaskbar.barHeightFor(DesktopConfig.MIN_SCALE)
                        >= Desktop2DTaskbar.MIN_BAR_HEIGHT_PX,
                "a thin bar scale still leaves at least the minimum height");
        assertTrue(Desktop2DTaskbar.barHeightFor(0.0f)
                >= Desktop2DTaskbar.MIN_BAR_HEIGHT_PX);
    }

    @Test
    @DisplayName("scaledSize scales an icon edge, clamped and never below one pixel")
    void scaledSize() {
        assertEquals(16, Desktop2DTaskbar.scaledSize(16, 1.0f));
        assertEquals(32, Desktop2DTaskbar.scaledSize(16, DesktopConfig.MAX_SCALE));
        assertEquals(Math.round(16 * DesktopConfig.MIN_SCALE),
                Desktop2DTaskbar.scaledSize(16, 0.0f),
                "a zero/negative scale is clamped to the minimum, not to zero");
        assertEquals(1, Desktop2DTaskbar.scaledSize(0, 1.0f),
                "a zero-sized edge stays at one pixel");
    }

    @Test
    @DisplayName("a tinted backdrop band drawn at full strength lets the wallpaper show through")
    void fadedBackdropBakesTheTintNotTheBlit() {
        // The taskbar's recipe (Desktop2DTaskbar.paintComponent): the bar's
        // colour is baked into the scratch band at the surface alpha and the
        // band is then drawn back 1:1 - the fade must NOT come from a
        // translucent blit, because the opaque content pane under the bar
        // would blend toward grey instead of revealing the wallpaper.
        Color bar = new Color(30, 40, 55);
        float alpha = DesktopTransparency.alphaFor(50);
        BufferedImage band = new BufferedImage(200, 40,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = band.createGraphics();
        try {
            bg.setColor(DesktopTransparency.fade(bar, alpha));
            bg.fillRect(0, 0, 200, 40);
        } finally {
            bg.dispose();
        }
        // A canvas pre-painted the way the shell looks under the bar (an
        // already-composited wallpaper strip stands in for the band's own).
        BufferedImage canvas = new BufferedImage(200, 40,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(new Color(200, 210, 220));
            g.fillRect(0, 0, 200, 40);
            g.drawImage(band, 0, 0, null);
        } finally {
            g.dispose();
        }
        int rgb = canvas.getRGB(100, 20);
        // Half bar colour over half backdrop, within premultiplied rounding.
        assertEquals(115, (rgb >> 16) & 0xFF, 2, "bar red blended over backdrop");
        assertEquals(125, (rgb >> 8) & 0xFF, 2, "bar green blended");
        assertEquals(137, rgb & 0xFF, 2, "bar blue blended");
        // And the untinted path is byte-identical to the legacy solid fill.
        assertTrue(DesktopTransparency.isSolid(
                DesktopConfig.DEFAULT_SURFACE_OPACITY),
                "at the default the bar keeps its stock opaque paint path");
    }
}
