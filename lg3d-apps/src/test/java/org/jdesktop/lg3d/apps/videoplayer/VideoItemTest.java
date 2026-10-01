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
package org.jdesktop.lg3d.apps.videoplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VideoItem}'s name derivation and null-safe bean behaviour. Pure
 * logic, so it runs headless.
 */
class VideoItemTest {

    @Test
    @DisplayName("a blank name is derived from the location's last segment")
    void derivesName() {
        assertEquals("Film", VideoItem.file("/movies/Film.mp4").getName());
        assertEquals("live", new VideoItem(null, "http://x/live", VideoItem.Kind.STREAM)
                .getName());
        assertEquals("Untitled", new VideoItem("", "", VideoItem.Kind.FILE).getName());
    }

    @Test
    @DisplayName("factories set the right kind and location")
    void factories() {
        VideoItem f = VideoItem.file("/a/b.mkv");
        assertEquals(VideoItem.Kind.FILE, f.getKind());
        assertEquals("/a/b.mkv", f.getLocation());

        VideoItem d = VideoItem.of("DVD", "dvd:///dev/sr0", VideoItem.Kind.DISC);
        assertEquals(VideoItem.Kind.DISC, d.getKind());
        assertEquals("DVD", d.getName());
    }

    @Test
    @DisplayName("setters are null-safe and fall back sensibly")
    void nullSafeSetters() {
        VideoItem m = new VideoItem();
        m.setLocation(null);
        assertEquals("", m.getLocation());
        m.setKind(null);
        assertEquals(VideoItem.Kind.FILE, m.getKind());
        m.setLocation("/x/Movie.mp4");
        m.setName("   ");
        assertEquals("Movie", m.getName(), "a blank name re-derives from the location");
    }

    @Test
    @DisplayName("toString is the display name and addedMillis defaults positive")
    void stringAndTimestamp() {
        VideoItem m = VideoItem.file("/x/Hello.mp4");
        assertEquals("Hello", m.toString());
        assertTrue(m.getAddedMillis() > 0);
        m.setAddedMillis(123L);
        assertEquals(123L, m.getAddedMillis());
    }
}
