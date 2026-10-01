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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link AudioPlayerPanel}'s construction and library editing without
 * ever touching a sound device, a child process or a file chooser: the panel is
 * built over a {@link TempDir} store, then asserted on through its package-private
 * test hooks. Swing widgets construct headless, so this runs in CI.
 */
class AudioPlayerPanelTest {

    @Test
    @DisplayName("a fresh panel starts empty, ready and idle")
    void defaultsAreIdle(@TempDir Path dir) {
        AudioPlayerPanel panel = new AudioPlayerPanel(new AudioPlayerStore(dir));
        assertEquals(0, panel.librarySize());
        assertEquals("Ready", panel.statusText());
        assertEquals("Nothing playing", panel.nowPlayingText());
        assertEquals(-1, panel.playlist().currentIndex());
        assertNotNull(panel.playlist());
    }

    @Test
    @DisplayName("addFile grows the library and the playlist in step")
    void addFileGrowsLibrary(@TempDir Path dir) {
        AudioPlayerPanel panel = new AudioPlayerPanel(new AudioPlayerStore(dir));
        panel.addFile("/music/One.mp3");
        panel.addFile("/music/Two.wav");
        assertEquals(2, panel.librarySize());
        assertEquals(2, panel.playlist().size());
        assertEquals("One", panel.playlist().get(0).getName());
        assertEquals("Two", panel.playlist().get(1).getName());
    }

    @Test
    @DisplayName("blank and null paths are ignored")
    void addFileIgnoresBlanks(@TempDir Path dir) {
        AudioPlayerPanel panel = new AudioPlayerPanel(new AudioPlayerStore(dir));
        panel.addFile("   ");
        panel.addFile(null);
        assertEquals(0, panel.librarySize());
    }

    @Test
    @DisplayName("a persisted library is reloaded on the next open")
    void reloadsPersistedLibrary(@TempDir Path dir) {
        AudioPlayerStore store = new AudioPlayerStore(dir);
        AudioPlayerPanel first = new AudioPlayerPanel(store);
        first.addFile("/music/Keep.flac");
        store.saveLibrary(first.playlist().items());

        AudioPlayerPanel second = new AudioPlayerPanel(new AudioPlayerStore(dir));
        assertEquals(1, second.librarySize());
        assertEquals("Keep", second.playlist().get(0).getName());
    }

    @Test
    @DisplayName("stopPlayback is a no-op safe to call when idle")
    void stopIsSafeWhenIdle(@TempDir Path dir) {
        AudioPlayerPanel panel = new AudioPlayerPanel(new AudioPlayerStore(dir));
        panel.stopPlayback();
        assertEquals("Nothing playing", panel.nowPlayingText());
        assertTrue(panel.librarySize() == 0);
    }
}
