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
 * Headless unit tests for {@link ConfigureNotifyBugFixed}, the subclass that
 * repairs the stock Escher {@code ConfigureNotify} coordinate accessors.
 *
 * <p>The X protocol encodes a configure event's {@code x}/{@code y} as
 * <em>signed</em> 16-bit integers (a window can sit at a negative offset when
 * it straddles the top-left of the root). The base {@code ConfigureNotify.x()}
 * /{@code y()} return the raw <em>unsigned</em> {@code read2}, so a negative
 * position comes back as a huge positive number. {@code ConfigureNotifyBugFixed}
 * sign-extends the low 16 bits with {@code (v << 16) >> 16}.
 *
 * <p>Neither the {@code ConfigureNotify} reading constructor nor these
 * accessors touch the {@code Display} (it is only stored), so the whole seam is
 * exercised with a {@code null} display and a synthetic 32-byte event buffer.
 * The buffer is populated through {@link Data#write2(int, int)} so the test is
 * byte-order-agnostic: writes and reads use the same host-order convention.
 */
class ConfigureNotifyBugFixedTest {

    /** Builds a 32-byte ConfigureNotify buffer with x at byte 16, y at 18. */
    private static byte[] event(int x, int y) {
        Data d = new Data(new byte[32]);
        d.write2(16, x & 0xffff);
        d.write2(18, y & 0xffff);
        return d.data;
    }

    @Test
    void positiveCoordinatesAreUnchanged() {
        ConfigureNotifyBugFixed e = new ConfigureNotifyBugFixed(null, event(120, 45));
        assertEquals(120, e.x());
        assertEquals(45, e.y());
    }

    @Test
    void negativeCoordinatesAreSignExtended() {
        // 0xfff6 as a signed 16-bit value is -10; the unsigned read2 would
        // return 65526, which is exactly the bug this subclass fixes.
        ConfigureNotifyBugFixed e = new ConfigureNotifyBugFixed(null, event(0xfff6, 0xfff7));
        assertEquals(-10, e.x());
        assertEquals(-9, e.y());
    }

    @Test
    void mostNegativeShortIsHandled() {
        ConfigureNotifyBugFixed e = new ConfigureNotifyBugFixed(null, event(0x8000, 0x8000));
        assertEquals(Short.MIN_VALUE, e.x());
        assertEquals(Short.MIN_VALUE, e.y());
    }

    @Test
    void largestPositiveShortIsHandled() {
        ConfigureNotifyBugFixed e = new ConfigureNotifyBugFixed(null, event(0x7fff, 0x7fff));
        assertEquals(Short.MAX_VALUE, e.x());
        assertEquals(Short.MAX_VALUE, e.y());
    }

    @Test
    void zeroCoordinatesAreZero() {
        ConfigureNotifyBugFixed e = new ConfigureNotifyBugFixed(null, event(0, 0));
        assertEquals(0, e.x());
        assertEquals(0, e.y());
    }
}
