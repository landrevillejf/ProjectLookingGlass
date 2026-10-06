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

import org.jdesktop.lg3d.displayserver.nativewindow.x11.ReadbackPlanner.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link ReadbackPlanner} — the pure MIT-SHM vs XGetImage
 * readback-path decision and its sizing arithmetic (Phase E). No X, no segment
 * lifecycle, so every branch is pinned directly.
 */
class ReadbackPlannerTest {

    @Test
    @DisplayName("bytesPerPixel rounds bits up to whole bytes, min 1")
    void bytesPerPixelRoundsUp() {
        assertEquals(4, ReadbackPlanner.bytesPerPixel(32));
        assertEquals(3, ReadbackPlanner.bytesPerPixel(24));
        assertEquals(2, ReadbackPlanner.bytesPerPixel(16));
        assertEquals(2, ReadbackPlanner.bytesPerPixel(15)); // rounds up
        assertEquals(1, ReadbackPlanner.bytesPerPixel(8));
        assertEquals(1, ReadbackPlanner.bytesPerPixel(1));
    }

    @Test
    @DisplayName("a non-positive bits-per-pixel defaults to 32-bit (4 bytes)")
    void bytesPerPixelDefaults() {
        assertEquals(4, ReadbackPlanner.bytesPerPixel(0));
        assertEquals(4, ReadbackPlanner.bytesPerPixel(-8));
    }

    @Test
    @DisplayName("regionBytes is width*height*bytesPerPixel")
    void regionBytesMath() {
        assertEquals(640L * 480L * 4L, ReadbackPlanner.regionBytes(640, 480, 32));
        assertEquals(10L * 10L * 3L, ReadbackPlanner.regionBytes(10, 10, 24));
    }

    @Test
    @DisplayName("regionBytes is zero for an empty region")
    void regionBytesEmpty() {
        assertEquals(0L, ReadbackPlanner.regionBytes(0, 10, 32));
        assertEquals(0L, ReadbackPlanner.regionBytes(10, 0, 32));
        assertEquals(0L, ReadbackPlanner.regionBytes(-1, 10, 32));
    }

    @Test
    @DisplayName("SHM is chosen when available, shared pixmaps supported and the region fits")
    void choosesShm() {
        assertEquals(Path.SHM, ReadbackPlanner.choose(true, true, 1000L, 4096L));
        // An unknown/unbounded capacity (<=0) does not veto SHM.
        assertEquals(Path.SHM, ReadbackPlanner.choose(true, true, 1000L, 0L));
        assertEquals(Path.SHM, ReadbackPlanner.choose(true, true, 1000L, -1L));
        // Exactly fitting the capacity is allowed.
        assertEquals(Path.SHM, ReadbackPlanner.choose(true, true, 4096L, 4096L));
    }

    @Test
    @DisplayName("XGetImage is chosen when SHM is unavailable or shared pixmaps unsupported")
    void fallsBackWhenUnavailable() {
        assertEquals(Path.XGETIMAGE, ReadbackPlanner.choose(false, true, 1000L, 4096L));
        assertEquals(Path.XGETIMAGE, ReadbackPlanner.choose(true, false, 1000L, 4096L));
        assertEquals(Path.XGETIMAGE, ReadbackPlanner.choose(false, false, 1000L, 4096L));
    }

    @Test
    @DisplayName("XGetImage is chosen when the region overflows the segment capacity")
    void fallsBackWhenTooBig() {
        assertEquals(Path.XGETIMAGE, ReadbackPlanner.choose(true, true, 5000L, 4096L));
    }

    @Test
    @DisplayName("XGetImage is chosen for an empty region")
    void fallsBackForEmptyRegion() {
        assertEquals(Path.XGETIMAGE, ReadbackPlanner.choose(true, true, 0L, 4096L));
        assertEquals(Path.XGETIMAGE, ReadbackPlanner.choose(true, true, -1L, 4096L));
    }
}
