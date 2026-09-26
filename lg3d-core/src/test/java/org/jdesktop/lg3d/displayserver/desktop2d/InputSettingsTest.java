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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure seams of {@link InputSettings}: the {@code xset q} token and
 * number parsing, the pointer/auto-repeat parsing and the {@code xset m} /
 * {@code xset r} command builders. The {@code xset} probes themselves need an X
 * server and are exercised only through their graceful-degradation contract, not
 * asserted here.
 */
class InputSettingsTest {

    private static final String QUERY =
            "Keyboard Control:\n"
          + "  auto repeat:  on    key click percent:  0    LED mask:  00000002\n"
          + "  auto repeating keys:  00ffffffdffffbbf\n"
          + "  bell percent:  50    bell pitch:  400    bell duration:  100\n"
          + "Pointer Control:\n"
          + "  acceleration:  2    threshold:  4\n"
          + "Screen Saver:\n"
          + "  prefer blanking:  yes    timeout:  0    cycle:  0\n";

    @Test
    @DisplayName("the first token after a key is read from a line")
    void parsesTokenAfter() {
        assertEquals("2", InputSettings.tokenAfter("acceleration:  2    threshold:  4", "acceleration:"));
        assertEquals("4", InputSettings.tokenAfter("acceleration:  2    threshold:  4", "threshold:"));
        assertEquals("", InputSettings.tokenAfter("nothing here", "acceleration:"), "absent key");
        assertEquals("", InputSettings.tokenAfter(null, "acceleration:"));
    }

    @Test
    @DisplayName("a numeric token may be a decimal or an a/b fraction")
    void parsesNumber() {
        assertEquals(2.0, InputSettings.parseNumber("2"), 1e-9);
        assertEquals(1.5, InputSettings.parseNumber("3/2"), 1e-9);
        assertEquals(1.5, InputSettings.parseNumber("1.5"), 1e-9);
        assertTrue(Double.isNaN(InputSettings.parseNumber("4/0")), "division by zero is NaN");
        assertTrue(Double.isNaN(InputSettings.parseNumber("")));
        assertTrue(Double.isNaN(InputSettings.parseNumber("abc")));
        assertTrue(Double.isNaN(InputSettings.parseNumber(null)));
    }

    @Test
    @DisplayName("the pointer-control line yields acceleration and threshold")
    void parsesPointer() {
        InputSettings.Pointer p = InputSettings.parsePointer(QUERY);
        assertEquals(2.0, p.acceleration(), 1e-9);
        assertEquals(4, p.threshold());
        assertNull(InputSettings.parsePointer(null));
        assertNull(InputSettings.parsePointer("no pointer line here\n"));
    }

    @Test
    @DisplayName("a fractional acceleration is reduced to a double")
    void parsesFractionalPointer() {
        InputSettings.Pointer p = InputSettings.parsePointer("acceleration: 3/2    threshold:  10\n");
        assertEquals(1.5, p.acceleration(), 1e-9);
        assertEquals(10, p.threshold());
    }

    @Test
    @DisplayName("the auto-repeat flag reads on/off and ignores the key-mask line")
    void parsesAutoRepeat() {
        assertEquals(Boolean.TRUE, InputSettings.parseAutoRepeat(QUERY));
        assertEquals(Boolean.FALSE, InputSettings.parseAutoRepeat("  auto repeat:  off\n"));
        assertNull(InputSettings.parseAutoRepeat("  auto repeating keys:  00ffffff\n"),
                "the 'auto repeating keys' mask line is not the flag line");
        assertNull(InputSettings.parseAutoRepeat(null));
    }

    @Test
    @DisplayName("the commands target xset m and xset r")
    void commandBuilders() {
        assertArrayEquals(new String[] {"xset", "m", "2", "4"},
                InputSettings.mouseCommand("2", "4"));
        assertArrayEquals(new String[] {"xset", "r", "rate", "500", "25"},
                InputSettings.repeatRateCommand(500, 25));
        assertArrayEquals(new String[] {"xset", "r", "on"},
                InputSettings.repeatToggleCommand(true));
        assertArrayEquals(new String[] {"xset", "r", "off"},
                InputSettings.repeatToggleCommand(false));
    }
}
