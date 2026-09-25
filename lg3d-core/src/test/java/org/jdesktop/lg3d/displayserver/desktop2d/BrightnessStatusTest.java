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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.jdesktop.lg3d.displayserver.desktop2d.BrightnessStatus.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link BrightnessStatus}'s pure maths and formatting: percentage
 * clamping, the raw↔percentage scaling against a backlight maximum, the sysfs
 * parse, and the glyph/tooltip text. No backlight device is touched, so the
 * suite is deterministic and headless.
 */
class BrightnessStatusTest {

    @Test
    @DisplayName("clamp keeps a percentage inside 0-100")
    void clamps() {
        assertEquals(0, BrightnessStatus.clamp(-20));
        assertEquals(0, BrightnessStatus.clamp(0));
        assertEquals(50, BrightnessStatus.clamp(50));
        assertEquals(100, BrightnessStatus.clamp(100));
        assertEquals(100, BrightnessStatus.clamp(250));
    }

    @Test
    @DisplayName("a raw value maps linearly onto 0-100 across 0..max")
    void percentFromRawMapsLinearly() {
        assertEquals(0, BrightnessStatus.percentFromRaw(0, 255));
        assertEquals(50, BrightnessStatus.percentFromRaw(128, 255));
        assertEquals(100, BrightnessStatus.percentFromRaw(255, 255));
    }

    @Test
    @DisplayName("out-of-range raw values are clamped, and a bad max yields 0")
    void percentFromRawClamps() {
        assertEquals(100, BrightnessStatus.percentFromRaw(999, 255));
        assertEquals(0, BrightnessStatus.percentFromRaw(-5, 255));
        assertEquals(0, BrightnessStatus.percentFromRaw(10, 0));
        assertEquals(0, BrightnessStatus.percentFromRaw(10, -1));
    }

    @Test
    @DisplayName("rawFromPercent inverts percentFromRaw at the boundaries")
    void rawRoundTrip() {
        assertEquals(0, BrightnessStatus.rawFromPercent(0, 255));
        assertEquals(255, BrightnessStatus.rawFromPercent(100, 255));
        assertEquals(0, BrightnessStatus.rawFromPercent(50, 0));
        // A clamped percentage maps back inside the range, then to itself.
        int percent = BrightnessStatus.percentFromRaw(
                BrightnessStatus.rawFromPercent(150, 255), 255);
        assertEquals(100, percent);
    }

    @Test
    @DisplayName("parse reads a valid pair and rejects malformed input")
    void parseRejectsBadInput() {
        assertEquals(Optional.of(new Level(50)),
                BrightnessStatus.parse("128", "255"));
        assertEquals(Optional.of(new Level(100)),
                BrightnessStatus.parse(" 300 ", "255"));
        assertTrue(BrightnessStatus.parse(null, "255").isEmpty());
        assertTrue(BrightnessStatus.parse("", "255").isEmpty());
        assertTrue(BrightnessStatus.parse("abc", "255").isEmpty());
        assertTrue(BrightnessStatus.parse("10", null).isEmpty());
        assertTrue(BrightnessStatus.parse("10", "0").isEmpty(), "a zero max is unusable");
    }

    @Test
    @DisplayName("glyph shows a percentage or '--' when unknown")
    void glyphFormatting() {
        assertEquals("Bri --", BrightnessStatus.glyph(null));
        assertEquals("Bri 60%", BrightnessStatus.glyph(new Level(60)));
    }

    @Test
    @DisplayName("label gives a human tooltip for each state")
    void labelFormatting() {
        assertEquals("Brightness: unavailable", BrightnessStatus.label(null));
        assertEquals("Brightness: 60%", BrightnessStatus.label(new Level(60)));
    }

    @Test
    @DisplayName("read()/setBrightness() never throw headless and agree on control")
    void readAndWriteAreSafeHeadless() {
        // On a box with no controllable backlight read() is empty and the write
        // reports false; either way neither call may throw.
        assertTrue(BrightnessStatus.read() != null);
        boolean applied = BrightnessStatus.setBrightness(40);
        if (BrightnessStatus.isControllable()) {
            assertTrue(applied, "a writable backlight must take the value");
        } else {
            assertFalse(applied, "an unwritable backlight must report refusal");
        }
    }

    @Test
    @DisplayName("a controllable backlight is always readable")
    void controllableImpliesReadable() {
        if (BrightnessStatus.isControllable()) {
            assertTrue(BrightnessStatus.read().isPresent(),
                    "a writable device must also be readable");
        }
    }
}
