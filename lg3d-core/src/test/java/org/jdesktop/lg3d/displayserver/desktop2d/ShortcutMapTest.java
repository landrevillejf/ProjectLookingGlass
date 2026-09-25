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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ShortcutMap}'s pure parse/lookup: the default bindings resolve
 * to the expected action ids, a custom table parses, invalid specs and blank
 * actions are skipped, and an unbound or null stroke resolves to empty. No AWT
 * event plumbing is involved, so the suite is headless.
 */
class ShortcutMapTest {

    @Test
    @DisplayName("the defaults bind every documented action")
    void defaultsBindAllActions() {
        ShortcutMap map = ShortcutMap.defaults();
        assertEquals(18, map.size(), "one binding per default action");
        assertTrue(map.actions().containsAll(java.util.List.of(
                ShortcutMap.SHOW_DESKTOP, ShortcutMap.SNAP_LEFT,
                ShortcutMap.SNAP_RIGHT, ShortcutMap.SNAP_MAXIMIZE,
                ShortcutMap.RUN_DIALOG, ShortcutMap.OPEN_TERMINAL,
                ShortcutMap.WINDOW_CLOSE, ShortcutMap.WORKSPACE_NEXT,
                ShortcutMap.WORKSPACE_PREVIOUS)));
    }

    @Test
    @DisplayName("each default spec resolves to its action id")
    void defaultSpecsResolve() {
        ShortcutMap map = ShortcutMap.defaults();
        assertEquals(ShortcutMap.SHOW_DESKTOP, map.actionForSpec("control alt D").orElseThrow());
        assertEquals(ShortcutMap.SNAP_LEFT, map.actionForSpec("alt shift LEFT").orElseThrow());
        assertEquals(ShortcutMap.SNAP_RIGHT, map.actionForSpec("alt shift RIGHT").orElseThrow());
        assertEquals(ShortcutMap.SNAP_MAXIMIZE, map.actionForSpec("alt shift UP").orElseThrow());
        assertEquals(ShortcutMap.RUN_DIALOG, map.actionForSpec("alt F2").orElseThrow());
        assertEquals(ShortcutMap.OPEN_TERMINAL, map.actionForSpec("control alt T").orElseThrow());
        assertEquals(ShortcutMap.WINDOW_CLOSE, map.actionForSpec("control W").orElseThrow());
        assertEquals(ShortcutMap.WORKSPACE_NEXT,
                map.actionForSpec("alt shift PAGE_DOWN").orElseThrow());
        assertEquals(ShortcutMap.WORKSPACE_PREVIOUS,
                map.actionForSpec("alt shift PAGE_UP").orElseThrow());
        assertEquals(ShortcutMap.MOVE_TO_WORKSPACE_PREFIX + "0",
                map.actionForSpec("alt shift 1").orElseThrow());
        assertEquals(ShortcutMap.MOVE_TO_WORKSPACE_PREFIX + "8",
                map.actionForSpec("alt shift 9").orElseThrow());
    }

    @Test
    @DisplayName("the Alt+` switcher binding is deliberately not claimed")
    void doesNotBindSwitcher() {
        ShortcutMap map = ShortcutMap.defaults();
        assertFalse(map.isBound(KeyStroke.getKeyStroke("alt BACK_QUOTE")),
                "the window switcher must fall through");
        assertFalse(map.isBound(KeyStroke.getKeyStroke("alt shift BACK_QUOTE")));
    }

    @Test
    @DisplayName("lookup by KeyStroke matches lookup by spec")
    void lookupByStroke() {
        ShortcutMap map = ShortcutMap.defaults();
        KeyStroke stroke = KeyStroke.getKeyStroke("alt F2");
        assertEquals(map.actionForSpec("alt F2"), map.actionFor(stroke));
        assertEquals(ShortcutMap.RUN_DIALOG, map.actionFor(stroke).orElseThrow());
    }

    @Test
    @DisplayName("a custom table parses and resolves")
    void customTable() {
        ShortcutMap map = new ShortcutMap(Map.of("control X", "custom-action"));
        assertEquals(1, map.size());
        assertEquals("custom-action", map.actionForSpec("control X").orElseThrow());
        assertTrue(map.actionForSpec("alt F2").isEmpty(), "defaults are not inherited");
    }

    @Test
    @DisplayName("an unbound stroke resolves to empty")
    void unknownStroke() {
        assertTrue(ShortcutMap.defaults().actionForSpec("F9").isEmpty());
        assertTrue(ShortcutMap.defaults().actionFor(KeyStroke.getKeyStroke("F9")).isEmpty());
    }

    @Test
    @DisplayName("a null stroke resolves to empty and is never bound")
    void nullStroke() {
        assertTrue(ShortcutMap.defaults().actionFor(null).isEmpty());
        assertFalse(ShortcutMap.defaults().isBound(null));
    }

    @Test
    @DisplayName("invalid specs and null/blank actions are skipped, not fatal")
    void skipsBadEntries() {
        Map<String, String> table = new HashMap<>();
        table.put("not a real key", "whatever");       // unparseable spec
        table.put("control Y", null);                    // null action
        table.put("control Z", "   ");                   // blank action
        table.put("control B", "good");                  // the one valid entry
        ShortcutMap map = new ShortcutMap(table);
        assertEquals(1, map.size(), "only the valid entry survives");
        assertEquals("good", map.actionForSpec("control B").orElseThrow());
    }

    @Test
    @DisplayName("a null table yields an empty map")
    void nullTable() {
        assertEquals(0, new ShortcutMap(null).size());
    }

    @Test
    @DisplayName("parse handles null, blank, invalid and valid specs")
    void parseSpecs() {
        assertNull(ShortcutMap.parse(null));
        assertNull(ShortcutMap.parse(""));
        assertNull(ShortcutMap.parse("   "));
        assertNull(ShortcutMap.parse("this is not a keystroke"));
        assertNotNull(ShortcutMap.parse("control alt T"));
        assertNotNull(ShortcutMap.parse("  alt F2  "), "surrounding space is trimmed");
    }

    @Test
    @DisplayName("the default binding table is the eighteen documented combos")
    void defaultBindingsTable() {
        Map<String, String> bindings = ShortcutMap.defaultBindings();
        assertEquals(18, bindings.size());
        assertEquals(ShortcutMap.RUN_DIALOG, bindings.get("alt F2"));
        assertEquals(ShortcutMap.WORKSPACE_NEXT, bindings.get("alt shift PAGE_DOWN"));
        // Alt+Shift+<n> is 1-based on the key, 0-based in the action id.
        assertEquals(ShortcutMap.MOVE_TO_WORKSPACE_PREFIX + "4", bindings.get("alt shift 5"));
    }
}
