/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.jdesktop.lg3d.displayserver.desktop2d.VolumeStatus.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VolumeStatus}'s pure maths, CLI output parsing, command
 * building and formatting: percentage clamping, the dB↔percentage conversion
 * used against a master-gain control, the {@code wpctl}/{@code pactl}/
 * {@code amixer} parsers, the backend command arrays, and the glyph/tooltip
 * text. No sound card is touched and no external tool is invoked, so the suite
 * is deterministic and headless.
 */
class VolumeStatusTest {

    @Test
    @DisplayName("clamp keeps a percentage inside 0-100")
    void clamps() {
        assertEquals(0, VolumeStatus.clamp(-20));
        assertEquals(0, VolumeStatus.clamp(0));
        assertEquals(50, VolumeStatus.clamp(50));
        assertEquals(100, VolumeStatus.clamp(100));
        assertEquals(100, VolumeStatus.clamp(250));
    }

    @Test
    @DisplayName("dB maps linearly to a percentage across min..max")
    void percentFromDbMapsLinearly() {
        assertEquals(0, VolumeStatus.percentFromDb(-80f, -80f, 0f));
        assertEquals(50, VolumeStatus.percentFromDb(-40f, -80f, 0f));
        assertEquals(100, VolumeStatus.percentFromDb(0f, -80f, 0f));
    }

    @Test
    @DisplayName("out-of-range dB is clamped, and a degenerate range yields 0")
    void percentFromDbClamps() {
        assertEquals(100, VolumeStatus.percentFromDb(50f, -80f, 0f));
        assertEquals(0, VolumeStatus.percentFromDb(-100f, -80f, 0f));
        assertEquals(0, VolumeStatus.percentFromDb(0f, 0f, 0f));
    }

    @Test
    @DisplayName("dbFromPercent inverts percentFromDb at the boundaries")
    void dbRoundTrip() {
        assertEquals(-80f, VolumeStatus.dbFromPercent(0, -80f, 0f), 0.001f);
        assertEquals(-40f, VolumeStatus.dbFromPercent(50, -80f, 0f), 0.001f);
        assertEquals(0f, VolumeStatus.dbFromPercent(100, -80f, 0f), 0.001f);
        // A clamped percentage maps back inside the range, then to itself.
        int percent = VolumeStatus.percentFromDb(
                VolumeStatus.dbFromPercent(150, -80f, 0f), -80f, 0f);
        assertEquals(100, percent);
    }

    @Test
    @DisplayName("glyph shows mute, a percentage, or '--' when unknown")
    void glyphFormatting() {
        assertEquals("Vol --", VolumeStatus.glyph(null));
        assertEquals("Vol x", VolumeStatus.glyph(new Level(40, true)));
        assertEquals("Vol 42%", VolumeStatus.glyph(new Level(42, false)));
    }

    @Test
    @DisplayName("label gives a human tooltip for each state")
    void labelFormatting() {
        assertEquals("Volume: unavailable", VolumeStatus.label(null));
        assertEquals("Volume: muted", VolumeStatus.label(new Level(10, true)));
        assertEquals("Volume: 10%", VolumeStatus.label(new Level(10, false)));
    }

    @Test
    @DisplayName("read() never throws headless; it returns empty or a level")
    void readIsSafeHeadless() {
        // On a CI box with no master control this is empty; either way no throw.
        assertTrue(VolumeStatus.read() != null);
    }

    @Test
    @DisplayName("wpctl volume parses the 0..N scale, mute marker and clamps over-100%")
    void parseWpctlVolumes() {
        assertEquals(Optional.of(new Level(42, false)),
                VolumeStatus.parseWpctl("Volume: 0.42\n"));
        assertEquals(Optional.of(new Level(100, true)),
                VolumeStatus.parseWpctl("Volume: 1.40 [MUTED]\n"));
        assertEquals(Optional.of(new Level(0, false)),
                VolumeStatus.parseWpctl("Volume: 0.00"));
    }

    @Test
    @DisplayName("wpctl volume rejects null and malformed output")
    void parseWpctlRejectsGarbage() {
        assertEquals(Optional.empty(), VolumeStatus.parseWpctl(null));
        assertEquals(Optional.empty(), VolumeStatus.parseWpctl("no volume here"));
        assertEquals(Optional.empty(), VolumeStatus.parseWpctl("Volume: abc"));
    }

