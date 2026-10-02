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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link RipSettings}: the defaults, the setters' clamping / normalising
 * of a hand-edited config, and a Jackson round trip. Pure bean logic, so it runs
 * headless.
 */
class RipSettingsTest {

    @Test
    @DisplayName("defaults are MP3 at the CD-native rate and default bitrate")
    void defaults() {
        RipSettings s = new RipSettings();
        assertEquals(CdRipBackend.Format.MP3, s.getFormat());
        assertEquals(CdRipBackend.DEFAULT_SAMPLE_RATE, s.getSampleRate());
        assertEquals(CdRipBackend.DEFAULT_BITRATE, s.getMp3Bitrate());
        assertFalse(s.getOutputDir().isBlank(), "a blank output dir resolves to the default");
        assertTrue(s.getOutputDir().endsWith("music"));
        assertEquals("", s.getDevice());
        assertEquals("", s.getPreferredRipper());
        assertEquals("", s.getPreferredEncoder());
    }

    @Test
    @DisplayName("unsupported rates/bitrates snap to the defaults")
    void clamps() {
        RipSettings s = new RipSettings();
        s.setSampleRate(22050);
        assertEquals(22050, s.getSampleRate());
        s.setSampleRate(4321);
        assertEquals(CdRipBackend.DEFAULT_SAMPLE_RATE, s.getSampleRate());
        s.setMp3Bitrate(320);
        assertEquals(320, s.getMp3Bitrate());
        s.setMp3Bitrate(64);
        assertEquals(CdRipBackend.DEFAULT_BITRATE, s.getMp3Bitrate());
    }

    @Test
    @DisplayName("null inputs fall back rather than nulling the bean")
    void nullSafe() {
        RipSettings s = new RipSettings();
        s.setFormat(null);
        assertEquals(CdRipBackend.Format.MP3, s.getFormat());
        s.setFormat(CdRipBackend.Format.WAV);
        assertEquals(CdRipBackend.Format.WAV, s.getFormat());
        s.setOutputDir(null);
        assertFalse(s.getOutputDir().isBlank());
        s.setDevice(null);
        assertEquals("", s.getDevice());
        s.setPreferredRipper(null);
        assertEquals("", s.getPreferredRipper());
    }

    @Test
    @DisplayName("values are trimmed")
    void trims() {
        RipSettings s = new RipSettings();
        s.setDevice("   /dev/sr0  ");
        assertEquals("/dev/sr0", s.getDevice());
        s.setOutputDir("  /music/rips ");
        assertEquals("/music/rips", s.getOutputDir());
    }

    @Test
    @DisplayName("the bean survives a Jackson round trip")
    void jacksonRoundTrip() throws Exception {
        RipSettings s = new RipSettings();
        s.setFormat(CdRipBackend.Format.WAV);
        s.setSampleRate(48000);
        s.setMp3Bitrate(256);
        s.setOutputDir("/tmp/rips");
        s.setDevice("/dev/sr1");
        s.setPreferredRipper("cdparanoia");
        s.setPreferredEncoder("ffmpeg");

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(s);
        RipSettings back = mapper.readValue(json, RipSettings.class);

        assertEquals(CdRipBackend.Format.WAV, back.getFormat());
        assertEquals(48000, back.getSampleRate());
        assertEquals(256, back.getMp3Bitrate());
        assertEquals("/tmp/rips", back.getOutputDir());
        assertEquals("/dev/sr1", back.getDevice());
        assertEquals("cdparanoia", back.getPreferredRipper());
        assertEquals("ffmpeg", back.getPreferredEncoder());
    }

    @Test
    @DisplayName("unknown JSON properties are ignored")
    void ignoresUnknown() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RipSettings back = mapper.readValue(
                "{\"format\":\"MP3\",\"bogus\":123}", RipSettings.class);
        assertEquals(CdRipBackend.Format.MP3, back.getFormat());
    }
}
