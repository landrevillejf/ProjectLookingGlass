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

import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure geometry math the 2D taskbar uses to honour the desktop
 * configuration (thickness from {@code barScale}, chrome-icon size from
 * {@code iconScale}). These helpers are headless-safe; the Swing layout that
 * consumes them is exercised at runtime on a live 2D desktop.
 */
class Desktop2DTaskbarConfigTest {

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
}
