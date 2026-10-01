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
}
