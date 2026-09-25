/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.displayserver.desktop2d.VolumeStatus.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VolumeStatus}'s pure maths and formatting: percentage clamping,
 * the dB↔percentage conversion used against a master-gain control, and the
 * glyph/tooltip text. No sound card is touched, so the suite is deterministic
 * and headless.
 */
class VolumeStatusTest {

    @Test
    @DisplayName("clamp keeps a percentage inside 0-100")
    void clamps() {
        assertEquals(0, VolumeStatus.clamp(-20));
        assertEquals(0, VolumeStatus.clamp(0));
        assertEquals(50, VolumeStatus.clamp(50));
        assertEquals(100, VolumeStatus.clamp(100));
        assertEquals(100, VolumeStatus.clamp(250));
    }

    @Test
    @DisplayName("dB maps linearly to a percentage across min..max")
    void percentFromDbMapsLinearly() {
        assertEquals(0, VolumeStatus.percentFromDb(-80f, -80f, 0f));
        assertEquals(50, VolumeStatus.percentFromDb(-40f, -80f, 0f));
        assertEquals(100, VolumeStatus.percentFromDb(0f, -80f, 0f));
    }

    @Test
    @DisplayName("out-of-range dB is clamped, and a degenerate range yields 0")
    void percentFromDbClamps() {
        assertEquals(100, VolumeStatus.percentFromDb(50f, -80f, 0f));
        assertEquals(0, VolumeStatus.percentFromDb(-100f, -80f, 0f));
        assertEquals(0, VolumeStatus.percentFromDb(0f, 0f, 0f));
    }

    @Test
    @DisplayName("dbFromPercent inverts percentFromDb at the boundaries")
    void dbRoundTrip() {
        assertEquals(-80f, VolumeStatus.dbFromPercent(0, -80f, 0f), 0.001f);
        assertEquals(-40f, VolumeStatus.dbFromPercent(50, -80f, 0f), 0.001f);
        assertEquals(0f, VolumeStatus.dbFromPercent(100, -80f, 0f), 0.001f);
        // A clamped percentage maps back inside the range, then to itself.
        int percent = VolumeStatus.percentFromDb(
                VolumeStatus.dbFromPercent(150, -80f, 0f), -80f, 0f);
        assertEquals(100, percent);
    }

    @Test
    @DisplayName("glyph shows mute, a percentage, or '--' when unknown")
    void glyphFormatting() {
        assertEquals("Vol --", VolumeStatus.glyph(null));
        assertEquals("Vol x", VolumeStatus.glyph(new Level(40, true)));
        assertEquals("Vol 42%", VolumeStatus.glyph(new Level(42, false)));
    }

    @Test
    @DisplayName("label gives a human tooltip for each state")
    void labelFormatting() {
        assertEquals("Volume: unavailable", VolumeStatus.label(null));
        assertEquals("Volume: muted", VolumeStatus.label(new Level(10, true)));
        assertEquals("Volume: 10%", VolumeStatus.label(new Level(10, false)));
    }

    @Test
    @DisplayName("read() never throws headless; it returns empty or a level")
    void readIsSafeHeadless() {
        // On a CI box with no master control this is empty; either way no throw.
        assertTrue(VolumeStatus.read() != null);
    }
}
