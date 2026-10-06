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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gnu.x11.Data;
import gnu.x11.Enum;
import gnu.x11.Rectangle;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the two pure XFixes decoders in
 * {@link X11FixesExt}: {@link X11FixesExt.FetchRegionReply} (a {@link Data}
 * view whose {@code rectangles()} enumerates the RECTANGLE list trailing the
 * 32-byte header) and {@link X11FixesExt.CursorNotifyEvent} (an {@code Event}
 * whose reading constructor only stores the {@code Display}).
 *
 * <p>Neither decoder touches a live connection, so both are exercised on
 * synthetic buffers with a {@code null} display. Every multi-byte field is
 * written through {@link Data}'s write helpers so the assertions hold on both
 * little- and big-endian hosts.
 */
class X11FixesExtReplyTest {

    // ---- FetchRegionReply ----

    /** Builds a FetchRegion reply: count<<1 at byte 4, then 8-byte rectangles from byte 32. */
    private static X11FixesExt.FetchRegionReply region(int[][] rects) {
        byte[] buf = new byte[32 + 8 * rects.length];
        Data d = new Data(buf);
        // The wire field is nRects in CARD32 at byte 4, doubled (>>1 on read).
        d.write4(4, rects.length << 1);
        for (int i = 0; i < rects.length; i++) {
            int base = 32 + 8 * i;
            d.write2(base + 0, rects[i][0] & 0xffff); // x (signed on the wire)
            d.write2(base + 2, rects[i][1] & 0xffff); // y
            d.write2(base + 4, rects[i][2] & 0xffff); // width
            d.write2(base + 6, rects[i][3] & 0xffff); // height
        }
        return new X11FixesExt.FetchRegionReply(d);
    }

    @Test
    void rectangleCountHalvesTheWireField() {
        X11FixesExt.FetchRegionReply r = region(new int[][] {{0, 0, 10, 10}, {5, 5, 1, 1}});
        assertEquals(2, r.rectangle_count());
    }

    @Test
    void emptyRegionHasZeroRectangles() {
        X11FixesExt.FetchRegionReply r = region(new int[0][]);
        assertEquals(0, r.rectangle_count());
        assertFalse(r.rectangles().more());
    }

    @Test
    void rectanglesEnumeratesEveryRectangleInOrder() {
        X11FixesExt.FetchRegionReply r = region(new int[][] {
            {0, 0, 100, 50},
            {10, 20, 30, 40},
            {7, 8, 9, 11},
        });
        Enum e = r.rectangles();
        int seen = 0;
        int[][] expected = {{0, 0, 100, 50}, {10, 20, 30, 40}, {7, 8, 9, 11}};
        while (e.more()) {
            Rectangle rect = (Rectangle) e.next();
            assertEquals(expected[seen][0], rect.x);
            assertEquals(expected[seen][1], rect.y);
            assertEquals(expected[seen][2], rect.width);
            assertEquals(expected[seen][3], rect.height);
            seen++;
        }
        assertEquals(3, seen);
    }

    // ---- CursorNotifyEvent ----

    /** Builds a 32-byte XFixes CursorNotify buffer (window at byte 4). */
    private static X11FixesExt.CursorNotifyEvent cursorEvent(
            int type, int subtype, int window, int serial, int timestamp, int nameAtom) {
        Data d = new Data(new byte[32]);
        d.write1(0, type);
        d.write1(1, subtype);
        d.write4(4, window);
        d.write4(8, serial);
        d.write4(12, timestamp);
        d.write4(16, nameAtom);
        return new X11FixesExt.CursorNotifyEvent(null, d.data);
    }

    @Test
    void decodesCursorNotifyFields() {
        X11FixesExt.CursorNotifyEvent e = cursorEvent(1, 0, 0x00400001, 42, 123456, 320);
        assertEquals(1, e.code());
        assertEquals(0, e.subtype());
        assertEquals(0x00400001, e.window_id());
        assertEquals(42, e.cursor_serial());
        assertEquals(123456, e.timestamp());
        assertEquals(320, e.name_atom());
    }

    @Test
    void unnamedCursorHasZeroNameAtom() {
        X11FixesExt.CursorNotifyEvent e = cursorEvent(1, 0, 0x1, 7, 99, 0);
        assertEquals(0, e.name_atom());
    }

    @Test
    void highBitOfTypeByteMarksTheEventSynthetic() {
        // The Event reading constructor strips the 0x80 "synthetic" flag from
        // the type byte and records it separately.
        X11FixesExt.CursorNotifyEvent e = cursorEvent(0x81, 0, 0x1, 1, 1, 1);
        assertTrue(e.synthetic);
        assertEquals(1, e.code());

        X11FixesExt.CursorNotifyEvent plain = cursorEvent(0x01, 0, 0x1, 1, 1, 1);
        assertFalse(plain.synthetic);
    }
}
