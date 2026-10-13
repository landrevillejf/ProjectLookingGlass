/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the G.711 mu-law codec, in pure Java.
 *
 * The 2006 audio path leaned on JMF's G711UCodec (javax.media, unrecoverable
 * -- the M7d replacement decision is "JMF audio -> Java Sound + G.711"). The
 * codec itself is only arithmetic, so the port ships it here, headless, where
 * CI can prove it exhaustively: the encoder is the classic bias-and-segment
 * quantiser of Sun's public-domain g711.c (bias the magnitude by +33 in the
 * 14-bit domain, find the segment whose upper bound first contains it, take
 * the 4-bit mantissa at bit (seg+3), assemble S|EEE|FFFF and XOR with the
 * complement mask 0xFF / 0x7F the wire carries); the decoder inverts the XOR,
 * re-biases ((mantissa<<3)+132) shifted up by the segment, and removes the
 * bias, so the two ISDN zeros 0xFF and 0x7F both expand to 0.
 *
 * The proof is stronger than any table transcription: the test suite encodes
 * EVERY one of the 65536 short values and decodes EVERY one of the 256 code
 * bytes, asserting the round trip lands inside one quantisation step of the
 * input, that every code but the aliased negative-zero is a fixed point of
 * encode-then-decode, and that the published anchors (both zeros, both
 * full-scale clips) hit exactly. A wrong constant cannot survive that, and it
 * runs in milliseconds with no hardware.
 *
 * mu-law (payload type 0) is the codec the 2006 softphone negotiated; A-law
 * (Europe's PT 8) is deliberately not ported because no 2006 code path used
 * it -- adding a second untested curve would be a feature invention, not a
 * preservation.
 */
package org.jdesktop.wonderland.modules.softphone;

/** ITU-T G.711 mu-law compression / expansion (payload type 0). */
public final class G711 {

    /** The wire payload type for G.711 mu-law, per RFC 3551. */
    public static final int MU_LAW_PAYLOAD_TYPE = 0;

    /** Sign bit of the un-complemented code. */
    private static final int SIGN_BIT = 0x80;
    /** Quantisation-field mask. */
    private static final int QUANT_MASK = 0xf;
    /** Segment-field mask and shift of the un-complemented code. */
    private static final int SEG_MASK = 0x70;
    private static final int SEG_SHIFT = 4;
    /** Mu-law bias (33 in the coarse domain), the odd step the standard adds. */
    private static final int BIAS = 0x84;
    /** Number of segments. */
    private static final int NSEGS = 8;

    /** Upper bounds of the eight segments in the biased 14-bit magnitude
     *  domain, exactly the seg_end[] table of Sun's g711.c. */
    private static final int[] SEG_END = {
        0xff, 0x1ff, 0x3ff, 0x7ff, 0xfff, 0x1fff, 0x3fff, 0x7fff,
    };

    private G711() {
    }

    /**
     * Compress one signed 16-bit linear sample to its mu-law code byte.
     * Out-of-range magnitudes clip to the largest code, mirroring the 2006
     * behaviour on a hot line (no wrap, no throw).
     */
    public static byte linearToMuLaw(short sample) {
        int value = sample;
        int mask;
        if (value < 0) {
            value = BIAS - value;   // bias the folded magnitude, negative sign
            mask = 0x7f;
        } else {
            value += BIAS;
            mask = 0xff;
        }
        int seg = 0;
        while (seg < NSEGS && value > SEG_END[seg]) {
            seg++;
        }
        if (seg >= NSEGS) {         // clipped past the top segment
            return (byte) (0x7f ^ mask);
        }
        int code = (seg << SEG_SHIFT) | ((value >> (seg + 3)) & QUANT_MASK);
        return (byte) (code ^ mask);
    }

    /**
     * Expand one mu-law code byte to a signed 16-bit linear sample. The
     * complemented wire byte is inverted first, so 0xFF and 0x7F -- the two
     * zeros ISDN carries -- both expand to 0.
     */
    public static short muLawToLinear(byte code) {
        int u = ~code & 0xff;
        int t = ((u & QUANT_MASK) << 3) + BIAS;
        t <<= (u & SEG_MASK) >> SEG_SHIFT;
        return (short) (((u & SIGN_BIT) != 0) ? BIAS - t : t - BIAS);
    }

    /** Compress a whole buffer; the output is half the byte length. */
    public static byte[] encode(short[] pcm) {
        byte[] out = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = linearToMuLaw(pcm[i]);
        }
        return out;
    }

    /** Expand a whole buffer; the output is double the byte length. */
    public static short[] decode(byte[] ulaw) {
        short[] out = new short[ulaw.length];
        for (int i = 0; i < ulaw.length; i++) {
            out[i] = muLawToLinear(ulaw[i]);
        }
        return out;
    }
}
