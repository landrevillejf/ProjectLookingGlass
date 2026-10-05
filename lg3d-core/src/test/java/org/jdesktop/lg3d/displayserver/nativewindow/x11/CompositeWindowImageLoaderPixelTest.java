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

import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the pure pixel-decoding helpers in
 * {@link CompositeWindowImageLoader}: {@code readPixel} (byte-order-aware pixel
 * assembly from an {@code XGetImage} reply) and {@code channel} (TrueColor mask
 * extraction scaled to 8 bits). Neither touches a {@code Display} or a pixmap,
 * so both are exercised directly with synthetic byte/int inputs.
 */
class CompositeWindowImageLoaderPixelTest {

    // ---- readPixel: byte-order and width handling ----

    @Test
    void readPixelLsbFirstTreatsFirstByteAsLowOrder() {
        // Little-endian storage of 0x12345678: least-significant byte first.
        byte[] d = {0x78, 0x56, 0x34, 0x12};
        assertEquals(0x12345678, CompositeWindowImageLoader.readPixel(d, 0, 4, true));
    }

    @Test
    void readPixelMsbFirstTreatsFirstByteAsHighOrder() {
        byte[] d = {0x12, 0x34, 0x56, 0x78};
        assertEquals(0x12345678, CompositeWindowImageLoader.readPixel(d, 0, 4, false));
    }

    @Test
    void readPixelHonoursOffsetAndThreeByteWidth() {
        // 24-bit pixel starting at offset 1, LSB first: {0xef,0xbe,0xad} -> 0xadbeef.
        byte[] d = {0x00, (byte) 0xef, (byte) 0xbe, (byte) 0xad};
        assertEquals(0x00adbeef, CompositeWindowImageLoader.readPixel(d, 1, 3, true));
    }

    @Test
    void readPixelSingleByteIsOrderIndependent() {
        byte[] d = {0x00, 0x41};
        assertEquals(0x41, CompositeWindowImageLoader.readPixel(d, 1, 1, true));
        assertEquals(0x41, CompositeWindowImageLoader.readPixel(d, 1, 1, false));
    }

    // ---- channel: TrueColor mask extraction scaled to 8 bits ----

    @Test
    void channelExtracts8BitTrueColorMasks() {
        int pixel = 0x123456; // 8-8-8: R=0x12 G=0x34 B=0x56
        assertEquals(0x12, CompositeWindowImageLoader.channel(pixel, 0xff0000));
        assertEquals(0x34, CompositeWindowImageLoader.channel(pixel, 0x00ff00));
        assertEquals(0x56, CompositeWindowImageLoader.channel(pixel, 0x0000ff));
    }

    @Test
    void channelZeroMaskReturnsZero() {
        assertEquals(0, CompositeWindowImageLoader.channel(0xffffff, 0));
    }

    @Test
    void channelScalesUpNarrow565MasksTo8Bits() {
        // 16-bit 5-6-5: red 0xf800 (5b), green 0x07e0 (6b), blue 0x001f (5b).
        int pixel = 0xffff; // every channel at its maximum
        assertEquals(0x1f << 3, CompositeWindowImageLoader.channel(pixel, 0xf800)); // 248
        assertEquals(0x3f << 2, CompositeWindowImageLoader.channel(pixel, 0x07e0)); // 252
        assertEquals(0x1f << 3, CompositeWindowImageLoader.channel(pixel, 0x001f)); // 248
    }

    @Test
    void channelScalesDownWideMasksToTop8Bits() {
        // A 10-bit-wide mask keeps only its top 8 bits: v >>> (width - 8).
        assertEquals(0xaa, CompositeWindowImageLoader.channel(0x2aa, 0x3ff));
    }

    @Test
    void channelReadsOnlyTheMaskedBits() {
        // Bits outside the mask must not leak into the extracted channel.
        int pixel = 0xff00ff; // green channel (0x00ff00) is empty
        assertEquals(0x00, CompositeWindowImageLoader.channel(pixel, 0x00ff00));
        assertEquals(0xff, CompositeWindowImageLoader.channel(pixel, 0xff0000));
        assertEquals(0xff, CompositeWindowImageLoader.channel(pixel, 0x0000ff));
    }
}
