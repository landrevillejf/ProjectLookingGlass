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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link WallpaperSlideshow}: the wraparound advance/retreat, the jump
 * accessor, and the defensive empty/single-image/null handling. The model is
 * pure, so the suite is deterministic and headless.
 */
class WallpaperSlideshowTest {

    private static URL url(String file) {
        try {
            return URI.create("file:///tmp/" + file).toURL();
        } catch (MalformedURLException e) {
            throw new AssertionError(e);
        }
    }

    private static List<URL> three() {
        return new ArrayList<>(Arrays.asList(url("a.jpg"), url("b.jpg"), url("c.jpg")));
    }

    @Test
    @DisplayName("a fresh slideshow starts on the first image")
    void startsOnFirst() {
        WallpaperSlideshow show = new WallpaperSlideshow(three());
        assertEquals(3, show.size());
        assertEquals(0, show.index());
        assertEquals(url("a.jpg"), show.current());
    }

    @Test
    @DisplayName("next() advances and wraps to the first after the last")
    void nextWraps() {
        WallpaperSlideshow show = new WallpaperSlideshow(three());
        assertEquals(url("b.jpg"), show.next());
        assertEquals(url("c.jpg"), show.next());
        assertEquals(url("a.jpg"), show.next(), "wraps to the first");
        assertEquals(0, show.index());
    }

    @Test
    @DisplayName("previous() retreats and wraps to the last before the first")
    void previousWraps() {
        WallpaperSlideshow show = new WallpaperSlideshow(three());
        assertEquals(url("c.jpg"), show.previous(), "wraps to the last");
        assertEquals(url("b.jpg"), show.previous());
        assertEquals(url("a.jpg"), show.previous());
        assertEquals(0, show.index());
    }

    @Test
    @DisplayName("at() jumps to an index, wrapping out-of-range values")
    void atWraps() {
        WallpaperSlideshow show = new WallpaperSlideshow(three());
        assertEquals(url("c.jpg"), show.at(2));
        assertEquals(2, show.index());
        assertEquals(url("a.jpg"), show.at(3), "at(size) lands on the first");
        assertEquals(url("c.jpg"), show.at(-1), "at(-1) lands on the last");
    }

    @Test
    @DisplayName("an empty slideshow is inert and never throws")
    void emptyIsInert() {
        WallpaperSlideshow show = new WallpaperSlideshow(List.of());
        assertTrue(show.isEmpty());
        assertEquals(0, show.size());
        assertNull(show.current());
        assertNull(show.next());
        assertNull(show.previous());
        assertNull(show.at(5));
        assertEquals(0, show.index(), "the position is untouched while empty");
    }

    @Test
    @DisplayName("a null image list is treated as empty")
    void nullListIsEmpty() {
        WallpaperSlideshow show = new WallpaperSlideshow(null);
        assertTrue(show.isEmpty());
        assertNull(show.next());
    }

    @Test
    @DisplayName("null entries are dropped when the list is built")
    void nullsDropped() {
        List<URL> withNulls = new ArrayList<>(Arrays.asList(url("a.jpg"), null, url("b.jpg")));
        WallpaperSlideshow show = new WallpaperSlideshow(withNulls);
        assertEquals(2, show.size());
        assertEquals(url("a.jpg"), show.current());
        assertEquals(url("b.jpg"), show.next());
    }

    @Test
    @DisplayName("a single image repeats rather than advancing")
    void singleImageRepeats() {
        WallpaperSlideshow show = new WallpaperSlideshow(List.of(url("only.jpg")));
        assertEquals(1, show.size());
        assertSame(show.current(), show.next());
        assertEquals(url("only.jpg"), show.next());
        assertEquals(url("only.jpg"), show.previous());
        assertEquals(0, show.index());
    }

    @Test
    @DisplayName("setImages() replaces the list and rewinds to the first")
    void setImagesRewinds() {
        WallpaperSlideshow show = new WallpaperSlideshow(three());
        show.next();
        show.next();
        assertEquals(2, show.index());
        show.setImages(Arrays.asList(url("x.jpg"), url("y.jpg")));
        assertEquals(0, show.index(), "a new list rewinds");
        assertEquals(2, show.size());
        assertEquals(url("x.jpg"), show.current());
    }

    @Test
    @DisplayName("setImages(null) empties the slideshow")
    void setImagesNullEmpties() {
        WallpaperSlideshow show = new WallpaperSlideshow(three());
        show.setImages(null);
        assertTrue(show.isEmpty());
        assertNull(show.current());
    }

    @Test
    @DisplayName("images() preserves the given order")
    void imagesPreserveOrder() {
        WallpaperSlideshow show = new WallpaperSlideshow(three());
        assertEquals(Arrays.asList(url("a.jpg"), url("b.jpg"), url("c.jpg")),
                show.images());
    }
}
