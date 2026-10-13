/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d: the RFC 3550 header bit-layout, the modulo-2^16
 * sequence rule, and the parser's refusals — all provable with bytes alone.
 */
package org.jdesktop.wonderland.modules.softphone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtpPacketTest {

    private static RtpPacket sample(int seq) {
        return new RtpPacket(false, false, 0, G711.MU_LAW_PAYLOAD_TYPE,
                seq, 160 * seq, 0x12345678L, new byte[]{1, 2, 3, 4});
    }

    @Test
    @DisplayName("the 12 fixed header bytes land exactly where RFC 3550 says")
    void headerLayout() {
        byte[] wire = new RtpPacket(false, false, 1, 0, 0x1234,
                0xdeadbeefL, 0x0f0e0d0cL, new byte[]{(byte) 0xaa})
                .toByteArray();
        assertThat(wire).hasSize(13);
        assertThat(wire[0] & 0xff).isEqualTo(0x80);        // v=2, cc=0
        assertThat(wire[1] & 0xff).isEqualTo(0x80);        // m=1, pt=0
        assertThat(wire[2] & 0xff).isEqualTo(0x12);
        assertThat(wire[3] & 0xff).isEqualTo(0x34);
        assertThat(wire[4] & 0xff).isEqualTo(0xde);
        assertThat(wire[5] & 0xff).isEqualTo(0xad);
        assertThat(wire[6] & 0xff).isEqualTo(0xbe);
        assertThat(wire[7] & 0xff).isEqualTo(0xef);
        assertThat(wire[8] & 0xff).isEqualTo(0x0f);
        assertThat(wire[9] & 0xff).isEqualTo(0x0e);
        assertThat(wire[10] & 0xff).isEqualTo(0x0d);
        assertThat(wire[11] & 0xff).isEqualTo(0x0c);
        assertThat(wire[12] & 0xff).isEqualTo(0xaa);
    }

    @Test
    @DisplayName("parse(toByteArray()) is the identity on every field")
    void roundTrip() {
        RtpPacket back = RtpPacket.parse(sample(7).toByteArray(), 16);
        assertThat(back.getSequenceNumber()).isEqualTo(7);
        assertThat(back.getTimestamp()).isEqualTo(160 * 7);
        assertThat(back.getSsrc()).isEqualTo(0x12345678L);
        assertThat(back.getPayloadType()).isZero();
        assertThat(back.getMarker()).isZero();
        assertThat(back.getPayload()).containsExactly(1, 2, 3, 4);
        assertThat(back.isPadding()).isFalse();
        assertThat(back.isExtension()).isFalse();
    }

    @Test
    @DisplayName("the padding flag survives the round trip")
    void flagsSurvive() {
        RtpPacket pad = new RtpPacket(true, false, 0, 0, 1, 2, 3, new byte[]{9});
        byte[] wire = pad.toByteArray();
        RtpPacket back = RtpPacket.parse(wire, wire.length);
        assertThat(back.isPadding()).isTrue();
        assertThat(back.getPayload()).containsExactly(9);   // the pad octet is gone
    }

    @Test
    @DisplayName("sequence arithmetic wraps at 2^16, and seqBefore reads the circle")
    void sequenceWrap() {
        assertThat(sample(65535).nextSequence().getSequenceNumber()).isZero();
        assertThat(RtpPacket.seqBefore(65535, 1)).isTrue();   // across a wrap
        assertThat(RtpPacket.seqBefore(1, 65535)).isFalse();
        assertThat(RtpPacket.seqBefore(5, 6)).isTrue();
        assertThat(RtpPacket.seqBefore(5, 5)).isFalse();      // equal is not before
        // the shortest path reads 65535 -> 0 as +1 forward, so 0 is AFTER 65535
        assertThat(RtpPacket.seqBefore(0, 65535)).isFalse();
    }

    @Test
    @DisplayName("field widths are enforced on write")
    void constructorBounds() {
        assertThatThrownBy(() -> sample(70000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket(false, false, 8, 0, 1, 2, 3, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket(false, false, -1, 0, 1, 2, 3, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket(false, false, 0, 128, 1, 2, 3, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket(false, false, 0, -1, 1, 2, 3, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket(false, false, 0, 0, 1, -1, 3, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket(false, false, 0, 0, 1, 0x100000000L, 3, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket(false, false, 0, 0, 1, 2, -1, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket(false, false, 0, 0, 1, 2, 0x100000000L, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the parser refuses what the 2006 stack never spoke")
    void parserRefusals() {
        byte[] good = sample(7).toByteArray();
        // version 0
        byte[] v0 = good.clone();
        v0[0] = 0;
        assertThatThrownBy(() -> RtpPacket.parse(v0, v0.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        // short datagram
        assertThatThrownBy(() -> RtpPacket.parse(good, 11))
                .isInstanceOf(IllegalArgumentException.class);
        // the M bit is the marker, NOT part of the codec number: a talk-spurt
        // packet from any real peer decodes as pt=0 m=1 instead of being
        // mis-negotiated (or, worse, refused as a "profile symbol")
        byte[] marked = good.clone();
        marked[1] = (byte) 0x80;
        RtpPacket back = RtpPacket.parse(marked, marked.length);
        assertThat(back.getMarker()).isEqualTo(1);
        assertThat(back.getPayloadType()).isZero();
        // csrc list: mixers only, never this client
        byte[] csrc = good.clone();
        csrc[0] = (byte) 0x81;   // cc=1
        byte[] withCsrc = new byte[good.length + 4];
        System.arraycopy(csrc, 0, withCsrc, 0, csrc.length);
        assertThatThrownBy(() -> RtpPacket.parse(withCsrc, withCsrc.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CSRC");
    }

    @Test
    @DisplayName("a payload copy is defensive in both directions")
    void defensiveCopies() {
        byte[] payload = {1, 2, 3};
        RtpPacket packet = new RtpPacket(false, false, 0, 0, 1, 2, 3, payload);
        payload[0] = 99;
        assertThat(packet.getPayload()).containsExactly(1, 2, 3);
        packet.getPayload()[1] = 99;
        assertThat(packet.getPayload()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a random SSRC stays in the 31-bit RFC recommendation")
    void ssrcRange() {
        for (int i = 0; i < 1000; i++) {
            assertThat(RtpPacket.randomSsrc()).isBetween(0L, 0x7fffffffL);
        }
    }

    @Test
    @DisplayName("the extension header is skipped, its overruns refused")
    void extensionHeader() {
        // hand-built: v=2, X=1, one 4-byte extension header of zero words
        byte[] wire = new RtpPacket(false, false, 1, 0, 9, 3, 4, new byte[]{5, 6})
                .toByteArray();
        byte[] withExt = new byte[wire.length + 4];
        System.arraycopy(wire, 0, withExt, 0, 12);      // the fixed header
        withExt[0] = (byte) (withExt[0] | 0x10);        // X=1
        withExt[12] = (byte) 0xBE;
        withExt[13] = (byte) 0xDE;
        withExt[14] = 0;
        withExt[15] = 0;                          // zero 32-bit words of body
        System.arraycopy(wire, 12, withExt, 16, wire.length - 12);
        RtpPacket back = RtpPacket.parse(withExt, withExt.length);
        assertThat(back.isExtension()).isTrue();
        assertThat(back.getMarker()).isEqualTo(1);
        assertThat(back.getPayload()).containsExactly(5, 6);
        // the X bit is authored too (the voice path never sends one, but the
        // writer expresses what the reader accepts)
        assertThat(new RtpPacket(false, true, 0, 0, 1, 2, 3, new byte[0])
                .toByteArray()[0] & 0x10).isEqualTo(0x10);

        // extension flag but no 4 bytes left to even read the word count
        assertThatThrownBy(() -> RtpPacket.parse(withExt, 14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension header");
        // a declared body longer than the datagram
        byte[] lying = withExt.clone();
        lying[15] = 20;                           // 20 words nobody sent
        assertThatThrownBy(() -> RtpPacket.parse(lying, lying.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension body");
    }

    @Test
    @DisplayName("a padding count that does not fit the datagram is refused")
    void impossiblePadding() {
        byte[] wire = new RtpPacket(true, false, 0, 0, 1, 2, 3, new byte[]{9, 9})
                .toByteArray();
        byte[] zeroPad = wire.clone();
        zeroPad[zeroPad.length - 1] = 0;          // pad count can never be 0
        assertThatThrownBy(() -> RtpPacket.parse(zeroPad, zeroPad.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("padding");
        byte[] lying = sample(1).toByteArray();
        lying[0] = (byte) (lying[0] | 0x20);      // pad flag on
        lying[lying.length - 1] = 20;             // ... 20 pad octets, datagram has 1
        assertThatThrownBy(() -> RtpPacket.parse(lying, lying.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("padding");
    }

    @Test
    @DisplayName("a CSRC list that overruns the datagram is refused")
    void csrcOverrun() {
        byte[] wire = sample(1).toByteArray();
        wire[0] = (byte) (wire[0] | 0x03);        // cc=3, but no csrc bytes follow
        assertThatThrownBy(() -> RtpPacket.parse(wire, wire.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("csrc count");
    }

    @Test
    @DisplayName("parse reads a received DatagramPacket directly")
    void parseFromDatagram() {
        byte[] wire = sample(42).toByteArray();
        RtpPacket back = RtpPacket.parse(
                new java.net.DatagramPacket(wire, wire.length));
        assertThat(back.getSequenceNumber()).isEqualTo(42);
        assertThat(back.getPayload()).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("toString names the fields, and the marker only when set")
    void toStringShape() {
        assertThat(sample(3).toString())
                .isEqualTo("RTP pt=0 seq=3 ts=480 ssrc=305419896 len=4");
        assertThat(new RtpPacket(false, false, 1, 0, 1, 2, 3, new byte[0]).toString())
                .endsWith(" m=1");
    }
}
