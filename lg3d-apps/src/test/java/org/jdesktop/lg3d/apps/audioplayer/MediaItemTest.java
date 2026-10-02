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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link MediaItem}'s name derivation and null-safe bean behaviour. Pure
 * logic, so it runs headless.
 */
class MediaItemTest {

    @Test
    @DisplayName("a blank name is derived from the location's last segment")
    void derivesName() {
        assertEquals("Song", MediaItem.file("/music/Song.mp3").getName());
        assertEquals("live", new MediaItem(null, "http://x/live", MediaItem.Kind.STREAM)
                .getName());
        assertEquals("Untitled", new MediaItem("", "", MediaItem.Kind.FILE).getName());
    }

    @Test
    @DisplayName("factories set the right kind and location")
    void factories() {
        MediaItem f = MediaItem.file("/a/b.wav");
        assertEquals(MediaItem.Kind.FILE, f.getKind());
        assertEquals("/a/b.wav", f.getLocation());

        MediaItem r = MediaItem.stream("FM 101", "http://x/stream", MediaItem.Kind.RADIO);
        assertEquals(MediaItem.Kind.RADIO, r.getKind());
        assertEquals("FM 101", r.getName());
    }

    @Test
    @DisplayName("setters are null-safe and fall back sensibly")
    void nullSafeSetters() {
        MediaItem m = new MediaItem();
        m.setLocation(null);
        assertEquals("", m.getLocation());
        m.setKind(null);
        assertEquals(MediaItem.Kind.FILE, m.getKind());
        m.setLocation("/x/Track.flac");
        m.setName("   ");
        assertEquals("Track", m.getName(), "a blank name re-derives from the location");
    }

    @Test
    @DisplayName("toString is the display name and addedMillis defaults positive")
    void stringAndTimestamp() {
        MediaItem m = MediaItem.file("/x/Hello.mp3");
        assertEquals("Hello", m.toString());
        assertTrue(m.getAddedMillis() > 0);
        m.setAddedMillis(123L);
        assertEquals(123L, m.getAddedMillis());
    }

    @Test
    @DisplayName("the album tags default blank/zero so plain items stay untagged")
    void tagsDefaultBlank() {
        MediaItem m = MediaItem.file("/x/Hello.mp3");
        assertEquals("", m.getArtist());
        assertEquals("", m.getAlbum());
        assertEquals(0, m.getTrackNumber());
        assertEquals("", m.getMbid());
        assertEquals("", m.getCoverFile());
    }

    @Test
    @DisplayName("the tagged factory records the album metadata")
    void taggedFactory() {
        MediaItem m = MediaItem.file("/music/01 Song.mp3", "Song", "The Band",
                "The Album", 1, "mbid-9", "/covers/mbid-9.jpg");
        assertEquals(MediaItem.Kind.FILE, m.getKind());
        assertEquals("Song", m.getName());
        assertEquals("The Band", m.getArtist());
        assertEquals("The Album", m.getAlbum());
        assertEquals(1, m.getTrackNumber());
        assertEquals("mbid-9", m.getMbid());
        assertEquals("/covers/mbid-9.jpg", m.getCoverFile());
    }

    @Test
    @DisplayName("tag setters trim, null-safe, and clamp a negative track number")
    void tagSetters() {
        MediaItem m = new MediaItem();
        m.setArtist("  Queen  ");
        assertEquals("Queen", m.getArtist());
        m.setAlbum(null);
        assertEquals("", m.getAlbum());
        m.setMbid(null);
        assertEquals("", m.getMbid());
        m.setCoverFile(null);
        assertEquals("", m.getCoverFile());
        m.setTrackNumber(-3);
        assertEquals(0, m.getTrackNumber());
        m.setTrackNumber(7);
        assertEquals(7, m.getTrackNumber());
    }

    @Test
    @DisplayName("the new tags round-trip through Jackson")
    void jacksonRoundTrip() throws Exception {
        MediaItem m = MediaItem.file("/music/02 Two.mp3", "Two", "Queen",
                "A Night at the Opera", 2, "mbid-2", "/covers/mbid-2.jpg");
        ObjectMapper mapper = new ObjectMapper();
        MediaItem back = mapper.readValue(mapper.writeValueAsString(m), MediaItem.class);
        assertEquals("Two", back.getName());
        assertEquals("Queen", back.getArtist());
        assertEquals("A Night at the Opera", back.getAlbum());
        assertEquals(2, back.getTrackNumber());
        assertEquals("mbid-2", back.getMbid());
        assertEquals("/covers/mbid-2.jpg", back.getCoverFile());
    }

    @Test
    @DisplayName("a pre-tags library.json still deserialises (unknown fields absent)")
    void oldJsonStillLoads() throws Exception {
        String legacy = "{\"name\":\"Old\",\"location\":\"/m/old.mp3\","
                + "\"kind\":\"FILE\",\"addedMillis\":1}";
        MediaItem back = new ObjectMapper().readValue(legacy, MediaItem.class);
        assertEquals("Old", back.getName());
        assertEquals("", back.getArtist());
        assertEquals(0, back.getTrackNumber());
    }
}
