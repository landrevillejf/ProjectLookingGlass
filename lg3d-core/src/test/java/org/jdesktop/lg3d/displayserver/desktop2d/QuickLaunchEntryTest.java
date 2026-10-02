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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link QuickLaunchEntry}: the encode/decode round trip (including
 * names, commands and icon paths that contain the delimiters, spaces and
 * non-ASCII text), the empty and null list, the null-icon and null-field
 * fallbacks, the {@link ItemSpec} bridge, and the tolerant decoding that drops
 * null, blank, corrupt or malformed input - and any entry with a blank command
 * - without throwing. Pure text handling; runs headless.
 */
class QuickLaunchEntryTest {

    private static QuickLaunchEntry entry(String name, String command, String icon) {
        return new QuickLaunchEntry(name, command, icon);
    }

    @Test
    @DisplayName("an empty or null list encodes to the empty string and back")
    void emptyRoundTrip() {
        assertEquals("", QuickLaunchEntry.encodeList(null));
        assertEquals("", QuickLaunchEntry.encodeList(Collections.emptyList()));
        assertTrue(QuickLaunchEntry.decodeList("").isEmpty());
        assertTrue(QuickLaunchEntry.decodeList(null).isEmpty());
        assertTrue(QuickLaunchEntry.decodeList("   ").isEmpty());
    }

    @Test
    @DisplayName("a single entry survives the round trip exactly")
    void singleRoundTrip() {
        QuickLaunchEntry e = entry("Calculator",
                "java org.jdesktop.lg3d.apps.calculator.Calculator",
                "resources/images/icon/calc.png");
        List<QuickLaunchEntry> decoded =
                QuickLaunchEntry.decodeList(QuickLaunchEntry.encodeList(List.of(e)));
        assertEquals(1, decoded.size());
        assertEntryEquals(e, decoded.get(0));
    }

    @Test
    @DisplayName("several entries keep their order through the round trip")
    void multiRoundTrip() {
        List<QuickLaunchEntry> entries = Arrays.asList(
                entry("A", "java a.A", "a.png"),
                entry("B", "java b.B", null),
                entry("C", "java c.C", "c.png"));
        List<QuickLaunchEntry> decoded =
                QuickLaunchEntry.decodeList(QuickLaunchEntry.encodeList(entries));
        assertEquals(3, decoded.size());
        assertEntryEquals(entries.get(0), decoded.get(0));
        assertEntryEquals(entries.get(1), decoded.get(1));
        assertEntryEquals(entries.get(2), decoded.get(2));
    }

    @Test
    @DisplayName("a null icon resource round-trips as null, not as empty text")
    void nullIconRoundTrip() {
        QuickLaunchEntry decoded = QuickLaunchEntry
                .decodeList(QuickLaunchEntry.encodeList(
                        List.of(entry("A", "java a.A", null))))
                .get(0);
        assertNull(decoded.iconResource());
    }

    @Test
    @DisplayName("fields containing delimiters, spaces and accents round-trip")
    void trickyFieldsRoundTrip() {
        QuickLaunchEntry e = entry("My | App; Name",
                "java a.A --flag=\"x|y;z\" caf\u00e9",
                "resources/ic on|we;ird.png");
        QuickLaunchEntry decoded = QuickLaunchEntry
                .decodeList(QuickLaunchEntry.encodeList(List.of(e))).get(0);
        assertEntryEquals(e, decoded);
    }

    @Test
    @DisplayName("null name and command fall back to the empty string")
    void nullFieldsFallBackToEmpty() {
        QuickLaunchEntry e = entry(null, null, null);
        assertEquals("", e.name());
        assertEquals("", e.command());
        assertNull(e.iconResource());
    }

    @Test
    @DisplayName("a null entry in the list is skipped when encoding")
    void nullEntrySkipped() {
        List<QuickLaunchEntry> withNull = Arrays.asList(
                null, entry("A", "java a.A", null), null);
        List<QuickLaunchEntry> decoded =
                QuickLaunchEntry.decodeList(QuickLaunchEntry.encodeList(withNull));
        assertEquals(1, decoded.size());
        assertEquals("A", decoded.get(0).name());
    }

    @Test
    @DisplayName("corrupt input decodes to empty rather than throwing")
    void corruptInputIsEmpty() {
        assertTrue(QuickLaunchEntry.decodeList("garbage-with-no-fields").isEmpty());
        assertTrue(QuickLaunchEntry.decodeList("a|b").isEmpty());   // too few fields
        assertTrue(QuickLaunchEntry.decodeList("a|b|c|d").isEmpty()); // too many
        assertTrue(QuickLaunchEntry.decodeList(";;;;;;").isEmpty());  // empty records
    }

    @Test
    @DisplayName("an entry with a blank command is dropped, its neighbours kept")
    void blankCommandDropped() {
        QuickLaunchEntry good = entry("Good", "java good.Good", "g.png");
        String goodEncoded = QuickLaunchEntry.encodeList(List.of(good));
        // A well-formed entry whose command decodes to blank cannot launch.
        String blankCommand = "Bad||b.png";
        List<QuickLaunchEntry> decoded = QuickLaunchEntry.decodeList(
                goodEncoded + ";" + blankCommand + ";" + goodEncoded);
        assertEquals(2, decoded.size());
        assertEntryEquals(good, decoded.get(0));
        assertEntryEquals(good, decoded.get(1));
    }

    @Test
    @DisplayName("of(ItemSpec) and toItemSpec() bridge the start-menu item")
    void itemSpecBridge() {
        ItemSpec item = new ItemSpec("Mail", "java mail.Mail", "Read mail",
                "Internet", "resources/images/icon/mail.png");
        QuickLaunchEntry e = QuickLaunchEntry.of(item);
        assertEquals("Mail", e.name());
        assertEquals("java mail.Mail", e.command());
        assertEquals("resources/images/icon/mail.png", e.iconResource());

        ItemSpec back = e.toItemSpec();
        assertEquals("Mail", back.getName());
        assertEquals("java mail.Mail", back.getCommand());
        assertEquals("resources/images/icon/mail.png", back.getIconResource());
        assertNull(back.getDesc());
        assertNull(back.getMenuGroup());
    }

    @Test
    @DisplayName("toString names the entry and its launch command")
    void stringForm() {
        assertTrue(entry("Mail", "java mail.Mail", null).toString()
                .contains("java mail.Mail"));
    }

    /** Field-wise equality, since {@link QuickLaunchEntry} carries no equals(). */
    private static void assertEntryEquals(QuickLaunchEntry expected, QuickLaunchEntry actual) {
        assertEquals(expected.name(), actual.name(), "name");
        assertEquals(expected.command(), actual.command(), "command");
        assertEquals(expected.iconResource(), actual.iconResource(), "iconResource");
    }
}
