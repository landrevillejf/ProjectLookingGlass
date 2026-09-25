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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link BrightnessDimmer}: the percentage-to-wash-opacity mapping and
 * the guarantee that the overlay never becomes the mouse target, so dimming the
 * desktop cannot swallow input. Headless and pure - nothing is painted.
 */
class BrightnessDimmerTest {

    @Test
    @DisplayName("opacity grows as brightness falls, and clamps at both ends")
    void alphaMapping() {
        assertEquals(0, BrightnessDimmer.alphaFor(100), "full brightness paints nothing");
        assertEquals(BrightnessDimmer.MAX_ALPHA, BrightnessDimmer.alphaFor(0));
        assertEquals(BrightnessDimmer.MAX_ALPHA / 2, BrightnessDimmer.alphaFor(50));
        assertEquals(BrightnessDimmer.MAX_ALPHA, BrightnessDimmer.alphaFor(-5));
        assertEquals(0, BrightnessDimmer.alphaFor(150));
    }

    @Test
    @DisplayName("applying a percentage shows the wash below 100 and hides it at 100")
    void setPercentControlsVisibility() {
        BrightnessDimmer dimmer = new BrightnessDimmer();
        dimmer.setPercent(40);
        assertEquals(40, dimmer.percent());
        assertEquals(true, dimmer.isVisible(), "a dim below full must be shown");
        dimmer.setPercent(100);
        assertEquals(100, dimmer.percent());
        assertFalse(dimmer.isVisible(), "full brightness removes the wash");
    }

    @Test
    @DisplayName("out-of-range percentages clamp into 0-100")
    void setPercentClamps() {
        BrightnessDimmer dimmer = new BrightnessDimmer();
        dimmer.setPercent(-20);
        assertEquals(0, dimmer.percent());
        assertEquals(true, dimmer.isVisible());
        dimmer.setPercent(250);
        assertEquals(100, dimmer.percent());
        assertFalse(dimmer.isVisible());
    }

    @Test
    @DisplayName("the overlay is never the mouse target")
    void neverInterceptsInput() {
        BrightnessDimmer dimmer = new BrightnessDimmer();
        dimmer.setPercent(10);
        assertFalse(dimmer.contains(0, 0), "dimming must not swallow desktop clicks");
        assertFalse(dimmer.contains(500, 500));
    }
}
