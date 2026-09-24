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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link WindowRecord}: its constructor validation, accessors, value
 * equality, and the on-screen clamping of {@link WindowRecord#restoredBounds}
 * that keeps a window saved on a bigger or offset screen reachable. Pure data
 * and geometry; runs headless.
 */
class WindowRecordTest {

    private static final String CMD = "java org.jdesktop.lg3d.apps.calculator.Calculator";

    private static WindowRecord record(int x, int y, int w, int h) {
        return new WindowRecord("Calculator", CMD, "resources/icon.png",
                x, y, w, h, false, false);
    }

    @Test
    @DisplayName("the accessors return exactly what was recorded")
    void accessors() {
        WindowRecord r = new WindowRecord("Calc", CMD, "icon.png",
                10, 20, 300, 200, true, false);
        assertEquals("Calc", r.appName());
        assertEquals(CMD, r.command());
        assertEquals("icon.png", r.iconResource());
        assertEquals(10, r.x());
        assertEquals(20, r.y());
        assertEquals(300, r.width());
        assertEquals(200, r.height());
        assertTrue(r.iconified());
        assertEquals(false, r.maximized());
        assertEquals(new Rectangle(10, 20, 300, 200), r.bounds());
    }

    @Test
    @DisplayName("a blank icon resource is normalised to null")
    void blankIconBecomesNull() {
        assertNull(new WindowRecord("a", CMD, "", 0, 0, 1, 1, false, false).iconResource());
        assertNull(new WindowRecord("a", CMD, "   ", 0, 0, 1, 1, false, false).iconResource());
        assertNull(new WindowRecord("a", CMD, null, 0, 0, 1, 1, false, false).iconResource());
    }

    @Test
    @DisplayName("a blank name or command is rejected")
    void rejectsBlankIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new WindowRecord(null, CMD, null, 0, 0, 10, 10, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new WindowRecord("  ", CMD, null, 0, 0, 10, 10, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new WindowRecord("a", null, null, 0, 0, 10, 10, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new WindowRecord("a", " ", null, 0, 0, 10, 10, false, false));
    }

    @Test
    @DisplayName("a non-positive dimension is rejected")
    void rejectsBadSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new WindowRecord("a", CMD, null, 0, 0, 0, 10, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new WindowRecord("a", CMD, null, 0, 0, 10, -1, false, false));
    }

    @Test
    @DisplayName("value equality covers every field")
    void equality() {
        WindowRecord a = new WindowRecord("n", CMD, "i", 1, 2, 3, 4, true, false);
        WindowRecord b = new WindowRecord("n", CMD, "i", 1, 2, 3, 4, true, false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "not a record");
        assertNotEquals(a, new WindowRecord("n", CMD, "i", 9, 2, 3, 4, true, false));
        assertNotEquals(a, new WindowRecord("n", CMD, "j", 1, 2, 3, 4, true, false));
        assertNotEquals(a, new WindowRecord("n", CMD, "i", 1, 2, 3, 4, false, false));
    }

    @Test
    @DisplayName("toString names the app and placement")
    void stringForm() {
        String s = record(5, 6, 7, 8).toString();
        assertTrue(s.contains("Calculator"), s);
        assertTrue(s.contains("5,6"), s);
    }

    @Test
    @DisplayName("a window already inside the desktop is unchanged")
    void restoredBoundsInside() {
        Rectangle desktop = new Rectangle(0, 0, 800, 600);
        assertEquals(new Rectangle(100, 100, 300, 200),
                record(100, 100, 300, 200).restoredBounds(desktop));
    }

    @Test
    @DisplayName("a window off the right/bottom edge is pulled back on-screen")
    void restoredBoundsOffEdge() {
        Rectangle desktop = new Rectangle(0, 0, 800, 600);
        Rectangle restored = record(900, 700, 300, 200).restoredBounds(desktop);
        assertEquals(new Rectangle(500, 400, 300, 200), restored);
    }

    @Test
    @DisplayName("a window off the left/top edge is clamped to the origin")
    void restoredBoundsNegative() {
        Rectangle desktop = new Rectangle(0, 0, 800, 600);
        assertEquals(new Rectangle(0, 0, 300, 200),
                record(-500, -400, 300, 200).restoredBounds(desktop));
    }

    @Test
    @DisplayName("a window bigger than the desktop is capped to it")
    void restoredBoundsOversized() {
        Rectangle desktop = new Rectangle(0, 0, 800, 600);
        assertEquals(new Rectangle(0, 0, 800, 600),
                record(0, 0, 2000, 1500).restoredBounds(desktop));
    }

    @Test
    @DisplayName("an offset desktop clamps relative to its origin")
    void restoredBoundsOffsetDesktop() {
        Rectangle desktop = new Rectangle(100, 50, 800, 600);
        assertEquals(new Rectangle(100, 50, 300, 200),
                record(0, 0, 300, 200).restoredBounds(desktop));
    }

    @Test
    @DisplayName("a null or empty desktop returns the raw bounds")
    void restoredBoundsNoDesktop() {
        WindowRecord r = record(900, 900, 300, 200);
        assertEquals(r.bounds(), r.restoredBounds(null));
        assertEquals(r.bounds(), r.restoredBounds(new Rectangle(0, 0, 0, 0)));
    }
}
