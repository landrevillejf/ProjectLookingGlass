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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link CdRipBackend}'s pure command table: the rip / encode argv shapes
 * per tool, the {@code -ar} / {@code -b:a} flags for each sampling rate and
 * bitrate, {@code lame --resample}, the empty-list guards on blank input, tool
 * resolution over a fake PATH predicate and device fallback. No process is ever
 * started, so it runs headless.
 */
class CdRipBackendTest {

    @Test
    @DisplayName("readTocCommand builds cdparanoia -Q and cd-info argv")
    void readTocCommand() {
        assertEquals(List.of("cdparanoia", "-d", "/dev/sr0", "-Q"),
                CdRipBackend.readTocCommand("cdparanoia", "/dev/sr0"));
        assertEquals(List.of("cdparanoia", "-Q"),
                CdRipBackend.readTocCommand("cdparanoia", "  "));
        assertEquals(List.of("cd-info", "--no-header", "--no-cddb", "-C", "/dev/cdrom"),
                CdRipBackend.readTocCommand("cd-info", "/dev/cdrom"));
        assertTrue(CdRipBackend.readTocCommand("", "/dev/sr0").isEmpty());
        assertTrue(CdRipBackend.readTocCommand(null, null).isEmpty());
    }

    @Test
    @DisplayName("ripWavCommand builds cdparanoia and ffmpeg audiocd argv")
    void ripWavCommand() {
        assertEquals(List.of("cdparanoia", "-d", "/dev/sr0", "3", "/tmp/03.wav"),
                CdRipBackend.ripWavCommand("cdparanoia", "/dev/sr0", 3, "/tmp/03.wav"));
        assertEquals(List.of("cdparanoia", "3", "/tmp/03.wav"),
                CdRipBackend.ripWavCommand("cdparanoia", "", 3, "/tmp/03.wav"));
        assertEquals(List.of("ffmpeg", "-y", "-f", "audiocd", "-i", "/dev/cdrom",
                        "-track", "2", "-c:a", "pcm_s16le", "/tmp/02.wav"),
                CdRipBackend.ripWavCommand("ffmpeg", "", 2, "/tmp/02.wav"));
        // Guards: blank ripper / out, or a non-positive track number.
        assertTrue(CdRipBackend.ripWavCommand("", "/dev/sr0", 1, "/x.wav").isEmpty());
        assertTrue(CdRipBackend.ripWavCommand("cdparanoia", "/dev/sr0", 0, "/x.wav").isEmpty());
        assertTrue(CdRipBackend.ripWavCommand("cdparanoia", "/dev/sr0", 1, " ").isEmpty());
    }

    @Test
    @DisplayName("ffmpeg MP3 encode carries -ar and -b:a for the chosen rate/bitrate")
    void encodeMp3Ffmpeg() {
        assertEquals(List.of("ffmpeg", "-y", "-i", "/tmp/01.wav", "-ar", "22050",
                        "-c:a", "libmp3lame", "-b:a", "256k", "/music/01.mp3"),
                CdRipBackend.encodeCommand("ffmpeg", "/tmp/01.wav", "/music/01.mp3",
                        CdRipBackend.Format.MP3, 22050, 256));
        // An unrecognised rate/bitrate snaps to the supported defaults.
        assertEquals(List.of("ffmpeg", "-y", "-i", "in.wav", "-ar", "44100",
                        "-c:a", "libmp3lame", "-b:a", "192k", "out.mp3"),
                CdRipBackend.encodeCommand("ffmpeg", "in.wav", "out.mp3",
                        CdRipBackend.Format.MP3, 12345, 999));
    }

    @Test
    @DisplayName("ffmpeg WAV encode resamples with pcm_s16le and no bitrate")
    void encodeWavFfmpeg() {
        assertEquals(List.of("ffmpeg", "-y", "-i", "in.wav", "-ar", "48000",
                        "-c:a", "pcm_s16le", "out.wav"),
                CdRipBackend.encodeCommand("ffmpeg", "in.wav", "out.wav",
                        CdRipBackend.Format.WAV, 48000, 320));
    }

