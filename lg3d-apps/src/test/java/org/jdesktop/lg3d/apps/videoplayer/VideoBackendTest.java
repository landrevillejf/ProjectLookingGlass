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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link VideoBackend} launch-decision seam: extension parsing,
 * stream / disc classification, disc URL building, the per-player command lines
 * (with full-screen and volume), and the availability / preference resolution.
 * No process is started, so it runs headless.
 */
class VideoBackendTest {

    @Test
    @DisplayName("extension strips directories and query strings")
    void parsesExtension() {
        assertEquals("mkv", VideoBackend.extension("/movies/Film.MKV"));
        assertEquals("", VideoBackend.extension("/movies/noext"));
        assertEquals("mp4", VideoBackend.extension("https://x/v.mp4?token=1"));
        assertEquals("", VideoBackend.extension(null));
        assertEquals("", VideoBackend.extension("   "));
    }

    @Test
    @DisplayName("isStream recognises network schemes only")
    void detectsStream() {
        assertTrue(VideoBackend.isStream("http://x/live"));
        assertTrue(VideoBackend.isStream("RTSP://cam/feed"));
        assertFalse(VideoBackend.isStream("/home/me/film.mp4"));
        assertFalse(VideoBackend.isStream("dvd:///dev/sr0"));
        assertFalse(VideoBackend.isStream(null));
    }

    @Test
    @DisplayName("disc URLs and devices are recognised")
    void detectsDisc() {
        assertTrue(VideoBackend.isDiscUrl("dvd:///dev/sr0"));
        assertTrue(VideoBackend.isDiscUrl("bd:///dev/sr1"));
        assertFalse(VideoBackend.isDiscUrl("/movies/x.mp4"));
        assertTrue(VideoBackend.isDiscDevice("/dev/sr0"));
        assertTrue(VideoBackend.isDiscDevice("/dev/dvd"));
        assertFalse(VideoBackend.isDiscDevice("/dev/sda"));
        assertFalse(VideoBackend.isDiscDevice(null));
        assertEquals("dvd:///dev/sr0", VideoBackend.discUrl(null, "/dev/sr0"));
        assertEquals("bd:///dev/sr0", VideoBackend.discUrl("bd", "/dev/sr0"));
    }

    @Test
    @DisplayName("a blank player or location yields an empty command")
    void blankCommand() {
        assertTrue(VideoBackend.playCommand("", "/a.mp4").isEmpty());
        assertTrue(VideoBackend.playCommand("vlc", "  ").isEmpty());
        assertTrue(VideoBackend.playCommand(null, null).isEmpty());
    }

    @Test
    @DisplayName("each known player gets its launch flags")
    void perPlayerCommands() {
        assertEquals(List.of("vlc", "--play-and-exit", "/a.mp4"),
                VideoBackend.playCommand("vlc", "/a.mp4"));
        assertEquals(List.of("mpv", "--really-quiet", "/a.mp4"),
                VideoBackend.playCommand("mpv", "/a.mp4"));
        assertEquals(List.of("ffplay", "-autoexit", "-loglevel", "quiet", "u"),
                VideoBackend.playCommand("ffplay", "u"));
        assertEquals(List.of("mplayer", "u"),
                VideoBackend.playCommand("mplayer", "u"));
        assertEquals(List.of("totem", "u"), VideoBackend.playCommand("totem", "u"));
    }

    @Test
    @DisplayName("full-screen and volume are added where the player takes them")
    void fullscreenAndVolume() {
        assertEquals(List.of("vlc", "--play-and-exit", "--fullscreen", "/a.mp4"),
                VideoBackend.playCommand("vlc", "/a.mp4", true, -1));
        assertEquals(List.of("mpv", "--really-quiet", "--fullscreen", "--volume=40",
                        "/a.mp4"),
                VideoBackend.playCommand("mpv", "/a.mp4", true, 40));
        assertEquals(List.of("mplayer", "-fs", "-volume", "40", "u"),
                VideoBackend.playCommand("mplayer", "u", true, 40));
        // Out-of-range volume is ignored; players without flags ignore both.
        assertEquals(List.of("mpv", "--really-quiet", "/a.mp4"),
                VideoBackend.playCommand("mpv", "/a.mp4", false, 500));
        assertEquals(List.of("totem", "u"),
                VideoBackend.playCommand("totem", "u", true, 50));
    }

    @Test
    @DisplayName("the first available known player wins, in preference order")
    void firstAvailable() {
        Set<String> present = Set.of("totem", "mpv");
        assertEquals(Optional.of("mpv"),
                VideoBackend.firstAvailablePlayer(present::contains));
        assertEquals(Optional.empty(), VideoBackend.firstAvailablePlayer(s -> false));
        assertEquals(Optional.empty(), VideoBackend.firstAvailablePlayer(null));
    }

    @Test
    @DisplayName("a preferred player is honoured when available, else auto-detect")
    void resolvesPreferred() {
        Set<String> present = Set.of("mpv", "vlc");
        assertEquals(Optional.of("mpv"), VideoBackend.resolvePlayer("mpv", present::contains));
        assertEquals(Optional.of("vlc"), VideoBackend.resolvePlayer("kodi", present::contains));
        assertEquals(Optional.of("vlc"), VideoBackend.resolvePlayer("", present::contains));
        assertEquals(Optional.empty(), VideoBackend.resolvePlayer("vlc", s -> false));
    }
}
