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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link CompositeWindowImageLoader#decodeZPixmap} — the
 * pure, {@code Display}-free pixel decoder that is the single shared source of
 * the compositor pipeline (both the 3D texture path and the 2D Swing path read
 * through it). Synthetic byte buffers stand in for an {@code XGetImage} reply, so
 * no live X connection, {@code Display} or Java 3D is required.
 *
 * <p>Complements {@code CompositeWindowImageLoaderPixelTest} (which covers the
 * per-pixel {@code readPixel}/{@code channel} helpers) by exercising the full
 * scanline assembly: stride/padding, byte order, channel masks, truncation and
 * the geometry/bpp guards.</p>
 */
class CompositeWindowImageLoaderDecodeTest {

    /** Byte offset of pixel data within a GetImage reply (mirrors the loader). */
    private static final int IMAGE_DATA_OFFSET = 32;

    /** Standard 24-bit TrueColor masks. */
    private static final int R = 0xff0000;
    private static final int G = 0x00ff00;
    private static final int B = 0x0000ff;

    /** A reply buffer sized exactly for {@code h} scanlines of {@code stride}. */
    private static byte[] reply(int stride, int h) {
        return new byte[IMAGE_DATA_OFFSET + stride * h];
    }

    /** Writes big-endian-listed byte values at {@code off}. */
    private static void putBytes(byte[] data, int off, int... bs) {
        for (int i = 0; i < bs.length; i++) {
            data[off + i] = (byte) bs[i];
        }
    }

    @Test
    @DisplayName("32bpp LSB 2x2 decodes each pixel to ARGB via the TrueColor masks")
    void decodes32bppLsb() {
        final int stride = 8; // ((2*32 + 31)/32) * 4
        byte[] data = reply(stride, 2);
        putBytes(data, IMAGE_DATA_OFFSET, 0x11, 0x22, 0x33, 0x44);          // (0,0)
        putBytes(data, IMAGE_DATA_OFFSET + 4, 0xAA, 0xBB, 0xCC, 0xDD);      // (1,0)
        putBytes(data, IMAGE_DATA_OFFSET + stride, 0x01, 0x02, 0x03, 0x04); // (0,1)
        putBytes(data, IMAGE_DATA_OFFSET + stride + 4, 0xF0, 0x0F, 0x77, 0x88); // (1,1)

        BufferedImage img = CompositeWindowImageLoader.decodeZPixmap(
            data, 2, 2, 32, 32, true, R, G, B);

        assertNotNull(img);
        assertEquals(2, img.getWidth());
        assertEquals(2, img.getHeight());
        // LSB: byte0=blue, byte1=green, byte2=red; byte3 is padding/alpha.
        assertEquals(0xff332211, img.getRGB(0, 0));
        assertEquals(0xffCCBBAA, img.getRGB(1, 0));
        assertEquals(0xff030201, img.getRGB(0, 1));
        assertEquals(0xff770FF0, img.getRGB(1, 1));
    }

    @Test
    @DisplayName("MSBFirst byte order assembles the pixel from the opposite end")
    void decodesMsbByteOrder() {
        final int stride = 4;
        byte[] data = reply(stride, 1);
        putBytes(data, IMAGE_DATA_OFFSET, 0x00, 0xF0, 0x0F, 0xAA);

        BufferedImage img = CompositeWindowImageLoader.decodeZPixmap(
            data, 1, 1, 32, 32, false, R, G, B);

        assertNotNull(img);
        // MSB: pv=(B0<<24)|(B1<<16)|(B2<<8)|B3 -> red=B1, green=B2, blue=B3.
        assertEquals(0xffF00FAA, img.getRGB(0, 0));
    }

    @Test
    @DisplayName("16bpp 5-6-5 scales each narrow channel up to 8 bits")
    void decodes16bpp565() {
        final int stride = 4; // ((1*16 + 31)/32) * 4
        byte[] data = reply(stride, 1);
        putBytes(data, IMAGE_DATA_OFFSET, 0xFF, 0xFF); // 0xFFFF: every channel maxed

        BufferedImage img = CompositeWindowImageLoader.decodeZPixmap(
            data, 1, 1, 16, 32, true, 0xf800, 0x07e0, 0x001f);

        assertNotNull(img);
        // 5 bits -> <<3 (0xF8), 6 bits -> <<2 (0xFC), 5 bits -> <<3 (0xF8).
        assertEquals(0xffF8FCF8, img.getRGB(0, 0));
    }

    @Test
    @DisplayName("the scanline stride skips per-row padding bytes (24bpp, pad 32)")
    void honoursScanlineStride() {
        // w=1, 24bpp, scanlinePad=32 -> stride=((1*24 + 31)/32)*4 = 4 (3 + 1 pad).
        final int stride = 4;
        byte[] data = reply(stride, 2);
        putBytes(data, IMAGE_DATA_OFFSET, 0x11, 0x22, 0x33, 0x99);          // row0 + pad
        putBytes(data, IMAGE_DATA_OFFSET + stride, 0xAA, 0xBB, 0xCC, 0x99); // row1 + pad

        BufferedImage img = CompositeWindowImageLoader.decodeZPixmap(
            data, 1, 2, 24, 32, true, R, G, B);

        assertNotNull(img);
        assertEquals(0xff332211, img.getRGB(0, 0));
        // A wrong (3-byte) stride would read the pad byte and corrupt row 1.
        assertEquals(0xffCCBBAA, img.getRGB(0, 1));
    }

    @Test
    @DisplayName("a buffer shorter than stride*height is rejected as truncated")
    void rejectsTruncatedBuffer() {
        final int stride = 8;
        byte[] full = reply(stride, 2); // 48 bytes needed
        byte[] truncated = new byte[full.length - 1];
        assertNull(CompositeWindowImageLoader.decodeZPixmap(
            truncated, 2, 2, 32, 32, true, R, G, B));
    }

    @Test
    @DisplayName("a null pixel buffer is rejected")
    void rejectsNullBuffer() {
        assertNull(CompositeWindowImageLoader.decodeZPixmap(
            null, 2, 2, 32, 32, true, R, G, B));
    }

    @Test
    @DisplayName("empty or negative geometry decodes to null before any read")
    void rejectsEmptyGeometry() {
        byte[] data = reply(4, 1);
        assertNull(CompositeWindowImageLoader.decodeZPixmap(
            data, 0, 1, 32, 32, true, R, G, B));
        assertNull(CompositeWindowImageLoader.decodeZPixmap(
            data, 1, 0, 32, 32, true, R, G, B));
        assertNull(CompositeWindowImageLoader.decodeZPixmap(
            data, -1, 1, 32, 32, true, R, G, B));
    }

    @Test
    @DisplayName("an out-of-range bits-per-pixel decodes to null")
    void rejectsUnsupportedBpp() {
        byte[] data = new byte[1024];
        // 0 bpp -> 0 bytes/pixel (< 1); 64 bpp -> 8 bytes/pixel (> 4).
        assertNull(CompositeWindowImageLoader.decodeZPixmap(
            data, 2, 2, 0, 32, true, R, G, B));
        assertNull(CompositeWindowImageLoader.decodeZPixmap(
            data, 2, 2, 64, 32, true, R, G, B));
    }
}
