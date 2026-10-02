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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AudioCdDb}'s pure parts: the MusicBrainz / Cover Art Archive URL
 * builders and {@code parseRelease} against canned JSON (artist / title / MBID /
 * tracks, the recording-title and position fallbacks, and missing-field
 * tolerance). No network is touched - the blocking {@code lookup}/{@code
 * fetchCover} are exercised only in the live rip dialog - so it runs headless.
 */
class AudioCdDbTest {

    private static final String FULL = """
            {
              "releases": [
                {
                  "id": "d0ce7c8a-1f1a-4a1e-9a5f-3f0d6f0a0001",
                  "title": "Greatest Hits",
                  "artist-credit": [
                    { "name": "The Artist", "artist": { "name": "Ignored" } }
                  ],
                  "media": [
                    {
                      "tracks": [
                        { "number": "1", "title": "First", "length": 200000 },
                        { "number": "2", "recording": { "title": "Second" },
                          "length": 180000 },
                        { "position": 3, "title": "Third" }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    @DisplayName("discidLookupUrl builds the MusicBrainz JSON endpoint")
    void discidLookupUrl() {
        assertEquals("https://musicbrainz.org/ws/2/discid/AbC._-1?inc=recordings&fmt=json",
                AudioCdDb.discidLookupUrl("AbC._-1"));
    }

    @Test
    @DisplayName("coverUrl builds the Cover Art Archive front image, size-aware")
    void coverUrl() {
        assertEquals("https://coverartarchive.org/release/mbid-1/front-500",
                AudioCdDb.coverUrl("mbid-1"));
        assertEquals("https://coverartarchive.org/release/mbid-1/front-250",
                AudioCdDb.coverUrl("mbid-1", 250));
        assertEquals("https://coverartarchive.org/release/mbid-1/front-500",
                AudioCdDb.coverUrl("mbid-1", 0), "a non-positive size uses the default");
    }

    @Test
    @DisplayName("parseRelease reads mbid, artist, title and the track list")
    void parseRelease() {
        AudioCdDb.Release rel = AudioCdDb.parseRelease(FULL);
        assertFalse(rel.isEmpty());
        assertEquals("d0ce7c8a-1f1a-4a1e-9a5f-3f0d6f0a0001", rel.mbid);
        assertEquals("The Artist", rel.artist, "artist-credit name wins over nested artist");
        assertEquals("Greatest Hits", rel.title);
        assertEquals(3, rel.tracks.size());

        AudioCdDb.TrackInfo t1 = rel.tracks.get(0);
        assertEquals(1, t1.number);
        assertEquals("First", t1.title);
        assertEquals(200000L, t1.lengthMillis);

        AudioCdDb.TrackInfo t2 = rel.tracks.get(1);
        assertEquals(2, t2.number);
        assertEquals("Second", t2.title, "falls back to the recording title");

        AudioCdDb.TrackInfo t3 = rel.tracks.get(2);
        assertEquals(3, t3.number, "number falls back to position");
        assertEquals("Third", t3.title);
        assertEquals(-1L, t3.lengthMillis, "a missing length is -1");
    }

    @Test
    @DisplayName("an artist-credit with a blank name falls back to the nested artist")
    void parseArtistFallback() {
        String body = """
                { "releases": [ { "id": "x", "title": "T",
                  "artist-credit": [ { "name": "", "artist": { "name": "Nested" } } ] } ] }
                """;
        assertEquals("Nested", AudioCdDb.parseRelease(body).artist);
    }

    @Test
    @DisplayName("a release with no media still yields its mbid/artist/title")
    void parseNoMedia() {
        String body = """
                { "releases": [ { "id": "y", "title": "Solo",
                  "artist-credit": [ { "name": "Someone" } ] } ] }
                """;
        AudioCdDb.Release rel = AudioCdDb.parseRelease(body);
        assertEquals("Solo", rel.title);
        assertTrue(rel.tracks.isEmpty());
    }

    @Test
    @DisplayName("no releases, blank, null or malformed JSON all give NO_MATCH")
    void tolerantParsing() {
        assertTrue(AudioCdDb.parseRelease("{\"releases\":[]}").isEmpty());
        assertTrue(AudioCdDb.parseRelease("{}").isEmpty());
        assertTrue(AudioCdDb.parseRelease("   ").isEmpty());
        assertTrue(AudioCdDb.parseRelease(null).isEmpty());
        assertTrue(AudioCdDb.parseRelease("{not json").isEmpty());
        assertTrue(AudioCdDb.NO_MATCH.isEmpty());
        assertEquals("", AudioCdDb.NO_MATCH.mbid);
    }

    @Test
    @DisplayName("lookup of a blank disc ID short-circuits to NO_MATCH (no network)")
    void lookupBlankIsOffline() {
        assertTrue(AudioCdDb.lookup("").isEmpty());
        assertTrue(AudioCdDb.lookup(null).isEmpty());
    }
}
