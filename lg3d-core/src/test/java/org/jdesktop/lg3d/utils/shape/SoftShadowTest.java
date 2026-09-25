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
 * Covers {@link SoftShadow#layoutCoords}: the pure geometry seam that decides
 * where the shadow quad's four corners sit. The quad must span the window rect
 * ({@code +-width/2, +-height/2}) grown outward by each side's own penumbra, all
 * at {@code zShift}, so the SDF fragment shader's falloff has exactly the right
 * room on every side. Headless-testable because it touches no Java 3D objects.
 */
class SoftShadowTest {

    private static final float EPS = 1e-6f;

    @Test
    @DisplayName("asymmetric margins grow each side of the window rect by its own penumbra")
    void layoutGrowsRectByPerSideMargins() {
        // width 2 (half 1), height 4 (half 2); N=0.1 E=0.2 S=0.3 W=0.4 at z=-0.5.
        // Corners are emitted counter-clockwise from the -x/-y (south-west) one.
        float[] coords = SoftShadow.layoutCoords(
            2.0f, 4.0f, 0.1f, 0.2f, 0.3f, 0.4f, -0.5f);
        assertArrayEquals(new float[] {
            -1.0f - 0.4f, -2.0f - 0.3f, -0.5f,   // SW: -hw-west, -hh-south
             1.0f + 0.2f, -2.0f - 0.3f, -0.5f,   // SE: +hw+east, -hh-south
             1.0f + 0.2f,  2.0f + 0.1f, -0.5f,   // NE: +hw+east, +hh+north
            -1.0f - 0.4f,  2.0f + 0.1f, -0.5f,   // NW: -hw-west, +hh+north
        }, coords, EPS);
    }

    @Test
    @DisplayName("zero penumbra collapses the quad onto the window rect, centred on the origin")
    void layoutZeroMarginsIsWindowRect() {
        float[] coords = SoftShadow.layoutCoords(
            1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        assertArrayEquals(new float[] {
            -0.5f, -0.5f, 0.0f,
             0.5f, -0.5f, 0.0f,
             0.5f,  0.5f, 0.0f,
            -0.5f,  0.5f, 0.0f,
        }, coords, EPS);
    }

    @Test
    @DisplayName("zShift is applied to all four corners so the quad parks behind the window")
    void layoutAppliesZShiftToEveryCorner() {
        float[] coords = SoftShadow.layoutCoords(
            0.6f, 0.6f, 0.15f, 0.15f, 0.15f, 0.15f, -0.005f);
        assertEquals(12, coords.length);
        for (int corner = 0; corner < 4; corner++) {
            assertEquals(-0.005f, coords[corner * 3 + 2], EPS,
                "corner " + corner + " must sit at zShift");
        }
    }
}