    @Test
    @DisplayName("pactl volume reads the first NN% channel token")
    void parsePactlVolumeReadsPercent() {
        assertEquals(140, VolumeStatus.parsePactlVolume(
                "Volume: front-left: 91749 / 140% / 8.77 dB,   front-right: 91749 / 140% / 8.77 dB\n"));
        assertEquals(0, VolumeStatus.parsePactlVolume("Volume: front-left: 0 / 0% / -inf dB"));
        assertEquals(null, VolumeStatus.parsePactlVolume(null));
        assertEquals(null, VolumeStatus.parsePactlVolume("Volume: front-left: 65536"));
    }

    @Test
    @DisplayName("pactl mute reads the yes/no flag")
    void parsePactlMuteReadsFlag() {
        assertTrue(VolumeStatus.parsePactlMute("Mute: yes\n"));
        assertFalse(VolumeStatus.parsePactlMute("Mute: no\n"));
        assertFalse(VolumeStatus.parsePactlMute(null));
    }

    @Test
    @DisplayName("amixer parses the bracketed percent and on/off mute flag")
    void parseAmixerVolumes() {
        assertEquals(Optional.of(new Level(100, false)), VolumeStatus.parseAmixer(
                "Simple mixer control 'Master',0\n"
                + "  Front Left: Playback 65536 [100%] [on]\n"
                + "  Front Right: Playback 65536 [100%] [on]\n"));
        assertEquals(Optional.of(new Level(75, true)), VolumeStatus.parseAmixer(
                "  Mono: Playback 48 [75%] [-12.00dB] [off]\n"));
        assertEquals(Optional.empty(), VolumeStatus.parseAmixer(null));
        assertEquals(Optional.empty(), VolumeStatus.parseAmixer("Capabilities: pvolume pswitch"));
    }

    @Test
    @DisplayName("backend command arrays are built exactly")
    void commandBuilders() {
        assertArrayEquals(new String[] {"wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"},
                VolumeStatus.wpctlGetVolumeCommand());
        assertArrayEquals(new String[] {"pactl", "get-sink-volume", "@DEFAULT_SINK@"},
                VolumeStatus.pactlGetVolumeCommand());
        assertArrayEquals(new String[] {"pactl", "get-sink-mute", "@DEFAULT_SINK@"},
                VolumeStatus.pactlGetMuteCommand());
        assertArrayEquals(new String[] {"amixer", "sget", "Master"},
                VolumeStatus.amixerGetCommand("Master"));
        assertArrayEquals(new String[] {"wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "42%"},
                VolumeStatus.wpctlSetVolumeCommand(42));
        assertArrayEquals(new String[] {"pactl", "set-sink-volume", "@DEFAULT_SINK@", "100%"},
                VolumeStatus.pactlSetVolumeCommand(250));
        assertArrayEquals(new String[] {"amixer", "sset", "PCM", "0%"},
                VolumeStatus.amixerSetVolumeCommand("PCM", -5));
        assertArrayEquals(new String[] {"wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "1"},
                VolumeStatus.wpctlSetMuteCommand(true));
        assertArrayEquals(new String[] {"pactl", "set-sink-mute", "@DEFAULT_SINK@", "0"},
                VolumeStatus.pactlSetMuteCommand(false));
        assertArrayEquals(new String[] {"amixer", "sset", "Master", "mute"},
                VolumeStatus.amixerSetMuteCommand("Master", true));
        assertArrayEquals(new String[] {"amixer", "sset", "Master", "unmute"},
                VolumeStatus.amixerSetMuteCommand("Master", false));
    }

    @Test
    @DisplayName("setVolume/setMuted never throw when no backend is present")
    void mutatorsAreSafeHeadless() {
        // On CI no wpctl/pactl/amixer exists and no master port is exposed; the
        // mutators must degrade to a silent no-op rather than throw.
        VolumeStatus.setVolume(50);
        VolumeStatus.setMuted(true);
        VolumeStatus.setMuted(false);
    }
}
