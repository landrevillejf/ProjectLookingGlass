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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import javax.swing.JPanel;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link DesktopTransparency}'s pure conversions and its paint cache.
 *
 * <p>The engine deliberately keeps the two apart: painting reads only the
 * in-memory cache, and the cache is filled from {@link DesktopConfig} by
 * {@link DesktopTransparency#refresh()} on the EDT. So these tests can assert the
 * compositing maths headless without touching the preference store, and each one
 * restores the cache afterwards - the singleton is shared with every other suite
 * in the JVM, and a leaked faded cache would silently change how the rest of the
 * desktop paints.</p>
 */
class DesktopTransparencyTest {

    @AfterEach
    void restoreSolidDefaults() {
        for (TransparencySurface surface : TransparencySurface.values()) {
            DesktopTransparency.setOpacityFor(surface,
                    DesktopConfig.DEFAULT_SURFACE_OPACITY);
        }
    }

    @Test
    @DisplayName("opacity 100 is a solid blit and lower percent fades linearly")
    void alphaForIsLinear() {
        assertEquals(1f, DesktopTransparency.alphaFor(100), 0f);
        assertEquals(0.5f, DesktopTransparency.alphaFor(50), 0f);
        assertEquals(0.25f, DesktopTransparency.alphaFor(25), 0f);
    }

    @Test
    @DisplayName("an out-of-range opacity clamps instead of painting nonsense")
    void alphaForClamps() {
        // A hand-edited preference must not turn into a negative or over-strong
        // composite; the nearer bound is what the user meant.
        assertEquals(1f, DesktopTransparency.alphaFor(250), 0f);
        assertEquals(0f, DesktopTransparency.alphaFor(-20), 0f);
    }

    @Test
    @DisplayName("only the solid ceiling short-circuits the compositing path")
    void isSolidOnlyAtTheCeiling() {
        assertTrue(DesktopTransparency.isSolid(100));
        assertTrue(DesktopTransparency.isSolid(150), "clamped input stays solid");
        assertFalse(DesktopTransparency.isSolid(99));
        assertFalse(DesktopTransparency.isSolid(0));
    }

    @Test
    @DisplayName("fade scales alpha and leaves the colour itself alone")
    void fadeKeepsRgb() {
        Color half = DesktopTransparency.fade(Color.RED, 0.5f);
        assertEquals(255, half.getRed());
        assertEquals(0, half.getGreen());
        assertEquals(0, half.getBlue());
        assertEquals(128, half.getAlpha(), 1);
    }

    @Test
    @DisplayName("fade keeps a colour's own alpha as its ceiling")
    void fadeScalesExistingAlpha() {
        Color translucent = new Color(10, 20, 30, 100);
        assertEquals(50, DesktopTransparency.fade(translucent, 0.5f).getAlpha(), 1);
        assertEquals(100, DesktopTransparency.fade(translucent, 1f).getAlpha());
    }

    @Test
    @DisplayName("fade passes null through so paint hooks can chain it")
    void fadeNullPassesThrough() {
        assertNull(DesktopTransparency.fade(null, 0.5f));
    }

    @Test
    @DisplayName("the cache starts solid, so an un-refreshed engine never fades")
    void defaultsAreSolid() {
        // Headless Gradle tests do not isolate java.util.prefs; the paint cache
        // existing (rather than a preference read in the paint path) is what keeps
        // a 3D session or a test JVM from touching the store while drawing.
        for (TransparencySurface surface : TransparencySurface.values()) {
            assertTrue(DesktopTransparency.isSolid(
                    DesktopTransparency.opacityOf(surface)));
            assertEquals(1f, DesktopTransparency.alphaOf(surface), 0f);
        }
        assertTrue(DesktopTransparency.allSolid());
    }

    @Test
    @DisplayName("fading one surface leaves the other three solid")
    void surfacesAreIndependent() {
        DesktopTransparency.setOpacityFor(TransparencySurface.WINDOW, 60);
        assertEquals(60,
                DesktopTransparency.opacityOf(TransparencySurface.WINDOW));
        assertFalse(DesktopTransparency.allSolid());
        for (TransparencySurface surface : TransparencySurface.values()) {
            if (surface != TransparencySurface.WINDOW) {
                assertTrue(DesktopTransparency.isSolid(
                        DesktopTransparency.opacityOf(surface)), surface + " unchanged");
            }
        }
    }

    @Test
    @DisplayName("a programmed opacity is clamped to the readable floor")
    void setOpacityForClamps() {
        DesktopTransparency.setOpacityFor(TransparencySurface.TASKBAR, 0);
        assertEquals(DesktopConfig.MIN_SURFACE_OPACITY,
                DesktopTransparency.opacityOf(TransparencySurface.TASKBAR));
        DesktopTransparency.setOpacityFor(TransparencySurface.TASKBAR, 500);
        assertEquals(DesktopConfig.MAX_SURFACE_OPACITY,
                DesktopTransparency.opacityOf(TransparencySurface.TASKBAR));
    }

    @Test
    @DisplayName("a faded component is routed for compositing, a plain one is not")
    void isFadedFollowsTheMarkerAndTheSetting() {
        Desktop2DWindow window = new Desktop2DWindow("App", null,
                new JPanel(), "App", null, null, null);
        assertEquals(TransparencySurface.WINDOW, window.transparencySurface());
        assertFalse(DesktopTransparency.isFaded(window),
                "solid is the stock paint path");
        DesktopTransparency.setOpacityFor(TransparencySurface.WINDOW, 40);
        assertTrue(DesktopTransparency.isFaded(window));
        assertFalse(DesktopTransparency.isFaded(new JPanel()),
                "an ordinary component is never faded");
    }
}
