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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SessionSnapshot}: the encode/decode round trip (including
 * fields that contain the delimiters, spaces and non-ASCII text), the empty
 * snapshot, the immutability and null-filtering of the window list, and the
 * tolerant decoding that drops null, blank, corrupt or malformed input without
 * throwing. Pure text handling; runs headless.
 */
class SessionSnapshotTest {

    private static WindowRecord record(String name, String command, String icon,
                                       int x, int y, int w, int h,
                                       boolean iconified, boolean maximized) {
        return new WindowRecord(name, command, icon, x, y, w, h, iconified, maximized);
    }

    @Test
    @DisplayName("an empty snapshot encodes to the empty string and back")
    void emptyRoundTrip() {
        assertTrue(SessionSnapshot.EMPTY.isEmpty());
        assertEquals(0, SessionSnapshot.EMPTY.size());
        assertEquals("", SessionSnapshot.EMPTY.encode());
        assertSame(SessionSnapshot.EMPTY, SessionSnapshot.decode(""));
        assertSame(SessionSnapshot.EMPTY, SessionSnapshot.decode(null));
        assertSame(SessionSnapshot.EMPTY, SessionSnapshot.decode("   "));
    }

    @Test
    @DisplayName("a single record survives the round trip exactly")
    void singleRoundTrip() {
        WindowRecord r = record("Calculator",
                "java org.jdesktop.lg3d.apps.calculator.Calculator",
                "resources/images/icon/calc.png", 120, 80, 340, 260, false, false);
        SessionSnapshot snapshot = new SessionSnapshot(List.of(r));
        assertEquals(List.of(r), SessionSnapshot.decode(snapshot.encode()).windows());
    }

    @Test
    @DisplayName("several records keep their order through the round trip")
    void multiRoundTrip() {
        List<WindowRecord> records = Arrays.asList(
                record("A", "java a.A", "a.png", 0, 0, 100, 100, false, false),
                record("B", "java b.B", null, 10, 20, 200, 150, true, false),
                record("C", "java c.C", "c.png", 30, 40, 300, 250, false, true));
        SessionSnapshot decoded =
                SessionSnapshot.decode(new SessionSnapshot(records).encode());
        assertEquals(records, decoded.windows());
        assertEquals(3, decoded.size());
        assertFalse(decoded.isEmpty());
    }

    @Test
    @DisplayName("a null icon resource round-trips as null, not as empty text")
    void nullIconRoundTrip() {
        WindowRecord r = record("A", "java a.A", null, 1, 2, 3, 4, false, false);
        WindowRecord decoded =
                SessionSnapshot.decode(new SessionSnapshot(List.of(r)).encode())
                        .windows().get(0);
        assertNull(decoded.iconResource());
        assertEquals(r, decoded);
    }

    @Test
    @DisplayName("fields containing delimiters, spaces and accents round-trip")
    void trickyFieldsRoundTrip() {
        WindowRecord r = record("My | App; Name",
                "java a.A --flag=\"x|y;z\" caf\u00e9",
                "resources/ic on|we;ird.png",
                -5, -6, 700, 800, true, true);
        SessionSnapshot decoded =
                SessionSnapshot.decode(new SessionSnapshot(List.of(r)).encode());
        assertEquals(List.of(r), decoded.windows());
        assertEquals("My | App; Name", decoded.windows().get(0).appName());
    }

    @Test
    @DisplayName("the window list is unmodifiable and copies its input")
    void listIsImmutableAndCopied() {
        List<WindowRecord> source = new ArrayList<>();
        source.add(record("A", "java a.A", null, 0, 0, 10, 10, false, false));
        SessionSnapshot snapshot = new SessionSnapshot(source);
        source.clear();                       // mutating the input...
        assertEquals(1, snapshot.size());     // ...does not change the snapshot
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.windows().add(
                        record("B", "java b.B", null, 0, 0, 10, 10, false, false)));
    }

    @Test
    @DisplayName("null records and a null list are filtered out")
    void nullsFiltered() {
        List<WindowRecord> withNulls = new ArrayList<>();
        withNulls.add(null);
        withNulls.add(record("A", "java a.A", null, 0, 0, 10, 10, false, false));
        withNulls.add(null);
        assertEquals(1, new SessionSnapshot(withNulls).size());
        assertTrue(new SessionSnapshot(null).isEmpty());
    }

    @Test
    @DisplayName("corrupt input decodes to empty rather than throwing")
    void corruptInputIsEmpty() {
        assertTrue(SessionSnapshot.decode("garbage-with-no-fields").isEmpty());
        assertTrue(SessionSnapshot.decode("a|b|c").isEmpty());       // too few fields
        assertTrue(SessionSnapshot.decode(";;;;;;").isEmpty());      // empty records
    }

    @Test
    @DisplayName("a malformed record is dropped but its neighbours survive")
    void partialCorruption() {
        WindowRecord good = record("Good", "java good.Good", "g.png",
                5, 6, 70, 80, false, false);
        String goodEncoded = new SessionSnapshot(List.of(good)).encode();
        // A record with a non-numeric width glued between two good ones.
        String bad = "Bad|java+bad.Bad||1|2|notanumber|4|false|false";
        String combined = goodEncoded + ";" + bad + ";" + goodEncoded;
        SessionSnapshot decoded = SessionSnapshot.decode(combined);
        assertEquals(2, decoded.size());
        assertEquals(good, decoded.windows().get(0));
        assertEquals(good, decoded.windows().get(1));
    }
}
