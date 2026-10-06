/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link SwingX11InputForwarder}'s pure mapping seams — the
 * component-local&rarr;root coordinate mapping, the AWT&rarr;X button mapping and
 * the keysym&rarr;keycode resolution. These are the decisions that turn Swing
 * input on a composited window's canvas into XTest injection points, extracted so
 * they can be pinned with no live {@code gnu.x11.Display} and no XTest.
 *
 * <p>Keysym <em>selection</em> ({@code vkToKeysym}) is shared with the 3D
 * forwarder and already covered by {@code X11InputForwarderKeysymTest}.</p>
 */
class SwingX11InputForwarderMappingTest {

    @Test
    @DisplayName("a component-local pixel maps to windowOrigin + offset")
    void mapsPanelToRoot() {
        int[] out = new int[2];
        assertTrue(SwingX11InputForwarder.mapPanelToRoot(5, 7, 100, 200, out));
        assertEquals(105, out[0]);
        assertEquals(207, out[1]);
    }

    @Test
    @DisplayName("the origin alone maps a local (0,0) to the window origin")
    void mapsLocalOriginToWindowOrigin() {
        int[] out = new int[2];
        SwingX11InputForwarder.mapPanelToRoot(0, 0, 42, 84, out);
        assertEquals(42, out[0]);
        assertEquals(84, out[1]);
    }

    @Test
    @DisplayName("negative local offsets (outside the canvas) clamp to zero")
    void clampsNegativeLocalOffsets() {
        int[] out = new int[2];
        SwingX11InputForwarder.mapPanelToRoot(-3, -4, 100, 200, out);
        assertEquals(100, out[0]);
        assertEquals(200, out[1]);
    }

    @Test
    @DisplayName("AWT buttons 1/2/3 map to X buttons 1/2/3")
    void mapsStandardButtons() {
        assertEquals(1, SwingX11InputForwarder.mapAwtButton(MouseEvent.BUTTON1));
        assertEquals(2, SwingX11InputForwarder.mapAwtButton(MouseEvent.BUTTON2));
        assertEquals(3, SwingX11InputForwarder.mapAwtButton(MouseEvent.BUTTON3));
    }

    @Test
    @DisplayName("NOBUTTON and out-of-range buttons map to -1 (no injection)")
    void rejectsNonButtons() {
        assertEquals(-1, SwingX11InputForwarder.mapAwtButton(MouseEvent.NOBUTTON));
        assertEquals(-1, SwingX11InputForwarder.mapAwtButton(0));
        assertEquals(-1, SwingX11InputForwarder.mapAwtButton(4));
        assertEquals(-1, SwingX11InputForwarder.mapAwtButton(-1));
    }

    @Test
    @DisplayName("keysymToKeycode accounts for keysyms_per_keycode")
    void resolvesKeycodeWithPerKeycode() {
        // Two keysyms per keycode, min keycode 8: {base,shift} pairs.
        int[] syms = { 0x61, 0x41, 0x62, 0x42, 0x63, 0x43 };
        assertEquals(8, SwingX11InputForwarder.keysymToKeycode(syms, 2, 8, 0x61));
        // The shifted keysym of the same keycode resolves to the same keycode.
        assertEquals(8, SwingX11InputForwarder.keysymToKeycode(syms, 2, 8, 0x41));
        assertEquals(9, SwingX11InputForwarder.keysymToKeycode(syms, 2, 8, 0x62));
        assertEquals(9, SwingX11InputForwarder.keysymToKeycode(syms, 2, 8, 0x42));
        assertEquals(10, SwingX11InputForwarder.keysymToKeycode(syms, 2, 8, 0x63));
    }

    @Test
    @DisplayName("keysymToKeycode honours a non-zero minimum keycode")
    void honoursMinKeycode() {
        int[] syms = { 0x00, 0x61 };
        assertEquals(8, SwingX11InputForwarder.keysymToKeycode(syms, 1, 8, 0x00));
        assertEquals(9, SwingX11InputForwarder.keysymToKeycode(syms, 1, 8, 0x61));
    }

    @Test
    @DisplayName("an absent keysym or an unusable table resolves to -1")
    void rejectsAbsentKeysym() {
        int[] syms = { 0x61, 0x62 };
        assertEquals(-1, SwingX11InputForwarder.keysymToKeycode(syms, 2, 8, 0x9999));
        assertEquals(-1, SwingX11InputForwarder.keysymToKeycode(null, 2, 8, 0x61));
        assertEquals(-1, SwingX11InputForwarder.keysymToKeycode(syms, 0, 8, 0x61));
        assertEquals(-1, SwingX11InputForwarder.keysymToKeycode(syms, -1, 8, 0x61));
    }
}
