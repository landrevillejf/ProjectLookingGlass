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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers the {@link Recording} and {@link RecordingSettings} beans. */
class RecordingTest {

    @Test
    @DisplayName("audio()/video() factories set the kind and derive a title")
    void factories() {
        Recording audio = Recording.audio("/home/me/Recordings/audio-1.wav");
        assertEquals(Recording.Kind.AUDIO, audio.getKind());
        assertEquals("audio-1", audio.getTitle());
        assertTrue(audio.toString().startsWith("[audio]"));

        Recording video = Recording.video("/tmp/screen-2.mp4");
        assertEquals(Recording.Kind.VIDEO, video.getKind());
        assertEquals("screen-2", video.getTitle());
        assertTrue(video.toString().startsWith("[video]"));
    }

    @Test
    @DisplayName("blank and null inputs fall back safely")
    void blankFallbacks() {
        assertEquals("Recording", Recording.deriveTitle(""));
        assertEquals("Recording", Recording.deriveTitle(null));
        Recording r = new Recording(null, null, "  ");
        assertEquals("", r.getPath());
        assertEquals(Recording.Kind.AUDIO, r.getKind(), "a null kind defaults to AUDIO");
        assertEquals("Recording", r.getTitle());
    }

    @Test
    @DisplayName("duration never goes negative")
    void durationClamps() {
        Recording r = Recording.audio("/tmp/a.wav");
        r.setDurationMillis(1500);
        assertEquals(1500, r.getDurationMillis());
        r.setDurationMillis(-9);
        assertEquals(0, r.getDurationMillis());
    }

    @Test
    @DisplayName("settings defaults match the backend and setters clamp")
    void settingsDefaultsAndClamps() {
        RecordingSettings s = new RecordingSettings();
        assertEquals(RecorderBackend.DEFAULT_SAMPLE_RATE, s.getSampleRate());
        assertEquals(RecorderBackend.DEFAULT_FPS, s.getFps());
        assertEquals(2, s.getChannels());
        assertEquals(RecorderBackend.DEFAULT_SOURCE, s.getScreenSource());

        s.setSampleRate(1);
        assertEquals(8000, s.getSampleRate(), "rate clamps up");
        s.setSampleRate(1_000_000);
        assertEquals(192000, s.getSampleRate(), "rate clamps down");
        s.setChannels(0);
        assertEquals(1, s.getChannels());
        s.setChannels(8);
        assertEquals(2, s.getChannels());
        s.setFps(0);
        assertEquals(1, s.getFps());
        s.setFps(500);
        assertEquals(120, s.getFps());
        s.setScreenSource("  ");
        assertEquals(RecorderBackend.DEFAULT_SOURCE, s.getScreenSource(),
                "a blank source resets to the default");
        s.setOutputDir(null);
        assertEquals("", s.getOutputDir());
    }
}
