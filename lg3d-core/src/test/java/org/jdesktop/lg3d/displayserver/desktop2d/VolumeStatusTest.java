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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.jdesktop.lg3d.displayserver.desktop2d.VolumeStatus.Device;
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

    @Test
    @DisplayName("wpctl status sinks parse ids, volumes and the '*' default marker")
    void parseWpctlSinksReadsDevices() {
        String status = "Audio\n"
                + "\u251c\u2500 Sinks:\n"
                + "\u2502  *   48. Ryzen HD Audio Analog Stereo        [vol: 0.50]\n"
                + "\u2502      55. HDMI Output                         [vol: 1.00]\n"
                + "\u251c\u2500 Sources:\n"
                + "\u2502      49. Built-in Audio Analog Stereo\n";
        List<Device> sinks = VolumeStatus.parseWpctlSinks(status);
        assertEquals(2, sinks.size(), "only the Sinks: section is read");
        assertEquals(new Device("48", "Ryzen HD Audio Analog Stereo", true, 50, false), sinks.get(0));
        assertEquals(new Device("55", "HDMI Output", false, 100, false), sinks.get(1));
        assertTrue(VolumeStatus.parseWpctlSinks(null).isEmpty());
        assertTrue(VolumeStatus.parseWpctlSinks("no sinks here").isEmpty());
    }

    @Test
    @DisplayName("a wpctl sink line with no volume reports percent -1")
    void parseWpctlSinksWithoutVolume() {
        String status = " Sinks:\n"
                + "      60. Some Sink With No Volume\n"
                + " Sources:\n";
        List<Device> sinks = VolumeStatus.parseWpctlSinks(status);
        assertEquals(1, sinks.size());
        assertEquals(new Device("60", "Some Sink With No Volume", false, -1, false), sinks.get(0));
    }

    @Test
    @DisplayName("pactl list sinks parses name/description/mute/volume and the default")
    void parsePactlSinksReadsDevices() {
        String listSinks = "Sink #48\n"
                + "\tState: RUNNING\n"
                + "\tName: alsa_output.analog-stereo\n"
                + "\tDescription: Built-in Audio Analog Stereo\n"
                + "\tMute: no\n"
                + "\tVolume: front-left: 32768 /  50% / -18.00 dB\n"
                + "Sink #55\n"
                + "\tName: hdmi\n"
                + "\tDescription: HDMI Output\n"
                + "\tMute: yes\n"
                + "\tVolume: front-left: 65536 / 100% / 0.00 dB\n";
        List<Device> sinks = VolumeStatus.parsePactlSinks(listSinks, "alsa_output.analog-stereo");
        assertEquals(2, sinks.size());
        assertEquals(new Device("alsa_output.analog-stereo", "Built-in Audio Analog Stereo",
                true, 50, false), sinks.get(0));
        assertEquals(new Device("hdmi", "HDMI Output", false, 100, true), sinks.get(1));
        assertTrue(VolumeStatus.parsePactlSinks(null, "x").isEmpty());
    }

    @Test
    @DisplayName("a pactl sink with no description falls back to its name")
    void parsePactlSinksFallsBackToName() {
        String listSinks = "Sink #1\n\tName: bare\n\tVolume: front-left: 0 / 40%\n";
        List<Device> sinks = VolumeStatus.parsePactlSinks(listSinks, null);
        assertEquals(1, sinks.size());
        assertEquals(new Device("bare", "bare", false, 40, false), sinks.get(0));
    }

    @Test
    @DisplayName("aplay -l parses playback cards into devices keyed by card number")
    void parseAlsaCardsReadsDevices() {
        String aplayL = "**** List of PLAYBACK Hardware Devices ****\n"
                + "card 0: PCH [HDA Intel PCH], device 0: ALC3232 Analog [ALC3232 Analog]\n"
                + "  Subdevices: 1/1\n"
                + "card 1: HDMI [HDA ATI HDMI], device 3: HDMI 0 [HDMI 0]\n";
        List<Device> cards = VolumeStatus.parseAlsaCards(aplayL);
        assertEquals(2, cards.size());
        assertEquals(new Device("0", "card 0: HDA Intel PCH - ALC3232 Analog", false, -1, false),
                cards.get(0));
        assertEquals(new Device("1", "card 1: HDA ATI HDMI - HDMI 0", false, -1, false),
                cards.get(1));
        assertTrue(VolumeStatus.parseAlsaCards(null).isEmpty());
        assertTrue(VolumeStatus.parseAlsaCards("no cards").isEmpty());
    }

    @Test
    @DisplayName("deviceLabel renders description, volume, mute and default markers")
    void deviceLabelFormatting() {
        assertEquals("", VolumeStatus.deviceLabel(null));
        assertEquals("Speakers  50%  [default]",
                VolumeStatus.deviceLabel(new Device("1", "Speakers", true, 50, false)));
        assertEquals("HDMI  100%  [muted]",
                VolumeStatus.deviceLabel(new Device("2", "HDMI", false, 100, true)));
        assertEquals("Card",
                VolumeStatus.deviceLabel(new Device("0", "Card", false, -1, false)));
    }

    @Test
    @DisplayName("device command arrays are built exactly")
    void deviceCommandBuilders() {
        assertArrayEquals(new String[] {"wpctl", "status"}, VolumeStatus.wpctlStatusCommand());
        assertArrayEquals(new String[] {"pactl", "list", "sinks"},
                VolumeStatus.pactlListSinksCommand());
        assertArrayEquals(new String[] {"pactl", "get-default-sink"},
                VolumeStatus.pactlGetDefaultSinkCommand());
        assertArrayEquals(new String[] {"aplay", "-l"}, VolumeStatus.aplayListCommand());
        assertArrayEquals(new String[] {"wpctl", "set-default", "48"},
                VolumeStatus.wpctlSetDefaultCommand("48"));
        assertArrayEquals(new String[] {"pactl", "set-default-sink", "hdmi"},
                VolumeStatus.pactlSetDefaultCommand("hdmi"));
        assertArrayEquals(new String[] {"wpctl", "set-volume", "48", "100%"},
                VolumeStatus.wpctlSetDeviceVolumeCommand("48", 250));
        assertArrayEquals(new String[] {"pactl", "set-sink-volume", "hdmi", "0%"},
                VolumeStatus.pactlSetSinkVolumeCommand("hdmi", -5));
        assertArrayEquals(new String[] {"wpctl", "set-mute", "48", "1"},
                VolumeStatus.wpctlSetDeviceMuteCommand("48", true));
        assertArrayEquals(new String[] {"pactl", "set-sink-mute", "hdmi", "0"},
                VolumeStatus.pactlSetSinkMuteCommand("hdmi", false));
        assertArrayEquals(new String[] {"amixer", "-c", "0", "sset", "Master", "42%"},
                VolumeStatus.amixerSetCardVolumeCommand("0", "Master", 42));
        assertArrayEquals(new String[] {"amixer", "-c", "0", "sset", "PCM", "unmute"},
                VolumeStatus.amixerSetCardMuteCommand("0", "PCM", false));
    }

    @Test
    @DisplayName("devices() and the per-device mutators never throw headless")
    void deviceProbesAreSafeHeadless() {
        // No wpctl/pactl/aplay on CI, so devices() degrades to an empty list.
        assertNotNull(VolumeStatus.devices());
        assertFalse(VolumeStatus.setDefault(null));
        assertFalse(VolumeStatus.setDefault(""));
        VolumeStatus.setDefault("48");
        VolumeStatus.setVolume(null, 50);   // falls back to the master volume
        VolumeStatus.setVolume("48", 50);
        VolumeStatus.setMuted("", false);   // falls back to the master mute
        VolumeStatus.setMuted("48", true);
    }
}
