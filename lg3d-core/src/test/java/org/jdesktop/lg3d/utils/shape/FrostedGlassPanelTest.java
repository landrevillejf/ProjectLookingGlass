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
package org.jdesktop.lg3d.utils.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the Java-3D-free seams of {@link FrostedGlassPanel}: the quad layout and
 * the corner-radius clamp. The rounded silhouette, anti-aliasing and frost band
 * themselves are evaluated per fragment on the GPU, so they are verified by an
 * offscreen probe rather than here; what is unit-testable is that the quad spans
 * exactly the panel rect (the SDF does the rounding) and that a requested corner
 * radius is clamped into the range the rounded-rect SDF is valid for.
 */
class FrostedGlassPanelTest {

    private static final float EPS = 1e-6f;

    @Test
    @DisplayName("the quad is the full panel rect at zShift; rounding is done per fragment")
    void layoutIsFullRectAtZShift() {
        // width 2 (half 1), height 4 (half 2) at z=-0.5; CCW from the -x/-y corner.
        float[] coords = FrostedGlassPanel.layoutCoords(2.0f, 4.0f, -0.5f);
        assertArrayEquals(new float[] {
            -1.0f, -2.0f, -0.5f,
             1.0f, -2.0f, -0.5f,
             1.0f,  2.0f, -0.5f,
            -1.0f,  2.0f, -0.5f,
        }, coords, EPS);
    }

    @Test
    @DisplayName("a radius within range passes through unchanged")
    void clampKeepsValidRadius() {
        assertEquals(0.5f, FrostedGlassPanel.clampRadius(2.0f, 4.0f, 0.5f), EPS);
        // exactly half the smaller side is the stadium limit and stays valid
        assertEquals(0.3f, FrostedGlassPanel.clampRadius(0.6f, 0.6f, 0.3f), EPS);
    }

    @Test
    @DisplayName("a radius larger than half the smaller side is capped to the stadium limit")
    void clampCapsOversizedRadius() {
        // min(2,4)/2 = 1, so 3 collapses to 1 (the SDF would otherwise invert)
        assertEquals(1.0f, FrostedGlassPanel.clampRadius(2.0f, 4.0f, 3.0f), EPS);
        assertEquals(0.5f, FrostedGlassPanel.clampRadius(3.0f, 1.0f, 9.0f), EPS);
    }

    @Test
    @DisplayName("a negative radius is clamped to zero (square corners)")
    void clampRejectsNegativeRadius() {
        assertEquals(0.0f, FrostedGlassPanel.clampRadius(2.0f, 4.0f, -1.0f), EPS);
    }
}