    @Test
    @DisplayName("lame resamples MP3 via --resample kHz but cannot emit WAV")
    void encodeLame() {
        assertEquals(List.of("lame", "--resample", "22.05", "-b", "128", "in.wav", "out.mp3"),
                CdRipBackend.encodeCommand("lame", "in.wav", "out.mp3",
                        CdRipBackend.Format.MP3, 22050, 128));
        assertEquals(List.of("lame", "--resample", "44.1", "-b", "192", "in.wav", "out.mp3"),
                CdRipBackend.encodeCommand("lame", "in.wav", "out.mp3",
                        CdRipBackend.Format.MP3, 44100, 192));
        assertEquals(List.of("lame", "--resample", "48", "-b", "320", "in.wav", "out.mp3"),
                CdRipBackend.encodeCommand("lame", "in.wav", "out.mp3",
                        CdRipBackend.Format.MP3, 48000, 320));
        // lame is MP3-only: a WAV target yields no command so the caller picks ffmpeg.
        assertTrue(CdRipBackend.encodeCommand("lame", "in.wav", "out.wav",
                CdRipBackend.Format.WAV, 44100, 192).isEmpty());
    }

    @Test
    @DisplayName("encodeCommand guards blank inputs and a null format")
    void encodeGuards() {
        assertTrue(CdRipBackend.encodeCommand("", "in.wav", "out.mp3",
                CdRipBackend.Format.MP3, 44100, 192).isEmpty());
        assertTrue(CdRipBackend.encodeCommand("ffmpeg", " ", "out.mp3",
                CdRipBackend.Format.MP3, 44100, 192).isEmpty());
        assertTrue(CdRipBackend.encodeCommand("ffmpeg", "in.wav", "out.mp3",
                null, 44100, 192).isEmpty());
    }

    @Test
    @DisplayName("khz renders rates with the fewest decimals lame accepts")
    void khz() {
        assertEquals("8", CdRipBackend.khz(8000));
        assertEquals("11.025", CdRipBackend.khz(11025));
        assertEquals("44.1", CdRipBackend.khz(44100));
        assertEquals("48", CdRipBackend.khz(48000));
    }

    @Test
    @DisplayName("normalise snaps unsupported rates/bitrates to the defaults")
    void normalise() {
        assertEquals(44100, CdRipBackend.normaliseSampleRate(44100));
        assertEquals(44100, CdRipBackend.normaliseSampleRate(1));
        assertEquals(192, CdRipBackend.normaliseBitrate(192));
        assertEquals(192, CdRipBackend.normaliseBitrate(64));
    }

    @Test
    @DisplayName("defaultDevice picks the first existing node in order")
    void defaultDevice() {
        assertEquals(Optional.of("/dev/sr0"),
                CdRipBackend.defaultDevice(p -> p.equals("/dev/sr0")));
        assertEquals(Optional.of("/dev/cdrom"),
                CdRipBackend.defaultDevice(p -> p.equals("/dev/cdrom") || p.equals("/dev/sr1")));
        assertTrue(CdRipBackend.defaultDevice(p -> false).isEmpty());
        assertTrue(CdRipBackend.defaultDevice(null).isEmpty());
    }

    @Test
    @DisplayName("resolveRipper/resolveEncoder honour a preference then fall back")
    void resolveTools() {
        Set<String> onPath = Set.of("cd-info", "lame");
        // Preferred and available wins even when a higher-priority tool exists.
        assertEquals(Optional.of("cd-info"),
                CdRipBackend.resolveRipper("cd-info", onPath::contains));
        // Preferred but absent falls back to the first known tool on the PATH.
        assertEquals(Optional.of("cd-info"),
                CdRipBackend.resolveRipper("cdparanoia", onPath::contains));
        assertEquals(Optional.of("lame"),
                CdRipBackend.resolveEncoder("lame", onPath::contains));
        // Nothing on the PATH resolves to empty.
        assertTrue(CdRipBackend.resolveRipper(null, p -> false).isEmpty());
        assertTrue(CdRipBackend.resolveEncoder("ffmpeg", null).isEmpty());
    }

    @Test
    @DisplayName("format extensions and path extension parsing")
    void extensions() {
        assertEquals("wav", CdRipBackend.Format.WAV.extension());
        assertEquals("mp3", CdRipBackend.Format.MP3.extension());
        assertEquals("mp3", CdRipBackend.extension("/music/Track.MP3"));
        assertEquals("", CdRipBackend.extension("/music/noext"));
        assertEquals("", CdRipBackend.extension("trailing."));
        assertEquals("", CdRipBackend.extension(null));
    }
}
