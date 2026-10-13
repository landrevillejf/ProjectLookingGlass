/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps Wonderland M7d: the voice bridge's device-free half. The frame
 * packing and the little-endian sample conversion Java Sound relies on carry
 * no microphone, speaker or socket, so they are proven here; the four worker
 * threads that touch those devices are compile-checked and verified live, never
 * claimed from this headless run.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.wonderland.modules.softphone.G711;
import org.jdesktop.wonderland.modules.softphone.RtpPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JavaSoundVoiceBridgeTest {

    @Test
    @DisplayName("encodeFrame mu-law compresses one full frame and stamps the header")
    void encodeFrameFramesOnePacket() {
        short[] pcm = new short[JavaSoundVoiceBridge.SAMPLES_PER_PACKET];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (i * 100 - 8000);
        }
        RtpPacket packet = JavaSoundVoiceBridge.encodeFrame(pcm, 1234, 2000L, 0xdeadbeefL);

        assertEquals(G711.MU_LAW_PAYLOAD_TYPE, packet.getPayloadType());
        assertEquals(1234, packet.getSequenceNumber());
        assertEquals(2000L, packet.getTimestamp());
        assertEquals(0xdeadbeefL, packet.getSsrc());
        assertEquals(pcm.length, packet.getPayload().length);
        assertTrue(!packet.isPadding() && !packet.isExtension());
    }

    @Test
    @DisplayName("decodePayload(encodeFrame(x)) lands within one quantisation step")
    void encodeDecodeRoundTrip() {
        short[] pcm = new short[JavaSoundVoiceBridge.SAMPLES_PER_PACKET];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (i * 100 - 8000);
        }
        RtpPacket packet = JavaSoundVoiceBridge.encodeFrame(pcm, 0, 0L, 1L);
        short[] back = JavaSoundVoiceBridge.decodePayload(packet);

        assertEquals(pcm.length, back.length);
        for (int i = 0; i < pcm.length; i++) {
            assertTrue(Math.abs(back[i] - pcm[i]) <= 0x400,
                    "sample " + i + " drifted too far: " + pcm[i] + " -> " + back[i]);
        }
    }

    @Test
    @DisplayName("a wire datagram survives serialise, parse and de-jitter intact")
    void frameSurvivesTheWire() {
        short[] pcm = {1000, -2000, 3000, 0, Short.MAX_VALUE, Short.MIN_VALUE};
        RtpPacket packet = JavaSoundVoiceBridge.encodeFrame(pcm, 7, 1120L, 99L);

        byte[] wire = packet.toByteArray();
        RtpPacket parsed = RtpPacket.parse(wire, wire.length);

        assertEquals(packet.getSequenceNumber(), parsed.getSequenceNumber());
        assertEquals(packet.getTimestamp(), parsed.getTimestamp());
        assertEquals(packet.getSsrc(), parsed.getSsrc());
        assertArrayEquals(packet.getPayload(), parsed.getPayload());
    }

    @Test
    @DisplayName("the sample conversion is little-endian and lossless")
    void sampleConversionIsLittleEndian() {
        short[] samples = {0, 1, -1, 258, -258, Short.MAX_VALUE, Short.MIN_VALUE};
        byte[] bytes = JavaSoundVoiceBridge.samplesToBytes(samples);

        // 258 == 0x0102 -> LE bytes [0x02, 0x01]
        assertEquals(0x02, bytes[6] & 0xff);
        assertEquals(0x01, bytes[7] & 0xff);
        assertArrayEquals(samples,
                JavaSoundVoiceBridge.bytesToSamples(bytes, bytes.length));
    }
}
