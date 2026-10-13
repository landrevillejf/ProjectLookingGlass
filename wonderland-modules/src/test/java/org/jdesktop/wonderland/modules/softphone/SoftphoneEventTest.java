/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d: the stdout parser against the pool's literal
 * event spellings — the same evidence discipline: expectations are written
 * as the 2006 strings, not derived from the code.
 */
package org.jdesktop.wonderland.modules.softphone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SoftphoneEventTest {

    @Test
    @DisplayName("the visibility and mute echoes")
    void stateEchoes() {
        assertThat(SoftphoneEvent.parse("Softphone is visible"))
                .isEqualTo(new SoftphoneEvent.Visible(true));
        assertThat(SoftphoneEvent.parse("Softphone is hidden"))
                .isEqualTo(new SoftphoneEvent.Visible(false));
        assertThat(SoftphoneEvent.parse("invisible"))
                .isEqualTo(new SoftphoneEvent.Visible(false));
        assertThat(SoftphoneEvent.parse("Softphone Muted"))
                .isEqualTo(new SoftphoneEvent.Muted(true));
        assertThat(SoftphoneEvent.parse("mute"))
                .isEqualTo(new SoftphoneEvent.Muted(true));
        assertThat(SoftphoneEvent.parse("Softphone Unmuted"))
                .isEqualTo(new SoftphoneEvent.Muted(false));
        assertThat(SoftphoneEvent.parse("Unmute"))
                .isEqualTo(new SoftphoneEvent.Muted(false));
    }

    @Test
    @DisplayName("the connection and gain reports")
    void connectionAndGain() {
        assertThat(SoftphoneEvent.parse("Softphone is connected"))
                .isEqualTo(new SoftphoneEvent.Connected(true));
        assertThat(SoftphoneEvent.parse("Softphone is disconnected"))
                .isEqualTo(new SoftphoneEvent.Connected(false));
        assertThat(SoftphoneEvent.parse("microphoneGainTooHigh"))
                .isInstanceOf(SoftphoneEvent.MicrophoneGainTooHigh.class);
    }

    @Test
    @DisplayName("the exit line with and without a status number")
    void exitLines() {
        assertThat(SoftphoneEvent.parse("Softphone exited with status 3"))
                .isEqualTo(new SoftphoneEvent.Exited(3));
        assertThat(SoftphoneEvent.parse("Softphone exited"))
                .isEqualTo(new SoftphoneEvent.Exited(0));
        assertThat(SoftphoneEvent.parse("Softphone exited abnormally"))
                .isEqualTo(new SoftphoneEvent.Exited(0));   // no trailing number
    }

    @Test
    @DisplayName("the UDP loopback echo, complete and short")
    void udpTestPorts() {
        assertThat(SoftphoneEvent.parse("softphoneTestUDPPort 5004 5006"))
                .isEqualTo(new SoftphoneEvent.TestUdpPorts(5004, 5006));
        assertThat(SoftphoneEvent.parse("softphoneTestUDPPort"))
                .isEqualTo(new SoftphoneEvent.TestUdpPorts(-1, -1));
        assertThat(SoftphoneEvent.parse("softphoneTestUDPPort 5004"))
                .isEqualTo(new SoftphoneEvent.TestUdpPorts(-1, 5004));
    }

    @Test
    @DisplayName("the meter lines split device from reading")
    void vuLines() {
        assertThat(SoftphoneEvent.parse("Microphone level: 42%"))
                .isEqualTo(new SoftphoneEvent.VuLevel("Microphone", "42%"));
        assertThat(SoftphoneEvent.parse("Speaker level: 7%"))
                .isEqualTo(new SoftphoneEvent.VuLevel("Speaker", "7%"));
        // a meter line with no colon carries the device but no reading
        assertThat(SoftphoneEvent.parse("Microphone level"))
                .isEqualTo(new SoftphoneEvent.VuLevel("Microphone", ""));
    }

    @Test
    @DisplayName("anything else is a Problem, verbatim; blank lines drop")
    void problemsAreVerbatim() {
        assertThat(SoftphoneEvent.parse("SipStarter sending Shutdown to softphone"))
                .isEqualTo(new SoftphoneEvent.Problem(
                        "SipStarter sending Shutdown to softphone"));
        assertThat(SoftphoneEvent.parse("   "))
                .isEqualTo(new SoftphoneEvent.Problem(""));
        assertThat(SoftphoneEvent.parse(null))
                .isEqualTo(new SoftphoneEvent.Problem(""));
    }

    @Test
    @DisplayName("matching is case-sensitive: the producer is literal")
    void caseMatters() {
        assertThat(SoftphoneEvent.parse("softphone is visible"))
                .isInstanceOf(SoftphoneEvent.Problem.class);
    }
}
