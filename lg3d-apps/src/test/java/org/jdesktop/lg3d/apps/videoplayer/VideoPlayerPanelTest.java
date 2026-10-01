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

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link VideoPlayerPanel}'s construction and library editing without
 * ever starting a child process or opening a file chooser: the panel is built
 * over a {@link TempDir} store, then asserted on through its package-private
 * test hooks. Swing widgets construct headless, so this runs in CI.
 */
class VideoPlayerPanelTest {

    @Test
    @DisplayName("a fresh panel starts empty and ready")
    void defaultsAreIdle(@TempDir Path dir) {
        VideoPlayerPanel panel = new VideoPlayerPanel(new VideoPlayerStore(dir));
        assertEquals(0, panel.librarySize());
        assertEquals("Ready", panel.statusText());
        assertEquals("Nothing selected", panel.nowPlayingText());
    }

    @Test
    @DisplayName("addFile grows the library")
    void addFileGrowsLibrary(@TempDir Path dir) {
        VideoPlayerPanel panel = new VideoPlayerPanel(new VideoPlayerStore(dir));
        panel.addFile("/movies/One.mp4");
        panel.addFile("/movies/Two.mkv");
        assertEquals(2, panel.librarySize());
        assertEquals(2, panel.library().size());
        assertEquals("One", panel.library().get(0).getName());
        assertEquals("Two", panel.library().get(1).getName());
    }

    @Test
    @DisplayName("blank and null paths are ignored")
    void addFileIgnoresBlanks(@TempDir Path dir) {
        VideoPlayerPanel panel = new VideoPlayerPanel(new VideoPlayerStore(dir));
        panel.addFile("   ");
        panel.addFile(null);
        assertEquals(0, panel.librarySize());
    }

    @Test
    @DisplayName("a persisted library is reloaded on the next open")
    void reloadsPersistedLibrary(@TempDir Path dir) {
        VideoPlayerStore store = new VideoPlayerStore(dir);
        VideoPlayerPanel first = new VideoPlayerPanel(store);
        first.addFile("/movies/Keep.mp4");

        VideoPlayerPanel second = new VideoPlayerPanel(new VideoPlayerStore(dir));
        assertEquals(1, second.librarySize());
        assertEquals("Keep", second.library().get(0).getName());
    }

    @Test
    @DisplayName("stopPlayback is a no-op safe to call when idle")
    void stopIsSafeWhenIdle(@TempDir Path dir) {
        VideoPlayerPanel panel = new VideoPlayerPanel(new VideoPlayerStore(dir));
        panel.stopPlayback();
        assertTrue(panel.librarySize() == 0);
    }
}
