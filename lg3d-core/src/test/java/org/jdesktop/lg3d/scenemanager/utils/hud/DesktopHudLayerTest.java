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
package org.jdesktop.lg3d.scenemanager.utils.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jogamp.vecmath.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the pure HUD geometry: the perspective-compensated
 * front-pose factor and the fractional placement / on-screen clamp math. These
 * statics carry no scene-graph or canvas dependency, so they are verified without
 * a live 3D desktop; the layer's rendering is probe-verified separately.
 */
class DesktopHudLayerTest {

    private static final float EPS = 1e-5f;

    @Test
    void frontPoseZLiftsToFractionOfEyeDistance() {
        assertEquals(4.0f, DesktopHudLayer.frontPoseZ(10f, 0.4f), EPS);
        assertEquals(2.5f, DesktopHudLayer.frontPoseZ(10f, 0.25f), EPS);
    }

    @Test
    void frontPoseZRejectsDegenerateInputs() {
        assertTrue(Float.isNaN(DesktopHudLayer.frontPoseZ(0f, 0.4f)), "eyeZ<=0");
        assertTrue(Float.isNaN(DesktopHudLayer.frontPoseZ(-5f, 0.4f)), "eyeZ<0");
        assertTrue(Float.isNaN(DesktopHudLayer.frontPoseZ(10f, 0f)), "fraction<=0");
        assertTrue(Float.isNaN(DesktopHudLayer.frontPoseZ(10f, -0.1f)), "fraction<0");
        assertTrue(Float.isNaN(DesktopHudLayer.frontPoseZ(10f, 1f)), "fraction>=1");
        assertTrue(Float.isNaN(DesktopHudLayer.frontPoseZ(10f, 1.5f)), "fraction>1");
    }

    @Test
    void frontPoseScaleCancelsPerspectiveMagnification() {
        // r = (eyeZ - eyeZ*fraction) / eyeZ = 1 - fraction
        assertEquals(0.6f, DesktopHudLayer.frontPoseScale(10f, 0.4f), EPS);
        assertEquals(0.75f, DesktopHudLayer.frontPoseScale(10f, 0.25f), EPS);
    }

    @Test
    void frontPoseScaleRejectsInvalidPlanes() {
        assertTrue(Float.isNaN(DesktopHudLayer.frontPoseScale(0f, 0.4f)), "bad eyeZ");
        // eyeZ - z must exceed 0.01; z=0.999*1 => gap 0.001
        assertTrue(Float.isNaN(DesktopHudLayer.frontPoseScale(1f, 0.999f)), "too close to eye");
    }

    @Test
    void fractionalToWorldMapsCentreAndCorners() {
        Vector3f out = new Vector3f();

        DesktopHudLayer.fractionalToWorld(0.5f, 0.5f, 2f, 2f, out);
        assertEquals(0f, out.x, EPS);
        assertEquals(0f, out.y, EPS);
        assertEquals(0f, out.z, EPS);

        DesktopHudLayer.fractionalToWorld(0f, 0f, 2f, 2f, out); // top-left
        assertEquals(-1f, out.x, EPS);
        assertEquals(1f, out.y, EPS);

        DesktopHudLayer.fractionalToWorld(1f, 1f, 2f, 2f, out); // bottom-right
        assertEquals(1f, out.x, EPS);
        assertEquals(-1f, out.y, EPS);
    }

    @Test
    void fractionalToWorldClampsOutOfRange() {
        Vector3f out = new Vector3f();
        DesktopHudLayer.fractionalToWorld(-1f, 2f, 2f, 2f, out);
        assertEquals(-1f, out.x, EPS); // clamped to fx=0 (left edge)
        assertEquals(-1f, out.y, EPS);  // clamped to fy=1 (bottom edge)
    }

    @Test
    void clampTranslationKeepsBoxOnScreen() {
        Vector3f out = new Vector3f();
        // screen 2x2, box half-extent 0.25, taskbar margin 0.
        DesktopHudLayer.clampTranslation(5f, -5f, 0.25f, 0.25f, 2f, 2f, 0f, out);
        assertEquals(0.75f, out.x, EPS);  // right edge
        assertEquals(-0.75f, out.y, EPS); // bottom edge
    }

    @Test
    void clampTranslationCentersOversizedBox() {
        Vector3f out = new Vector3f();
        // half-extent wider than the screen => left > right => centre (0)
        DesktopHudLayer.clampTranslation(3f, 0f, 2f, 0.1f, 2f, 2f, 0f, out);
        assertEquals(0f, out.x, EPS);
    }
}
