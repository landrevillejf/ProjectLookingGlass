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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link RecorderPanel}'s construction and history editing without ever
 * opening a capture device or starting an external recorder: the panel is built
 * over a {@link TempDir} store, then asserted on through its package-private test
 * hooks. Swing widgets construct headless, so this runs in CI.
 */
class RecorderPanelTest {

    @Test
    @DisplayName("a fresh panel is idle, ready and empty")
    void defaultsAreIdle(@TempDir Path dir) {
        RecorderPanel panel = new RecorderPanel(new RecorderStore(dir));
        assertEquals("Ready", panel.statusText());
        assertEquals(0, panel.historySize());
        assertFalse(panel.isRecording());
        assertEquals(RecorderBackend.DEFAULT_SAMPLE_RATE, panel.settings().getSampleRate());
    }

    @Test
    @DisplayName("stopRecording is a safe no-op when nothing is capturing")
    void stopIsSafeWhenIdle(@TempDir Path dir) {
        RecorderPanel panel = new RecorderPanel(new RecorderStore(dir));
        panel.stopRecording();
        assertFalse(panel.isRecording());
        assertEquals(0, panel.historySize());
    }

    @Test
    @DisplayName("addRecording grows the history, newest first in the list")
    void addRecordingGrowsHistory(@TempDir Path dir) {
        RecorderPanel panel = new RecorderPanel(new RecorderStore(dir));
        panel.addRecording(Recording.audio("/tmp/first.wav"));
        panel.addRecording(Recording.video("/tmp/second.mp4"));
        assertEquals(2, panel.historySize());
        assertEquals(2, panel.history().size());
        // history() is oldest-first; the JList model shows newest-first.
        assertEquals("/tmp/first.wav", panel.history().get(0).getPath());
        assertEquals("/tmp/second.mp4", panel.history().get(1).getPath());
        panel.addRecording(null);
        assertEquals(2, panel.historySize(), "a null recording is ignored");
    }

    @Test
    @DisplayName("a persisted history is reloaded on the next open")
    void reloadsPersistedHistory(@TempDir Path dir) {
        RecorderStore store = new RecorderStore(dir);
        RecorderPanel first = new RecorderPanel(store);
        first.addRecording(Recording.audio("/tmp/keep.wav"));

        RecorderPanel second = new RecorderPanel(new RecorderStore(dir));
        assertEquals(1, second.historySize());
        assertEquals("/tmp/keep.wav", second.history().get(0).getPath());
    }

    @Test
    @DisplayName("resolveOutput places a capture in the configured folder")
    void resolveOutputHonoursConfiguredDir(@TempDir Path dir) {
        RecorderPanel panel = new RecorderPanel(new RecorderStore(dir));
        panel.settings().setOutputDir(dir.toString());
        File out = panel.resolveOutput("audio-1.wav");
        assertEquals("audio-1.wav", out.getName());
        assertEquals(dir.toFile().getAbsolutePath(), out.getParentFile().getAbsolutePath());
    }
}
