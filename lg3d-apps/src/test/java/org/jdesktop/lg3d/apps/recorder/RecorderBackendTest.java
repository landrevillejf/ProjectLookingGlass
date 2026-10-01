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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link RecorderBackend}'s pure command / format table. Nothing here
 * opens a capture device or starts a process, so it runs headless.
 */
class RecorderBackendTest {

    @Test
    @DisplayName("wavFormat is 16-bit little-endian PCM and clamps its inputs")
    void wavFormat() {
        AudioFormat stereo = RecorderBackend.wavFormat(44100, 2);
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, stereo.getEncoding());
        assertEquals(44100f, stereo.getSampleRate());
        assertEquals(16, stereo.getSampleSizeInBits());
        assertEquals(2, stereo.getChannels());
        assertEquals(4, stereo.getFrameSize());
        assertFalse(stereo.isBigEndian());

        assertEquals(1, RecorderBackend.wavFormat(44100, 0).getChannels(),
                "channels clamp up to mono");
        assertEquals(2, RecorderBackend.wavFormat(44100, 9).getChannels(),
                "channels clamp down to stereo");
        assertEquals(8000f, RecorderBackend.wavFormat(1, 1).getSampleRate(),
                "rate clamps up to 8 kHz");
        assertEquals(192000f, RecorderBackend.wavFormat(999999, 1).getSampleRate(),
                "rate clamps down to 192 kHz");
    }

    @Test
    @DisplayName("screenCommand builds an x11grab ffmpeg line")
    void screenCommandBasic() {
        List<String> cmd = RecorderBackend.screenCommand(
                "ffmpeg", "/out/screen.mp4", ":0.0", null, 30, false);
        assertEquals(List.of("ffmpeg", "-y", "-f", "x11grab",
                "-framerate", "30", "-i", ":0.0",
                "-c:v", "libx264", "-preset", "ultrafast", "/out/screen.mp4"), cmd);
    }

    @Test
    @DisplayName("a valid resolution adds -video_size and fps clamps to 1..120")
    void screenCommandResolutionAndClamp() {
        List<String> cmd = RecorderBackend.screenCommand(
                "ffmpeg", "/out/x.mp4", ":0.0", "1920x1080", 999, false);
        assertTrue(cmd.contains("-video_size"));
        assertTrue(cmd.contains("1920x1080"));
        assertTrue(cmd.contains("120"), "fps clamps down to 120");
        assertFalse(cmd.contains("-c:a"), "no audio codec without the microphone");
    }

    @Test
    @DisplayName("capturing audio muxes in a pulse input and an aac codec")
    void screenCommandWithAudio() {
        List<String> cmd = RecorderBackend.screenCommand(
                "ffmpeg", "/out/x.mp4", ":0.0", "", 24, true);
        assertTrue(cmd.contains("pulse"));
        assertTrue(cmd.contains("-c:a"));
        assertTrue(cmd.contains("aac"));
        assertFalse(cmd.contains("-video_size"), "a blank resolution is dropped");
    }

    @Test
    @DisplayName("screenCommand is empty when a required input is blank")
    void screenCommandGuards() {
        assertTrue(RecorderBackend.screenCommand(null, "/o.mp4", ":0.0", "", 30, false).isEmpty());
        assertTrue(RecorderBackend.screenCommand("ffmpeg", "  ", ":0.0", "", 30, false).isEmpty());
        assertTrue(RecorderBackend.screenCommand("ffmpeg", "/o.mp4", null, "", 30, false).isEmpty());
    }

    @Test
    @DisplayName("normaliseResolution accepts WxH and rejects junk")
    void normaliseResolution() {
        assertEquals("1920x1080", RecorderBackend.normaliseResolution(" 1920X1080 "));
        assertNull(RecorderBackend.normaliseResolution(null));
        assertNull(RecorderBackend.normaliseResolution("   "));
        assertNull(RecorderBackend.normaliseResolution("wide"));
        assertNull(RecorderBackend.normaliseResolution("x1080"));
        assertNull(RecorderBackend.normaliseResolution("1920x"));
        assertNull(RecorderBackend.normaliseResolution("0x0"));
        assertNull(RecorderBackend.normaliseResolution("axb"));
    }

    @Test
    @DisplayName("defaultFileName is timestamped with the right prefix and suffix")
    void defaultFileName() {
        String name = RecorderBackend.defaultFileName("audio", "wav");
        assertTrue(name.startsWith("audio-"), name);
        assertTrue(name.endsWith(".wav"), name);
        assertTrue(RecorderBackend.defaultFileName(null, null).startsWith("recording-"));
        assertTrue(RecorderBackend.defaultFileName("x", ".mp4").endsWith(".mp4"));
    }

    @Test
    @DisplayName("extension reads the lower-case suffix")
    void extension() {
        assertEquals("mp4", RecorderBackend.extension("/a/b/Clip.MP4"));
        assertEquals("wav", RecorderBackend.extension("audio-1.wav"));
        assertEquals("", RecorderBackend.extension("noext"));
        assertEquals("", RecorderBackend.extension("trailing."));
        assertEquals("", RecorderBackend.extension(null));
    }

    @Test
    @DisplayName("resolveRecorder prefers the user's choice when available")
    void resolveRecorder() {
        assertEquals(Optional.of("avconv"),
                RecorderBackend.resolveRecorder("avconv", s -> true));
        assertEquals(Optional.of("ffmpeg"),
                RecorderBackend.resolveRecorder("vlc", exe -> exe.equals("ffmpeg")),
                "an unavailable preference falls back to the first known recorder");
        assertEquals(Optional.of("ffmpeg"),
                RecorderBackend.resolveRecorder("", exe -> exe.equals("ffmpeg")));
        assertEquals(Optional.empty(), RecorderBackend.resolveRecorder("ffmpeg", s -> false));
        assertEquals(Optional.empty(), RecorderBackend.firstAvailableRecorder(null));
    }
}
