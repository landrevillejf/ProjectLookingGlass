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
package org.jdesktop.lg3d.apps.photoviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers {@link PhotoLibrary}'s de-duplicated insertion and filtering. */
class PhotoLibraryTest {

    private PhotoLibrary sample() {
        PhotoLibrary lib = new PhotoLibrary();
        PhotoItem a = PhotoItem.of("/tmp/alpha.png");
        a.addTag("beach");
        a.setRating(4);
        PhotoItem b = PhotoItem.of("/tmp/beta.png");
        b.setTitle("Beta shot");
        b.addTag("city");
        b.setRating(2);
        PhotoItem c = PhotoItem.of("/tmp/gamma.png");
        c.addTag("beach");
        lib.add(a);
        lib.add(b);
        lib.add(c);
        return lib;
    }

    @Test
    @DisplayName("add de-duplicates by path and rejects null/blank")
    void addDedups() {
        PhotoLibrary lib = new PhotoLibrary();
        assertTrue(lib.add(PhotoItem.of("/tmp/a.png")));
        assertFalse(lib.add(PhotoItem.of("/tmp/a.png")), "same path is a duplicate");
        assertFalse(lib.add(null));
        assertFalse(lib.add(new PhotoItem("", "x")), "a blank path is rejected");
        assertEquals(1, lib.size());
    }

    @Test
    @DisplayName("addAll counts only newly added photos")
    void addAllCounts() {
        PhotoLibrary lib = sample();
        int added = lib.addAll(List.of(
                PhotoItem.of("/tmp/delta.png"), PhotoItem.of("/tmp/alpha.png")));
        assertEquals(1, added, "alpha.png was already present");
        assertEquals(4, lib.size());
        assertEquals(0, lib.addAll(null));
    }

    @Test
    @DisplayName("findByPath / get / remove locate and drop photos")
    void findAndRemove() {
        PhotoLibrary lib = sample();
        assertNotNull(lib.findByPath("/tmp/beta.png"));
        assertNull(lib.findByPath("/tmp/missing.png"));
        assertNull(lib.findByPath(null));
        assertEquals("alpha", lib.get(0).getTitle());
        assertNull(lib.get(99));
        assertTrue(lib.removeByPath("/tmp/beta.png"));
        assertFalse(lib.removeByPath("/tmp/beta.png"));
        assertFalse(lib.remove(-1));
        assertEquals(2, lib.size());
    }

    @Test
    @DisplayName("allTags is the union of every photo's tags")
    void unionsTags() {
        PhotoLibrary lib = sample();
        assertEquals(List.of("beach", "city"), List.copyOf(lib.allTags()));
    }

    @Test
    @DisplayName("filter narrows by tag, keyword and minimum rating")
    void filters() {
        PhotoLibrary lib = sample();
        assertEquals(2, lib.filter("beach", null, 0).size());
        assertEquals(1, lib.filter("beach", null, 4).size(), "only alpha is 4-star");
        assertEquals(1, lib.filter(null, "shot", 0).size(), "keyword matches title");
        assertEquals(3, lib.filter("", "", 0).size(), "blank filters match everything");
        assertEquals(0, lib.filter("nope", null, 0).size());
    }

    @Test
    @DisplayName("all() is a snapshot and clear empties the library")
    void snapshotAndClear() {
        PhotoLibrary lib = sample();
        List<PhotoItem> snapshot = lib.all();
        lib.clear();
        assertEquals(3, snapshot.size(), "the snapshot is unaffected by clear");
        assertTrue(lib.isEmpty());
        assertEquals(0, lib.size());
    }
}
