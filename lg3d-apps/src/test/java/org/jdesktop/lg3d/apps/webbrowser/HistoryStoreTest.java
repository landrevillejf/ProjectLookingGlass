/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the in-memory {@link HistoryStore}. */
class HistoryStoreTest {

    @Test
    @DisplayName("record keeps entries newest-first")
    void newestFirst() {
        HistoryStore store = new HistoryStore();
        store.record("https://a.com", "A");
        store.record("https://b.com", "B");
        assertEquals("https://b.com", store.list().get(0).getUrl());
        assertEquals("https://a.com", store.list().get(1).getUrl());
        assertEquals(2, store.size());
    }

    @Test
    @DisplayName("re-visiting a URL de-duplicates and bumps the count")
    void dedupBumpsCount() {
        HistoryStore store = new HistoryStore();
        store.record("https://a.com", "A");
        store.record("https://b.com", "B");
        HistoryEntry again = store.record("https://a.com", "A again");
        assertEquals(2, store.size());
        assertEquals(2, again.getVisitCount());
        assertEquals("https://a.com", store.list().get(0).getUrl(), "moved to front");
        assertEquals("A again", again.getTitle());
    }

    @Test
    @DisplayName("transient and blank URLs are never recorded")
    void transientSkipped() {
        HistoryStore store = new HistoryStore();
        assertNull(store.record(null, "x"));
        assertNull(store.record("   ", "x"));
        assertNull(store.record("about:blank", "x"));
        assertNull(store.record("data:text/html,x", "x"));
        assertNull(store.record("javascript:void(0)", "x"));
        assertNull(store.record("view-source:https://a.com", "x"));
        assertNull(store.record("blob:https://a.com/1", "x"));
        assertTrue(store.isEmpty());
    }

    @Test
    @DisplayName("the store trims to its capacity")
    void capacityCap() {
        HistoryStore store = new HistoryStore(3);
        store.record("https://1.com", "1");
        store.record("https://2.com", "2");
        store.record("https://3.com", "3");
        store.record("https://4.com", "4");
        assertEquals(3, store.size());
        assertEquals("https://4.com", store.list().get(0).getUrl());
        assertNull(store.find("https://1.com"), "the oldest entry was dropped");
    }

    @Test
    @DisplayName("shrinking the capacity trims immediately")
    void setCapacityTrims() {
        HistoryStore store = new HistoryStore(10);
        for (int i = 0; i < 6; i++) {
            store.record("https://" + i + ".com", "n" + i);
        }
        store.setCapacity(2);
        assertEquals(2, store.size());
        store.setCapacity(0);
        assertEquals(HistoryStore.DEFAULT_CAPACITY, store.getCapacity(),
                "a non-positive capacity falls back to the default");
    }

    @Test
    @DisplayName("list is unmodifiable and find locates by URL")
    void unmodifiableAndFind() {
        HistoryStore store = new HistoryStore();
        store.record("https://a.com", "A");
        assertThrows(UnsupportedOperationException.class,
                () -> store.list().add(new HistoryEntry("https://x.com", "x")));
        assertNotNull(store.find("https://a.com"));
        assertNull(store.find("https://missing.com"));
        assertNull(store.find(null));
    }

    @Test
    @DisplayName("the seeded constructor copies and trims to capacity")
    void seededCopies() {
        List<HistoryEntry> seed = new ArrayList<>();
        seed.add(new HistoryEntry("https://a.com", "A"));
        seed.add(new HistoryEntry("https://b.com", "B"));
        HistoryStore store = new HistoryStore(seed, 1);
        seed.clear();
        assertEquals(1, store.size());
        assertEquals("https://a.com", store.list().get(0).getUrl(),
                "the newest seeded entry survives the capacity of 1");
    }

    @Test
    @DisplayName("clear empties the store")
    void clear() {
        HistoryStore store = new HistoryStore();
        store.record("https://a.com", "A");
        store.clear();
        assertTrue(store.isEmpty());
    }
}
