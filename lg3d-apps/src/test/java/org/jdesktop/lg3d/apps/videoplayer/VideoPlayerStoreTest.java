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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link VideoPlayerStore}'s round-trip persistence and its defensive
 * reads: a missing folder yields an empty library / default settings, and a
 * corrupt file is swallowed rather than thrown. Uses a {@link TempDir}, so it
 * runs headless.
 */
class VideoPlayerStoreTest {

    @Test
    @DisplayName("a missing folder reads back an empty library and defaults")
    void missingIsSafe(@TempDir Path dir) {
        VideoPlayerStore store = new VideoPlayerStore(dir.resolve("nope"));
        assertTrue(store.loadLibrary().isEmpty());
        assertEquals(VideoSettings.DEFAULT_VOLUME, store.loadSettings().getVolume());
        assertFalse(store.loadSettings().isFullscreen());
    }

    @Test
    @DisplayName("the library round-trips through JSON")
    void libraryRoundTrip(@TempDir Path dir) {
        VideoPlayerStore store = new VideoPlayerStore(dir);
        store.saveLibrary(List.of(
                VideoItem.file("/movies/a.mp4"),
                VideoItem.of("DVD", "dvd:///dev/sr0", VideoItem.Kind.DISC)));
        List<VideoItem> loaded = store.loadLibrary();
        assertEquals(2, loaded.size());
        assertEquals("a", loaded.get(0).getName());
        assertEquals(VideoItem.Kind.DISC, loaded.get(1).getKind());
        assertEquals("dvd:///dev/sr0", loaded.get(1).getLocation());
    }

    @Test
    @DisplayName("the settings round-trip through JSON")
    void settingsRoundTrip(@TempDir Path dir) {
        VideoPlayerStore store = new VideoPlayerStore(dir);
        VideoSettings s = new VideoSettings();
        s.setVolume(35);
        s.setFullscreen(true);
        s.setPreferredPlayer("mpv");
        store.saveSettings(s);

        VideoSettings loaded = store.loadSettings();
        assertEquals(35, loaded.getVolume());
        assertTrue(loaded.isFullscreen());
        assertEquals("mpv", loaded.getPreferredPlayer());
    }

    @Test
    @DisplayName("a corrupt library file is swallowed, yielding an empty list")
    void corruptIsSafe(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("library.json"), "{not json at all");
        VideoPlayerStore store = new VideoPlayerStore(dir);
        assertTrue(store.loadLibrary().isEmpty());
    }

    @Test
    @DisplayName("saving null writes an empty library, not a failure")
    void saveNull(@TempDir Path dir) {
        VideoPlayerStore store = new VideoPlayerStore(dir);
        store.saveLibrary(null);
        store.saveSettings(null);
        assertTrue(store.loadLibrary().isEmpty());
        assertEquals(VideoSettings.DEFAULT_VOLUME, store.loadSettings().getVolume());
    }
}
