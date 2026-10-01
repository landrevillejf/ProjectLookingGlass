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
package org.jdesktop.lg3d.apps.recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers {@link RecorderStore}'s defensive JSON round-trip of both files. */
class RecorderStoreTest {

    @Test
    @DisplayName("settings survive a save / load round-trip")
    void settingsRoundTrip(@TempDir Path dir) {
        RecorderStore store = new RecorderStore(dir);
        RecordingSettings s = new RecordingSettings();
        s.setOutputDir("/home/me/Captures");
        s.setSampleRate(48000);
        s.setChannels(1);
        s.setFps(24);
        s.setCaptureAudioWithScreen(true);
        s.setPreferredRecorder("avconv");
        store.saveSettings(s);

        RecordingSettings back = new RecorderStore(dir).loadSettings();
        assertEquals("/home/me/Captures", back.getOutputDir());
        assertEquals(48000, back.getSampleRate());
        assertEquals(1, back.getChannels());
        assertEquals(24, back.getFps());
        assertTrue(back.isCaptureAudioWithScreen());
        assertEquals("avconv", back.getPreferredRecorder());
    }

    @Test
    @DisplayName("history survives a round-trip with kinds intact")
    void historyRoundTrip(@TempDir Path dir) {
        RecorderStore store = new RecorderStore(dir);
        Recording audio = Recording.audio("/tmp/a.wav");
        audio.setDurationMillis(1200);
        store.saveHistory(List.of(audio, Recording.video("/tmp/b.mp4")));

        List<Recording> back = new RecorderStore(dir).loadHistory();
        assertEquals(2, back.size());
        assertEquals(Recording.Kind.AUDIO, back.get(0).getKind());
        assertEquals(1200, back.get(0).getDurationMillis());
        assertEquals(Recording.Kind.VIDEO, back.get(1).getKind());
    }

    @Test
    @DisplayName("missing files yield defaults / empty, never throw")
    void missingIsSafe(@TempDir Path dir) {
        RecorderStore store = new RecorderStore(dir);
        assertEquals(RecorderBackend.DEFAULT_SAMPLE_RATE, store.loadSettings().getSampleRate());
        assertTrue(store.loadHistory().isEmpty());
    }

    @Test
    @DisplayName("corrupt files yield defaults / empty, never throw")
    void corruptIsSafe(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(RecorderStore.SETTINGS_FILE), "{bad json]");
        Files.writeString(dir.resolve(RecorderStore.HISTORY_FILE), "not-a-list");
        RecorderStore store = new RecorderStore(dir);
        assertEquals(RecorderBackend.DEFAULT_FPS, store.loadSettings().getFps());
        assertTrue(store.loadHistory().isEmpty());
    }

    @Test
    @DisplayName("saving null persists defaults without error")
    void saveNullIsSafe(@TempDir Path dir) {
        RecorderStore store = new RecorderStore(dir);
        store.saveSettings(null);
        store.saveHistory(null);
        assertEquals(RecorderBackend.DEFAULT_SAMPLE_RATE, store.loadSettings().getSampleRate());
        assertTrue(store.loadHistory().isEmpty());
    }

    @Test
    @DisplayName("the DIR_PROPERTY override resolves the default directory")
    void honoursOverride() {
        String previous = System.getProperty(RecorderStore.DIR_PROPERTY);
        try {
            System.setProperty(RecorderStore.DIR_PROPERTY, "/tmp/rec-override");
            assertEquals(Path.of("/tmp/rec-override"), RecorderStore.defaultConfigDir());
            assertEquals(Path.of("/tmp/rec-override"), new RecorderStore().getConfigDir());
        } finally {
            if (previous == null) {
                System.clearProperty(RecorderStore.DIR_PROPERTY);
            } else {
                System.setProperty(RecorderStore.DIR_PROPERTY, previous);
            }
        }
    }
}
