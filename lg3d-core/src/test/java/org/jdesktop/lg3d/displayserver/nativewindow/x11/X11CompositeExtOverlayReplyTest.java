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

import gnu.x11.Data;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link X11CompositeExt.OverlayReply}, the Composite
 * {@code GetOverlayWindow} reply decoder.
 *
 * <p>{@code OverlayReply} is a pure {@link Data} view: the only field lg3d
 * models is {@code overlay_window_id()} (a CARD32 at byte 8). It never touches
 * the {@code Display}, so it is exercised on a synthetic buffer, written via
 * {@link Data#write4(int, int)} so the assertion is byte-order-agnostic.
 */
class X11CompositeExtOverlayReplyTest {

    private static X11CompositeExt.OverlayReply reply(int overlayId) {
        Data d = new Data(new byte[32]);
        d.write4(8, overlayId);
        return new X11CompositeExt.OverlayReply(d);
    }

    @Test
    void decodesOverlayWindowId() {
        assertEquals(0x00400003, reply(0x00400003).overlay_window_id());
    }

    @Test
    void decodesZeroOverlayWindowId() {
        assertEquals(0, reply(0).overlay_window_id());
    }

    @Test
    void decodesHighBitWindowId() {
        // Window ids are unsigned CARD32s; a value with the top bit set must
        // not be interpreted as negative by the reader.
        assertEquals(0x80000001, reply(0x80000001).overlay_window_id());
    }
}
