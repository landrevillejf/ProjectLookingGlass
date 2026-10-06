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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gnu.x11.Data;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link X11ShmExt.GetImageReply}, the MIT-SHM
 * {@code ShmGetImage} reply decoder.
 *
 * <p>{@code GetImageReply} is a pure {@link Data} view over the reply bytes:
 * {@code depth()} at byte 1, {@code visual_id()} at 8, {@code size()} at 12 and
 * {@code pixels()} copying the payload that follows the 32-byte header. It
 * never touches the {@code Display}, so it is exercised directly on a synthetic
 * buffer. All multi-byte fields are written through {@link Data}'s write
 * helpers so the assertions hold on both little- and big-endian hosts.
 */
class X11ShmExtGetImageReplyTest {

    /** Wraps a header + payload buffer as a GetImageReply. */
    private static X11ShmExt.GetImageReply reply(int depth, int visual, int size, byte[] payload) {
        byte[] buf = new byte[32 + payload.length];
        Data d = new Data(buf);
        d.write1(1, depth);
        d.write4(8, visual);
        d.write4(12, size);
        System.arraycopy(payload, 0, buf, 32, payload.length);
        return new X11ShmExt.GetImageReply(d);
    }

    @Test
    void decodesHeaderFields() {
        X11ShmExt.GetImageReply r = reply(24, 0x21, 0, new byte[0]);
        assertEquals(24, r.depth());
        assertEquals(0x21, r.visual_id());
        assertEquals(0, r.size());
    }

    @Test
    void depthIsReadAsUnsignedByte() {
        X11ShmExt.GetImageReply r = reply(0xff, 0, 0, new byte[0]);
        assertEquals(255, r.depth());
    }

    @Test
    void pixelsCopiesTheFullDeclaredPayload() {
        byte[] payload = {0x11, 0x22, 0x33, 0x44, 0x55};
        X11ShmExt.GetImageReply r = reply(24, 0x21, payload.length, payload);
        assertEquals(payload.length, r.size());
        assertArrayEquals(payload, r.pixels());
    }

    @Test
    void pixelsClampsTheCopyWhenSizeExceedsTheBuffer() {
        // The reply advertises more bytes than actually arrived. pixels() sizes
        // the array by the declared count but clamps the copy to what is
        // present, so it never over-reads the source: the tail stays zero.
        byte[] payload = {0x01, 0x02, 0x03};
        X11ShmExt.GetImageReply r = reply(24, 0x21, 5, payload);
        assertEquals(5, r.size());
        byte[] px = r.pixels();
        assertEquals(5, px.length);
        assertEquals(0x01, px[0]);
        assertEquals(0x02, px[1]);
        assertEquals(0x03, px[2]);
        assertEquals(0, px[3]);
        assertEquals(0, px[4]);
    }

    @Test
    void pixelsIsSizedByTheDeclaredCountEvenWhenShorter() {
        // size() smaller than the trailing buffer: pixels() returns exactly
        // size() bytes (zero-padded), not the whole remainder.
        byte[] payload = {0x0a, 0x0b, 0x0c, 0x0d};
        X11ShmExt.GetImageReply r = reply(24, 0x21, 2, payload);
        byte[] px = r.pixels();
        assertEquals(2, px.length);
        assertEquals(0x0a, px[0]);
        assertEquals(0x0b, px[1]);
    }

    @Test
    void zeroSizeYieldsEmptyPixels() {
        X11ShmExt.GetImageReply r = reply(32, 0x100, 0, new byte[0]);
        assertEquals(0, r.pixels().length);
    }
}
