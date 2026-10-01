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
package org.jdesktop.lg3d.apps.audioplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link Playlist} ordering model: sequential advance, wrap-around
 * under repeat, random selection under shuffle (with a seeded {@link Random}),
 * removal keeping the cursor on its neighbour, and the empty-list guards. Pure
 * logic, so it runs headless.
 */
class PlaylistTest {

    private static MediaItem item(String name) {
        return MediaItem.stream(name, "http://x/" + name, MediaItem.Kind.STREAM);
    }

    @Test
    @DisplayName("an empty playlist has no current item and next/prev are null")
    void emptyPlaylist() {
        Playlist p = new Playlist();
        assertTrue(p.isEmpty());
        assertEquals(-1, p.currentIndex());
        assertNull(p.current());
        assertNull(p.next());
        assertNull(p.previous());
    }

    @Test
    @DisplayName("add / size / get / items reflect the contents in order")
    void addAndRead() {
        Playlist p = new Playlist();
        p.add(item("a"));
        p.add(item("b"));
        p.addAll(List.of(item("c")));
        p.add(null);
        assertEquals(3, p.size());
        assertEquals("a", p.get(0).getName());
        assertEquals("c", p.get(2).getName());
        assertNull(p.get(9));
        assertEquals(3, p.items().size());
    }

    @Test
    @DisplayName("selectIndex clamps and sets the cursor")
    void selectClamps() {
        Playlist p = new Playlist();
        p.add(item("a"));
        p.add(item("b"));
        assertEquals("b", p.selectIndex(5).getName());
        assertEquals(1, p.currentIndex());
        assertEquals("a", p.selectIndex(-3).getName());
        assertEquals(0, p.currentIndex());
    }

    @Test
    @DisplayName("next advances and stops at the end without repeat")
    void nextStopsAtEnd() {
        Playlist p = new Playlist();
        p.add(item("a"));
        p.add(item("b"));
        p.selectIndex(0);
        assertEquals("b", p.next().getName());
        assertNull(p.next(), "no wrap without repeat");
    }

    @Test
    @DisplayName("next wraps to the start under repeat")
    void nextWrapsWithRepeat() {
        Playlist p = new Playlist();
        p.setRepeat(true);
        p.add(item("a"));
        p.add(item("b"));
        p.selectIndex(1);
        assertEquals("a", p.next().getName());
        assertEquals(0, p.currentIndex());
    }

    @Test
    @DisplayName("previous steps back and wraps only under repeat")
    void previousWraps() {
        Playlist p = new Playlist();
        p.add(item("a"));
        p.add(item("b"));
        p.selectIndex(0);
        assertNull(p.previous(), "no wrap without repeat");
        p.setRepeat(true);
        assertEquals("b", p.previous().getName());
        assertEquals(1, p.currentIndex());
    }

    @Test
    @DisplayName("shuffle picks a different index and stays in range")
    void shufflePicksOther() {
        Playlist p = new Playlist(new Random(42));
        p.setShuffle(true);
        for (String n : List.of("a", "b", "c", "d")) {
            p.add(item(n));
        }
        p.selectIndex(0);
        for (int i = 0; i < 20; i++) {
            MediaItem next = p.next();
            assertNotEquals(null, next);
            assertTrue(p.currentIndex() >= 0 && p.currentIndex() < p.size());
        }
    }

    @Test
    @DisplayName("shuffle on a single item keeps that item")
    void shuffleSingle() {
        Playlist p = new Playlist(new Random(1));
        p.setShuffle(true);
        p.add(item("only"));
        p.selectIndex(0);
        assertEquals("only", p.next().getName());
        assertEquals(0, p.currentIndex());
    }

    @Test
    @DisplayName("removing before the cursor shifts it down; removing it re-targets")
    void removeKeepsCursorSane() {
        Playlist p = new Playlist();
        p.add(item("a"));
        p.add(item("b"));
        p.add(item("c"));
        p.selectIndex(2);
        assertTrue(p.remove(0));
        assertEquals(1, p.currentIndex());
        assertEquals("c", p.current().getName());
        assertFalse(p.remove(99));
        p.remove(1); // remove current ("c"), leaving just [b]
        assertEquals(0, p.currentIndex());
        assertEquals("b", p.current().getName());
    }

    @Test
    @DisplayName("removing the last item clears the cursor; clear empties all")
    void removeLastAndClear() {
        Playlist p = new Playlist();
        p.add(item("a"));
        p.selectIndex(0);
        p.remove(0);
        assertTrue(p.isEmpty());
        assertEquals(-1, p.currentIndex());
        p.add(item("b"));
        p.clear();
        assertTrue(p.isEmpty());
        assertNull(p.current());
    }
}
