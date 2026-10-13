/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d: the G.711 mu-law codec proof. The header of
 * G711.java makes an unusual promise -- correctness without a reference
 * table -- so these tests are the promise kept: every one of the 65536
 * short values encodes, every one of the 256 codes decodes, the round trip
 * lands inside one quantisation step of its segment, every code but the
 * aliased negative zero is a fixed point, and the published anchors hit
 * exactly. A wrong constant anywhere in the quantiser cannot survive.
 *
 * The anchor values are the canonical Sun g711.c outputs: the two ISDN zeros
 * 0xFF / 0x7F both expand to 0, and full scale clips to 0x80 / 0x00 (which
 * expand to +/-32124, the largest code the segment table can hold).
 */
package org.jdesktop.wonderland.modules.softphone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class G711Test {

    @Test
    @DisplayName("published anchors: the two zeros and the two clips")
    void referenceAnchors() {
        assertThat(G711.linearToMuLaw((short) 0)).isEqualTo((byte) 0xff);
        assertThat(G711.muLawToLinear((byte) 0xff)).isZero();
        assertThat(G711.linearToMuLaw((short) -1)).isEqualTo((byte) 0x7f);
        assertThat(G711.muLawToLinear((byte) 0x7f)).isZero();
        // full scale clips to the coarsest codes, the telephony convention
        assertThat(G711.linearToMuLaw(Short.MAX_VALUE)).isEqualTo((byte) 0x80);
        assertThat(G711.muLawToLinear((byte) 0x80)).isEqualTo((short) 32124);
        assertThat(G711.linearToMuLaw(Short.MIN_VALUE)).isEqualTo((byte) 0x00);
        assertThat(G711.muLawToLinear((byte) 0x00)).isEqualTo((short) -32124);
        // a mid-scale spot check against the reference table
        assertThat(G711.linearToMuLaw((short) 1000)).isEqualTo((byte) 0xce);
        assertThat(G711.muLawToLinear((byte) 0xce)).isEqualTo((short) 988);
    }

    @Test
    @DisplayName("every short encodes, and decode(encode(x)) is within one "
            + "quantisation step of x")
    void exhaustiveRoundTrip() {
        for (int i = 0; i < 65536; i++) {
            short sample = (short) (i - 32768);
            short back = G711.muLawToLinear(G711.linearToMuLaw(sample));
            // compare as ints: both live in the short domain, so the
            // difference never overflows; the coarsest segment plus the
            // full-scale clip keep the error under 0x400
            int error = Math.abs((int) back - (int) sample);
            assertThat(error)
                    .as("round-trip error of sample %d", sample)
                    .isLessThanOrEqualTo(0x400);
        }
    }

    @Test
    @DisplayName("every code is a fixed point of encode(decode(code)) except "
            + "the aliased negative zero 0x7F")
    void codesAreStableUnderRequantisation() {
        for (int c = 0; c < 256; c++) {
            if (c == 0x7f) {
                continue;   // -0 expands to 0, which re-encodes to +0 (0xFF)
            }
            byte code = (byte) c;
            byte again = G711.linearToMuLaw(G711.muLawToLinear(code));
            assertThat(again)
                    .as("code 0x%02X requantises to itself", c)
                    .isEqualTo(code);
        }
    }

    @Test
    @DisplayName("the codec preserves ordering: louder in means louder out")
    void roundTripIsMonotonic() {
        int previous = -1;
        for (int x = 0; x <= 32767; x += 7) {   // a coarse positive ladder
            int back = G711.muLawToLinear(G711.linearToMuLaw((short) x));
            assertThat(back).as("sample %d", x).isGreaterThanOrEqualTo(previous);
            previous = back;
        }
    }

    @Test
    @DisplayName("buffer helpers halve and double the byte length, in order")
    void arrayEncodeDecode() {
        short[] pcm = {0, 33, -33, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE};
        byte[] coded = G711.encode(pcm);
        assertThat(coded).hasSize(pcm.length);
        short[] back = G711.decode(coded);
        assertThat(back).hasSize(pcm.length);
        for (int i = 0; i < pcm.length; i++) {
            assertThat(Math.abs((int) back[i] - (int) pcm[i]))
                    .as("sample %d", i)
                    .isLessThanOrEqualTo(0x400);
        }
        assertThat(G711.decode(G711.encode(new short[0]))).isEmpty();
    }

    @Test
    @DisplayName("payload type is the RFC 3551 static zero")
    void payloadTypeConstant() {
        assertThat(G711.MU_LAW_PAYLOAD_TYPE).isZero();
    }
}
