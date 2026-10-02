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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the in-memory {@link BookmarkStore}. */
class BookmarkStoreTest {

    @Test
    @DisplayName("a new store is empty")
    void emptyByDefault() {
        BookmarkStore store = new BookmarkStore();
        assertTrue(store.isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("add stores a bookmark and returns it")
    void addReturnsBookmark() {
        BookmarkStore store = new BookmarkStore();
        Bookmark b = store.add("Example", "https://example.com");
        assertNotNull(b);
        assertEquals("Example", b.getTitle());
        assertEquals("https://example.com", b.getUrl());
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("a blank URL is ignored")
    void blankUrlIgnored() {
        BookmarkStore store = new BookmarkStore();
        assertNull(store.add("Title", "  "));
        assertNull(store.add((Bookmark) null, false));
        assertTrue(store.isEmpty());
    }

    @Test
    @DisplayName("adding the same URL twice updates rather than duplicates")
    void dedupByUrl() {
        BookmarkStore store = new BookmarkStore();
        store.add("First", "https://example.com");
        store.add("Second", "https://example.com");
        assertEquals(1, store.size());
        assertEquals("Second", store.findByUrl("https://example.com").getTitle());
    }

    @Test
    @DisplayName("update replaces by id, or appends an unknown id")
    void updateById() {
        BookmarkStore store = new BookmarkStore();
        Bookmark b = store.add("Old", "https://example.com");
        b.setTitle("New");
        assertTrue(store.update(b));
        assertEquals("New", store.findById(b.getId()).getTitle());

        Bookmark stranger = new Bookmark("Stranger", "https://stranger.com");
        assertTrue(store.update(stranger));
        assertEquals(2, store.size());
        assertFalse(store.update(null));
    }

    @Test
    @DisplayName("remove deletes by id")
    void removeById() {
        BookmarkStore store = new BookmarkStore();
        Bookmark b = store.add("Example", "https://example.com");
        assertTrue(store.remove(b.getId()));
        assertTrue(store.isEmpty());
        assertFalse(store.remove(b.getId()));
        assertFalse(store.remove(null));
    }

    @Test
    @DisplayName("contains / findByUrl reflect the collection")
    void lookups() {
        BookmarkStore store = new BookmarkStore();
        store.add("Example", "https://example.com");
        assertTrue(store.contains("https://example.com"));
        assertFalse(store.contains("https://other.com"));
        assertNotNull(store.findByUrl("https://example.com"));
        assertNull(store.findByUrl(null));
        assertNull(store.findById("missing"));
    }

    @Test
    @DisplayName("list is unmodifiable")
    void listUnmodifiable() {
        BookmarkStore store = new BookmarkStore();
        store.add("Example", "https://example.com");
        assertThrows(UnsupportedOperationException.class,
                () -> store.list().add(new Bookmark("x", "https://x.com")));
    }

    @Test
    @DisplayName("folders group bookmarks and report distinct paths")
    void folders() {
        BookmarkStore store = new BookmarkStore();
        store.add("A", "https://a.com", "Work");
        store.add("B", "https://b.com", "Work");
        store.add("C", "https://c.com", "Play");
        store.add("D", "https://d.com");
        assertEquals(2, store.inFolder("Work").size());
        assertEquals(1, store.inFolder("Play").size());
        assertEquals(1, store.inFolder("").size(), "the root folder holds D");
        assertTrue(store.folders().contains("Work"));
        assertTrue(store.folders().contains("Play"));
        assertEquals(2, store.folders().size());
    }

    @Test
    @DisplayName("the seeded constructor copies its input")
    void seededCopies() {
        List<Bookmark> seed = new ArrayList<>();
        seed.add(new Bookmark("A", "https://a.com"));
        BookmarkStore store = new BookmarkStore(seed);
        seed.clear();
        assertEquals(1, store.size(), "the store keeps its own copy");
    }

    @Test
    @DisplayName("clear empties the store")
    void clear() {
        BookmarkStore store = new BookmarkStore();
        store.add("A", "https://a.com");
        store.clear();
        assertTrue(store.isEmpty());
    }
}
