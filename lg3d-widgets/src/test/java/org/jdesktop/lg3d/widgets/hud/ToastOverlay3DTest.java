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
package org.jdesktop.lg3d.widgets.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link ToastOverlay3D#cardPosition} - the pure
 * bottom-right stacking layout. The scene origin is the screen centre with +x
 * right and +y up, so the newest card (index 0) hugs the bottom-right inset by
 * the margins, and each older card is lifted by {@code cardH + gap}. No live
 * desktop or SwingNode is required.
 */
class ToastOverlay3DTest {

    private static final float DELTA = 1e-5f;

    // A 2 x 1 screen, a 0.4 x 0.1 card, and the overlay's real margins.
    private static final float SCREEN_W = 2.0f;
    private static final float SCREEN_H = 1.0f;
    private static final float CARD_W = 0.4f;
    private static final float CARD_H = 0.1f;
    private static final float RIGHT = ToastOverlay3D.RIGHT_MARGIN;
    private static final float BOTTOM = ToastOverlay3D.BOTTOM_MARGIN;
    private static final float GAP = ToastOverlay3D.GAP;

    private float[] pos(int index) {
        return ToastOverlay3D.cardPosition(index, CARD_W, CARD_H,
                SCREEN_W, SCREEN_H, RIGHT, BOTTOM, GAP);
    }

    @Test
    void newestCardAnchorsBottomRight() {
        float[] p = pos(0);
        // x = screenW/2 - rightMargin - cardW/2 = 1 - 0.02 - 0.2 = 0.78
        assertEquals(0.78f, p[0], DELTA);
        // y = -screenH/2 + bottomMargin + cardH/2 = -0.5 + 0.06 + 0.05 = -0.39
        assertEquals(-0.39f, p[1], DELTA);
    }

    @Test
    void olderCardsStackUpwardByCardHeightPlusGap() {
        float[] first = pos(0);
        float[] second = pos(1);
        float[] third = pos(2);

        float step = CARD_H + GAP;
        assertEquals(first[1] + step, second[1], DELTA);
        assertEquals(second[1] + step, third[1], DELTA);
    }

    @Test
    void horizontalPositionIsIndependentOfStackIndex() {
        assertEquals(pos(0)[0], pos(3)[0], DELTA);
        assertEquals(pos(1)[0], pos(7)[0], DELTA);
    }

    @Test
    void negativeIndexIsClampedToTheNewestSlot() {
        float[] newest = pos(0);
        float[] clamped = pos(-5);
        assertEquals(newest[0], clamped[0], DELTA);
        assertEquals(newest[1], clamped[1], DELTA);
    }
}
