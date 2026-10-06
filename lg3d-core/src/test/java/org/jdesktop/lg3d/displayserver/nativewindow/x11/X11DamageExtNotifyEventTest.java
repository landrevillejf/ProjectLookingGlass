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

import gnu.x11.Data;
import gnu.x11.Rectangle;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link X11DamageExt.NotifyEvent}, the DamageNotify
 * event decoder.
 *
 * <p>{@code NotifyEvent} is an {@code Event} whose reading constructor only
 * stores the {@code Display}; every accessor is a pure read out of the 32-byte
 * wire buffer, so the whole class is exercised with a {@code null} display and
 * a synthetic event. Fields are written through {@link Data}'s helpers so the
 * assertions hold on both little- and big-endian hosts. The signed
 * {@code area}/{@code geometry} coordinates (INT16 on the wire, decoded with a
 * {@code (short)} cast) are the interesting cases: a damaged region can start
 * at a negative offset relative to the drawable origin.
 */
class X11DamageExtNotifyEventTest {

    /** Builds a 32-byte DamageNotify buffer. */
    private static X11DamageExt.NotifyEvent event(
            int level, int drawable, int damage, int timestamp,
            int ax, int ay, int aw, int ah,
            int gx, int gy, int gw, int gh) {
        Data d = new Data(new byte[32]);
        d.write1(0, 0);              // extension event code (DamageNotify = 0)
        d.write1(1, level);
        d.write4(4, drawable);
        d.write4(8, damage);
        d.write4(12, timestamp);
        d.write2(16, ax & 0xffff);
        d.write2(18, ay & 0xffff);
        d.write2(20, aw & 0xffff);
        d.write2(22, ah & 0xffff);
        d.write2(24, gx & 0xffff);
        d.write2(26, gy & 0xffff);
        d.write2(28, gw & 0xffff);
        d.write2(30, gh & 0xffff);
        return new X11DamageExt.NotifyEvent(null, d.data);
    }

    @Test
    void decodesScalarFields() {
        X11DamageExt.NotifyEvent e = event(
                X11DamageExt.REPORT_LEVEL_NON_EMPTY, 0x00400007, 0x00500009, 987654,
                0, 0, 640, 480, 0, 0, 640, 480);
        assertEquals(X11DamageExt.REPORT_LEVEL_NON_EMPTY, e.level());
        assertEquals(0x00400007, e.drawable_id());
        assertEquals(0x00500009, e.damage_id());
        assertEquals(987654, e.timestamp());
        // window_offset is 4, so window_id() aliases the drawable field.
        assertEquals(0x00400007, e.window_id());
    }

    @Test
    void decodesUnsignedAreaAndGeometryDimensions() {
        X11DamageExt.NotifyEvent e = event(3, 1, 2, 3,
                0, 0, 65535, 40000, 0, 0, 50000, 60000);
        assertEquals(65535, e.area_width());
        assertEquals(40000, e.area_height());
        assertEquals(50000, e.geometry_width());
        assertEquals(60000, e.geometry_height());
    }

    @Test
    void decodesNegativeAreaAndGeometryOrigins() {
        // -5 / -12 as unsigned 16-bit patterns, sign-corrected by the (short) cast.
        X11DamageExt.NotifyEvent e = event(3, 1, 2, 3,
                0xfffb, 0xfff4, 10, 20, 0xfff6, 0xfff7, 30, 40);
        assertEquals(-5, e.area_x());
        assertEquals(-12, e.area_y());
        assertEquals(-10, e.geometry_x());
        assertEquals(-9, e.geometry_y());
    }

    @Test
    void areaAndGeometryBuildRectangles() {
        X11DamageExt.NotifyEvent e = event(3, 1, 2, 3,
                -5, -12, 10, 20, 0, 0, 640, 480);
        Rectangle area = e.area();
        assertEquals(-5, area.x);
        assertEquals(-12, area.y);
        assertEquals(10, area.width);
        assertEquals(20, area.height);

        Rectangle geometry = e.geometry();
        assertEquals(0, geometry.x);
        assertEquals(0, geometry.y);
        assertEquals(640, geometry.width);
        assertEquals(480, geometry.height);
    }

    @Test
    void toStringReportsHexIdsAndLevel() {
        X11DamageExt.NotifyEvent e = event(3, 0x00400007, 0x00500009, 42,
                0, 0, 8, 8, 0, 0, 8, 8);
        String s = e.toString();
        assertTrue(s.contains("#DamageNotify"), s);
        assertTrue(s.contains("400007"), s);   // drawable, lower-case hex
        assertTrue(s.contains("500009"), s);   // damage, lower-case hex
        assertTrue(s.contains("level: 3"), s);
    }
}
