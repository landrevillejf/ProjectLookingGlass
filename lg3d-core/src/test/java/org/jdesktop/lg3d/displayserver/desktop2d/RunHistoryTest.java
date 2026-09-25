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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the run dialog's command history: add / promote / trim, blank
 * rejection, the encode-decode persistence round trip (including entries with
 * spaces and separators), tolerance of a corrupt value, and the save/load/clear
 * round trip through an in-memory {@link RunHistoryStore} — the same seam
 * convention {@code SessionManagerTest} uses so no test writes to the developer's
 * home preferences.
 */
class RunHistoryTest {

    /** An in-memory store that persists through the encoded form, like the real one. */
    private static final class FakeStore implements RunHistoryStore {
        private String encoded = "";

        @Override
        public void save(RunHistory history) {
            encoded = (history == null) ? "" : history.encode();
        }

        @Override
        public RunHistory load() {
            return RunHistory.decode(encoded);
        }

        @Override
        public void clear() {
            encoded = "";
        }
    }

    @Test
    @DisplayName("entries are kept most-recent-first")
    void mostRecentFirst() {
        RunHistory history = new RunHistory();
        history.add("a");
        history.add("b");
        history.add("c");
        assertEquals(List.of("c", "b", "a"), history.entries());
        assertEquals(3, history.size());
    }

    @Test
    @DisplayName("re-adding an entry promotes it instead of duplicating")
    void addPromotesDuplicate() {
        RunHistory history = new RunHistory();
        history.add("a");
        history.add("b");
        history.add("a");
        assertEquals(List.of("a", "b"), history.entries());
    }

    @Test
    @DisplayName("entries are trimmed to the maximum, keeping the newest")
    void trimsToMax() {
        RunHistory history = new RunHistory();
        for (int i = 0; i < RunHistory.MAX_ENTRIES + 5; i++) {
            history.add("cmd" + i);
        }
        assertEquals(RunHistory.MAX_ENTRIES, history.size());
        assertEquals("cmd" + (RunHistory.MAX_ENTRIES + 4), history.entries().get(0));
        // The oldest entries are the ones dropped.
        assertTrue(history.entries().stream().noneMatch(e -> e.equals("cmd0")));
    }

    @Test
    @DisplayName("blank and null entries are ignored")
    void blankIgnored() {
        RunHistory history = new RunHistory();
        history.add(null);
        history.add("");
        history.add("   ");
        assertTrue(history.isEmpty());
        history.add("  xterm -ls  ");
        assertEquals(List.of("xterm -ls"), history.entries(), "entries are trimmed");
    }

    @Test
    @DisplayName("clear empties the history")
    void clearEmpties() {
        RunHistory history = new RunHistory();
        history.add("a");
        history.clear();
        assertTrue(history.isEmpty());
        assertEquals(0, history.size());
    }

    @Test
    @DisplayName("encode then decode round-trips the entries in order")
    void encodeDecodeRoundTrip() {
        RunHistory history = new RunHistory();
        history.add("firefox --private-window");
        history.add("java org.jdesktop.lg3d.apps.calculator.Calculator");
        history.add("xterm -e \"sh; echo hi\"");
        RunHistory restored = RunHistory.decode(history.encode());
        assertEquals(history.entries(), restored.entries());
    }

    @Test
    @DisplayName("an empty history encodes to an empty string and decodes back empty")
    void emptyRoundTrip() {
        assertEquals("", new RunHistory().encode());
        assertTrue(RunHistory.decode("").isEmpty());
        assertTrue(RunHistory.decode(null).isEmpty());
        assertTrue(RunHistory.decode("   ").isEmpty());
    }

    @Test
    @DisplayName("a malformed entry is dropped and the rest are kept")
    void malformedEntrySkipped() {
        // "%zz" is an illegal percent-escape; the valid neighbour survives.
        RunHistory restored = RunHistory.decode("%zz\nfirefox");
        assertEquals(List.of("firefox"), restored.entries());
    }

    @Test
    @DisplayName("save/load/clear round-trips through the store's encoded form")
    void storeRoundTrip() {
        FakeStore store = new FakeStore();
        RunHistory history = new RunHistory();
        history.add("xterm");
        history.add("firefox");
        store.save(history);
        assertEquals(List.of("firefox", "xterm"), store.load().entries());
        store.clear();
        assertTrue(store.load().isEmpty());
    }

    @Test
    @DisplayName("a history loaded from the store keeps accepting new entries")
    void loadedHistoryIsUsable() {
        FakeStore store = new FakeStore();
        RunHistory first = new RunHistory();
        first.add("xterm");
        store.save(first);

        RunHistory reloaded = store.load();
        reloaded.add("firefox");
        store.save(reloaded);
        assertEquals(List.of("firefox", "xterm"), store.load().entries());
    }
}
