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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link AudioBackend} playback-decision seam: extension
 * parsing, stream detection, the native-versus-external classification, the
 * per-player command lines (with and without a volume), and the availability /
 * preference resolution. No process is started, so it runs headless.
 */
class AudioBackendTest {

    @Test
    @DisplayName("extension strips directories and query strings")
    void parsesExtension() {
        assertEquals("mp3", AudioBackend.extension("/music/Song.MP3"));
        assertEquals("", AudioBackend.extension("/music/noext"));
        assertEquals("aac", AudioBackend.extension("https://x/stream.aac?token=1"));
        assertEquals("", AudioBackend.extension(null));
        assertEquals("", AudioBackend.extension("   "));
        assertEquals("", AudioBackend.extension("trailing."));
    }

    @Test
    @DisplayName("isStream recognises network schemes only")
    void detectsStream() {
        assertTrue(AudioBackend.isStream("http://radio.example/live"));
        assertTrue(AudioBackend.isStream("HTTPS://x/y"));
        assertTrue(AudioBackend.isStream("rtsp://cam/feed"));
        assertFalse(AudioBackend.isStream("/home/me/song.mp3"));
        assertFalse(AudioBackend.isStream("file:///tmp/a.wav"));
        assertFalse(AudioBackend.isStream(null));
        assertFalse(AudioBackend.isStream(""));
    }

    @Test
    @DisplayName("only JDK-native local formats are natively playable")
    void nativeClassification() {
        assertTrue(AudioBackend.isNativelyPlayable("/a/song.wav"));
        assertTrue(AudioBackend.isNativelyPlayable("/a/song.AIFF"));
        assertTrue(AudioBackend.isNativelyPlayable("/a/song.au"));
        assertFalse(AudioBackend.isNativelyPlayable("/a/song.mp3"),
                "the JDK cannot decode mp3");
        assertFalse(AudioBackend.isNativelyPlayable("http://x/stream.wav"),
                "a stream is never native");
        assertFalse(AudioBackend.isNativelyPlayable(null));
    }

    @Test
    @DisplayName("a blank player or location yields an empty command")
    void blankCommand() {
        assertTrue(AudioBackend.playCommand("", "/a.wav").isEmpty());
        assertTrue(AudioBackend.playCommand("mpv", "  ").isEmpty());
        assertTrue(AudioBackend.playCommand(null, null).isEmpty());
    }

    @Test
    @DisplayName("each known player gets its audio-only / exit flags")
    void perPlayerCommands() {
        assertEquals(List.of("mpv", "--no-video", "--really-quiet", "/a.mp3"),
                AudioBackend.playCommand("mpv", "/a.mp3"));
        assertEquals(List.of("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", "u"),
                AudioBackend.playCommand("ffplay", "u"));
        assertEquals(List.of("mplayer", "-novideo", "u"),
                AudioBackend.playCommand("mplayer", "u"));
        assertEquals(List.of("cvlc", "--play-and-exit", "--no-video", "u"),
                AudioBackend.playCommand("cvlc", "u"));
        assertEquals(List.of("vlc", "--play-and-exit", "--intf", "dummy", "u"),
                AudioBackend.playCommand("vlc", "u"));
        // Unknown / plain players get just the location.
        assertEquals(List.of("mpg123", "u"), AudioBackend.playCommand("mpg123", "u"));
        assertEquals(List.of("totem", "u"), AudioBackend.playCommand("totem", "u"));
    }

    @Test
    @DisplayName("volume is added only for players that take a level flag")
    void volumeCommands() {
        assertEquals(List.of("mpv", "--no-video", "--really-quiet", "--volume=40", "u"),
                AudioBackend.playCommand("mpv", "u", 40));
        assertEquals(List.of("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet",
                        "-volume", "40", "u"),
                AudioBackend.playCommand("ffplay", "u", 40));
        assertEquals(List.of("mplayer", "-novideo", "-volume", "40", "u"),
                AudioBackend.playCommand("mplayer", "u", 40));
        // Out-of-range volume is ignored; players without a level flag ignore it.
        assertEquals(List.of("mpv", "--no-video", "--really-quiet", "u"),
                AudioBackend.playCommand("mpv", "u", -1));
        assertEquals(List.of("mpv", "--no-video", "--really-quiet", "u"),
                AudioBackend.playCommand("mpv", "u", 500));
        assertEquals(List.of("cvlc", "--play-and-exit", "--no-video", "u"),
                AudioBackend.playCommand("cvlc", "u", 50));
    }

    @Test
    @DisplayName("the first available known player wins, in preference order")
    void firstAvailable() {
        Set<String> present = Set.of("vlc", "mpg123");
        assertEquals(Optional.of("mpg123"),
                AudioBackend.firstAvailablePlayer(present::contains));
        assertEquals(Optional.empty(),
                AudioBackend.firstAvailablePlayer(s -> false));
        assertEquals(Optional.empty(), AudioBackend.firstAvailablePlayer(null));
    }

    @Test
    @DisplayName("a preferred player is honoured when available, else auto-detect")
    void resolvesPreferred() {
        Set<String> present = Set.of("vlc", "mpv");
        assertEquals(Optional.of("vlc"),
                AudioBackend.resolvePlayer("vlc", present::contains));
        // Preferred not present -> falls back to the first available known one.
        assertEquals(Optional.of("mpv"),
                AudioBackend.resolvePlayer("totem", present::contains));
        // No preference -> auto-detect.
        assertEquals(Optional.of("mpv"), AudioBackend.resolvePlayer("", present::contains));
        assertEquals(Optional.empty(), AudioBackend.resolvePlayer("mpv", s -> false));
    }
}
