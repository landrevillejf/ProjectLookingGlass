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

import java.awt.Color;
import java.util.Optional;
import org.jdesktop.lg3d.displayserver.desktop2d.BatteryStatus.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link BatteryStatus}'s pure parsing and formatting: valid/invalid
 * capacity strings, the charging flag, and the glyph/tooltip text. No sysfs read
 * is exercised, so the suite is deterministic and headless.
 */
class BatteryStatusTest {

    @Test
    @DisplayName("a plain capacity parses to a discharging level")
    void parsesDischarging() {
        Optional<Level> level = BatteryStatus.parse("42", "Discharging");
        assertTrue(level.isPresent());
        assertEquals(42, level.get().percent());
        assertFalse(level.get().charging());
    }

    @Test
    @DisplayName("status 'Charging' sets the charging flag, case-insensitively")
    void parsesCharging() {
        Optional<Level> level = BatteryStatus.parse(" 85 ", "cHaRgInG");
        assertTrue(level.isPresent());
        assertEquals(85, level.get().percent());
        assertTrue(level.get().charging());
    }

    @Test
    @DisplayName("boundary percentages 0 and 100 are accepted")
    void acceptsBoundaries() {
        assertTrue(BatteryStatus.parse("0", "Full").isPresent());
        assertTrue(BatteryStatus.parse("100", "Full").isPresent());
    }

    @Test
    @DisplayName("null, blank, non-numeric and out-of-range capacity yield empty")
    void rejectsInvalid() {
        assertTrue(BatteryStatus.parse(null, "Charging").isEmpty());
        assertTrue(BatteryStatus.parse("", "Charging").isEmpty());
        assertTrue(BatteryStatus.parse("   ", "Charging").isEmpty());
        assertTrue(BatteryStatus.parse("abc", "Charging").isEmpty());
        assertTrue(BatteryStatus.parse("-1", "Charging").isEmpty());
        assertTrue(BatteryStatus.parse("101", "Charging").isEmpty());
    }

    @Test
    @DisplayName("a null status is treated as not charging")
    void nullStatusNotCharging() {
        Optional<Level> level = BatteryStatus.parse("50", null);
        assertTrue(level.isPresent());
        assertFalse(level.get().charging());
    }

    @Test
    @DisplayName("glyph shows the percentage and a '+' while charging")
    void glyphFormatting() {
        assertEquals("", BatteryStatus.glyph(null));
        assertEquals("Bat 7%", BatteryStatus.glyph(new Level(7, false)));
        assertEquals("Bat 99% +", BatteryStatus.glyph(new Level(99, true)));
    }

    @Test
    @DisplayName("label gives a human tooltip, or 'No battery' when absent")
    void labelFormatting() {
        assertEquals("No battery", BatteryStatus.label(null));
        assertEquals("Battery 40% (on battery)", BatteryStatus.label(new Level(40, false)));
        assertEquals("Battery 40% (charging)", BatteryStatus.label(new Level(40, true)));
    }

    @Test
    @DisplayName("the gauge colour grades green to red as the charge drains")
    void colorGradesWithCharge() {
        assertEquals(new Color(0x7F, 0x8C, 0x8D), BatteryStatus.color(null));
        assertEquals(new Color(0x2E, 0xCC, 0x71), BatteryStatus.color(new Level(50, true)));
        assertEquals(new Color(0xC0, 0x39, 0x2B), BatteryStatus.color(new Level(10, false)));
        assertEquals(new Color(0xC0, 0x39, 0x2B), BatteryStatus.color(new Level(15, false)));
        assertEquals(new Color(0xE6, 0x7E, 0x22), BatteryStatus.color(new Level(16, false)));
        assertEquals(new Color(0xE6, 0x7E, 0x22), BatteryStatus.color(new Level(35, false)));
        assertEquals(new Color(0x27, 0xAE, 0x60), BatteryStatus.color(new Level(36, false)));
        assertEquals(new Color(0x27, 0xAE, 0x60), BatteryStatus.color(new Level(100, false)));
    }
}
