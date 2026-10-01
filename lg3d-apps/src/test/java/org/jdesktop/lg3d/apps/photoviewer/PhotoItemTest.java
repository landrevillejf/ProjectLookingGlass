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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers {@link PhotoItem}'s bean behaviour: titling, tags and rating clamps. */
class PhotoItemTest {

    @Test
    @DisplayName("of() derives the title from the file name minus extension")
    void derivesTitle() {
        PhotoItem item = PhotoItem.of("/home/me/Pictures/Sunset Beach.jpg");
        assertEquals("/home/me/Pictures/Sunset Beach.jpg", item.getPath());
        assertEquals("Sunset Beach", item.getTitle());
        assertEquals("Sunset Beach", item.toString());
    }

    @Test
    @DisplayName("blank and null paths and titles fall back safely")
    void blankFallbacks() {
        assertEquals("Untitled", PhotoItem.deriveTitle(""));
        assertEquals("Untitled", PhotoItem.deriveTitle(null));
        PhotoItem item = new PhotoItem(null, "  ");
        assertEquals("", item.getPath());
        assertEquals("Untitled", item.getTitle());
    }

    @Test
    @DisplayName("an explicit title wins over the derived one")
    void explicitTitle() {
        PhotoItem item = new PhotoItem("/tmp/a.png", "Holiday");
        assertEquals("Holiday", item.getTitle());
        item.setTitle("");
        assertEquals("a", item.getTitle(), "blank title re-derives from the path");
    }

    @Test
    @DisplayName("tags trim, ignore blanks and de-duplicate")
    void tagHandling() {
        PhotoItem item = PhotoItem.of("/tmp/a.png");
        assertTrue(item.addTag("beach"));
        assertTrue(item.addTag("  sunset "));
        assertFalse(item.addTag("beach"), "a duplicate tag is not re-added");
        assertFalse(item.addTag("   "), "a blank tag is ignored");
        assertFalse(item.addTag(null));
        assertTrue(item.hasTag("sunset"));
        assertEquals(2, item.getTags().size());
        assertTrue(item.removeTag("beach"));
        assertFalse(item.removeTag("beach"));
    }

    @Test
    @DisplayName("rating clamps into 0..MAX_RATING")
    void ratingClamps() {
        PhotoItem item = PhotoItem.of("/tmp/a.png");
        item.setRating(3);
        assertEquals(3, item.getRating());
        item.setRating(99);
        assertEquals(PhotoItem.MAX_RATING, item.getRating());
        item.setRating(-5);
        assertEquals(0, item.getRating());
    }
}
