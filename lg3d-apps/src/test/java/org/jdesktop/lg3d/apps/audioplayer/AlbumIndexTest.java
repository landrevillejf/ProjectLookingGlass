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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AlbumIndex}'s pure grouping: by MBID first, then by a
 * case-insensitive {@code artist|album} key, distinct albums never merging,
 * untagged items becoming per-item "singles", first-non-blank cover / artist /
 * title resolution and track ordering. No AWT, so it runs headless.
 */
class AlbumIndexTest {

    private static MediaItem tagged(String path, String name, String artist,
                                    String album, int track, String mbid,
                                    String cover) {
        return MediaItem.file(path, name, artist, album, track, mbid, cover);
    }

    @Test
    @DisplayName("items sharing an MBID collapse to one album, fields resolved")
    void groupByMbid() {
        List<AlbumIndex.Album> albums = AlbumIndex.albums(Arrays.asList(
                tagged("/m/a.mp3", "A", "ArtistX", "AlbumX", 1, "mbid-1", ""),
                tagged("/m/b.mp3", "B", "", "", 2, "mbid-1", "/covers/mbid-1.jpg")));
        assertEquals(1, albums.size());
        AlbumIndex.Album a = albums.get(0);
        assertEquals("mbid-1", a.mbid);
        assertEquals("ArtistX", a.artist, "first non-blank artist wins");
        assertEquals("AlbumX", a.title, "first non-blank album wins");
        assertEquals("/covers/mbid-1.jpg", a.coverPath, "first non-blank cover wins");
        assertTrue(a.hasCover());
        assertEquals(2, a.tracks.size());
    }

    @Test
    @DisplayName("without an MBID, grouping is by case-insensitive artist|album")
    void groupByArtistAlbum() {
        List<AlbumIndex.Album> albums = AlbumIndex.albums(Arrays.asList(
                tagged("/m/c.mp3", "C", "Queen", "A Night at the Opera", 1, "", ""),
                tagged("/m/d.mp3", "D", "queen", "a night at the opera", 2, "", "")));
        assertEquals(1, albums.size());
        assertEquals("Queen", albums.get(0).artist);
        assertEquals("A Night at the Opera", albums.get(0).title);
        assertFalse(albums.get(0).hasCover());
    }

    @Test
    @DisplayName("distinct albums stay separate and first-seen order is kept")
    void distinctAlbums() {
        List<AlbumIndex.Album> albums = AlbumIndex.albums(Arrays.asList(
                tagged("/m/1.mp3", "One", "Queen", "A Night at the Opera", 1, "", ""),
                tagged("/m/2.mp3", "Two", "Queen", "News of the World", 1, "", ""),
                tagged("/m/3.mp3", "Three", "Queen", "A Night at the Opera", 2, "", "")));
        assertEquals(2, albums.size());
        assertEquals("A Night at the Opera", albums.get(0).title);
        assertEquals("News of the World", albums.get(1).title);
        assertEquals(2, albums.get(0).tracks.size());
    }

    @Test
    @DisplayName("tracks within an album are ordered by track number then name")
    void trackOrder() {
        List<AlbumIndex.Album> albums = AlbumIndex.albums(Arrays.asList(
                tagged("/m/z.mp3", "Zeta", "A", "Al", 3, "", ""),
                tagged("/m/y.mp3", "Yankee", "A", "Al", 1, "", ""),
                tagged("/m/x.mp3", "Xray", "A", "Al", 2, "", "")));
        List<MediaItem> tracks = albums.get(0).tracks;
        assertEquals("Yankee", tracks.get(0).toString());
        assertEquals("Xray", tracks.get(1).toString());
        assertEquals("Zeta", tracks.get(2).toString());
    }

    @Test
    @DisplayName("an untagged item becomes its own single titled by its name")
    void untaggedSingle() {
        List<AlbumIndex.Album> albums =
                AlbumIndex.albums(List.of(MediaItem.file("/music/Solo.mp3")));
        assertEquals(1, albums.size());
        AlbumIndex.Album s = albums.get(0);
        assertEquals("", s.artist);
        assertEquals("Solo", s.title);
        assertEquals("Solo", s.label(), "no artist means the label is just the title");
    }

    @Test
    @DisplayName("label combines artist and title with an en dash")
    void label() {
        AlbumIndex.Album album = AlbumIndex.albums(List.of(
                tagged("/m/a.mp3", "A", "Queen", "A Night at the Opera", 1, "", "")))
                .get(0);
        assertEquals("Queen \u2013 A Night at the Opera", album.label());
        assertEquals(album.label(), album.toString());
    }

    @Test
    @DisplayName("an empty or null library yields no albums")
    void emptyLibrary() {
        assertTrue(AlbumIndex.albums(List.of()).isEmpty());
        assertTrue(AlbumIndex.albums(null).isEmpty());
        assertTrue(AlbumIndex.albums(new ArrayList<>()).isEmpty());
        assertTrue(AlbumIndex.isEmpty(null));
        assertFalse(AlbumIndex.isEmpty(List.of(MediaItem.file("/m/a.mp3"))));
    }

    @Test
    @DisplayName("null items in the list are skipped, not fatal")
    void skipsNulls() {
        List<MediaItem> items = new ArrayList<>();
        items.add(null);
        items.add(tagged("/m/a.mp3", "A", "Artist", "Album", 1, "m1", ""));
        items.add(null);
        List<AlbumIndex.Album> albums = AlbumIndex.albums(items);
        assertEquals(1, albums.size());
    }
}
