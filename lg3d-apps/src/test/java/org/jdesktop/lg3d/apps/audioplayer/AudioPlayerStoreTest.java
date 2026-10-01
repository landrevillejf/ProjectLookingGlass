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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link AudioPlayerStore}'s round-trip persistence and its defensive
 * reads: a missing folder yields an empty library / default settings, and a
 * corrupt file is swallowed rather than thrown, so a damaged config can never
 * stop the player from opening. Uses a {@link TempDir}, so it runs headless.
 */
class AudioPlayerStoreTest {

    @Test
    @DisplayName("a missing folder reads back an empty library and defaults")
    void missingIsSafe(@TempDir Path dir) {
        AudioPlayerStore store = new AudioPlayerStore(dir.resolve("nope"));
        assertTrue(store.loadLibrary().isEmpty());
        assertEquals(PlayerSettings.DEFAULT_VOLUME, store.loadSettings().getVolume());
    }

    @Test
    @DisplayName("the library round-trips through JSON")
    void libraryRoundTrip(@TempDir Path dir) {
        AudioPlayerStore store = new AudioPlayerStore(dir);
        store.saveLibrary(List.of(
                MediaItem.file("/music/a.mp3"),
                MediaItem.stream("FM", "http://x/live", MediaItem.Kind.RADIO)));
        List<MediaItem> loaded = store.loadLibrary();
        assertEquals(2, loaded.size());
        assertEquals("a", loaded.get(0).getName());
        assertEquals(MediaItem.Kind.RADIO, loaded.get(1).getKind());
        assertEquals("http://x/live", loaded.get(1).getLocation());
    }

    @Test
    @DisplayName("the settings round-trip through JSON")
    void settingsRoundTrip(@TempDir Path dir) {
        AudioPlayerStore store = new AudioPlayerStore(dir);
        PlayerSettings s = new PlayerSettings();
        s.setVolume(35);
        s.setRepeat(true);
        s.setPreferredPlayer("mpv");
        store.saveSettings(s);

        PlayerSettings loaded = store.loadSettings();
        assertEquals(35, loaded.getVolume());
        assertTrue(loaded.isRepeat());
        assertFalse(loaded.isShuffle());
        assertEquals("mpv", loaded.getPreferredPlayer());
    }

    @Test
    @DisplayName("a corrupt library file is swallowed, yielding an empty list")
    void corruptIsSafe(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("library.json"), "{not json at all");
        AudioPlayerStore store = new AudioPlayerStore(dir);
        assertTrue(store.loadLibrary().isEmpty());
    }

    @Test
    @DisplayName("saving null writes an empty library, not a failure")
    void saveNull(@TempDir Path dir) {
        AudioPlayerStore store = new AudioPlayerStore(dir);
        store.saveLibrary(null);
        store.saveSettings(null);
        assertTrue(store.loadLibrary().isEmpty());
        assertEquals(PlayerSettings.DEFAULT_VOLUME, store.loadSettings().getVolume());
    }
}
