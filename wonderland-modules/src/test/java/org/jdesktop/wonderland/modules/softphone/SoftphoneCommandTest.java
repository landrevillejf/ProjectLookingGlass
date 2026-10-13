/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d: every stdin line against the 2006 protocol
 * vocabulary — the tokens extracted verbatim from SoftphoneControlImpl's
 * constant pool. A drift here is a softphone that stops answering, so the
 * expected strings are written as literals, not recomputed from the code.
 */
package org.jdesktop.wonderland.modules.softphone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SoftphoneCommandTest {

    @Test
    @DisplayName("the registration pair")
    void registerAndCallId() {
        assertThat(SoftphoneCommand.register("alice").toLine())
                .isEqualTo("register alice");
        assertThat(SoftphoneCommand.callId("c-1").toLine())
                .isEqualTo("callID=c-1");
    }

    @Test
    @DisplayName("mute keeps the pool's exact casing, Unmute included")
    void mutePair() {
        assertThat(SoftphoneCommand.mute().toLine()).isEqualTo("mute");
        assertThat(SoftphoneCommand.unmute().toLine()).isEqualTo("Unmute");
    }

    @Test
    @DisplayName("quality names the preset by enum constant")
    void qualityLine() {
        assertThat(SoftphoneCommand.quality(AudioQuality.BEST).toLine())
                .isEqualTo("quality BEST");
        assertThat(SoftphoneCommand.quality(AudioQuality.MINIMUM).toLine())
                .isEqualTo("quality MINIMUM");
    }

    @Test
    @DisplayName("the recorder: one '=' argument line, three bare verbs")
    void recorderLines() {
        assertThat(SoftphoneCommand.recordAudio("/tmp/call.wav").toLine())
                .isEqualTo("recordAudio=/tmp/call.wav");
        assertThat(SoftphoneCommand.resumeRecording().toLine()).isEqualTo("record");
        assertThat(SoftphoneCommand.pauseRecording().toLine()).isEqualTo("pause");
        assertThat(SoftphoneCommand.stopRecording().toLine()).isEqualTo("stop");
    }

    @Test
    @DisplayName("test, dial, window and goodbye lines")
    void protocolRest() {
        assertThat(SoftphoneCommand.lineTest().toLine()).isEqualTo("linetest");
        assertThat(SoftphoneCommand.placeCall("conf-9", "20315").toLine())
                .isEqualTo("PlaceCall=conferenceId:conf-9,callee=20315");
        assertThat(SoftphoneCommand.setVisible(true).toLine()).isEqualTo("visible");
        assertThat(SoftphoneCommand.setVisible(false).toLine()).isEqualTo("invisible");
        assertThat(SoftphoneCommand.shutdown().toLine()).isEqualTo("Shutdown");
    }

    @Test
    @DisplayName("the verbatim escape hatch trims but never rewrites")
    void verbatim() {
        assertThat(SoftphoneCommand.verbatim("  someDialect  ").toLine())
                .isEqualTo("someDialect");
        // the command is a value whose text IS the protocol line
        assertThat(SoftphoneCommand.shutdown()).hasToString("Shutdown");
    }

    @Test
    @DisplayName("AudioQuality: the 2006 values and the forgiving parse")
    void audioQualityValues() {
        assertThat(AudioQuality.MINIMUM.sampleRate()).isEqualTo(8000);
        assertThat(AudioQuality.MINIMUM.channels()).isEqualTo(1);
        assertThat(AudioQuality.STEREO.sampleRate()).isEqualTo(8000);
        assertThat(AudioQuality.STEREO.channels()).isEqualTo(2);
        assertThat(AudioQuality.VPN.sampleRate()).isEqualTo(16000);
        assertThat(AudioQuality.BEST.sampleRate()).isEqualTo(44100);
        // every preset transmits mono: G.711 is an 8 kHz mono codec
        for (AudioQuality quality : AudioQuality.values()) {
            assertThat(quality.transmitChannels()).isEqualTo(1);
            // and at its own capture rate — the 2006 mixer only down-mixed
            // channels, never resampled
            assertThat(quality.transmitSampleRate()).isEqualTo(quality.sampleRate());
        }
        assertThat(AudioQuality.VPN.description())
                .isEqualTo("Normal (16k stereo)");
        // the preference panel showed the description; toString IS that label
        assertThat(AudioQuality.MINIMUM).hasToString("Minimum (8k mono)");
    }

    @Test
    @DisplayName("parse is case-insensitive, trims, and forgives the unknown")
    void audioQualityParse() {
        assertThat(AudioQuality.parse("vpn")).isEqualTo(AudioQuality.VPN);
        assertThat(AudioQuality.parse("  BEST ")).isEqualTo(AudioQuality.BEST);
        assertThat(AudioQuality.parse("nonsense")).isNull();
        assertThat(AudioQuality.parse(null)).isNull();
    }
}
