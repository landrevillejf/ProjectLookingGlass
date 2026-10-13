/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): one RTP datagram, RFC 3550 style.
 *
 * The 2006 voice path shipped G.711 audio over RTP, but inside JMF —
 * javax.media's RTPSession/Transform classes, unrecoverable on JDK 21 (the
 * M7d replacement decision is "JMF audio -> Java Sound + G.711 + RFC 3550 in
 * tree"). The wire format itself is fixed by the RFC, not by JMF, so the port
 * owns a plain value class for the 12-byte header here, headless, where CI can
 * prove the bit layout against the spec. The lg3d-apps JavaSoundVoiceBridge
 * sends and receives these through plain DatagramSocket sockets; no audio
 * class is needed to pack or parse a header.
 *
 * Deliberate limits, all preservation rather than invention: only version 2
 * (the only version the 2006 stack emitted or accepted) parses; the optional
 * header extension and padding are preserved on parse and can be authored,
 * but nothing interprets them; a CSRC list is written only for the zero-count
 * case the voice path uses. RTCP, SRTP and DTLS are not part of the 2006
 * contract and are not ported.
 */
package org.jdesktop.wonderland.modules.softphone;

import java.net.DatagramPacket;

/** An immutable RTP packet: the RFC 3550 fixed header plus an opaque payload. */
public final class RtpPacket {

    /** The only protocol version the port writes or accepts. */
    public static final int VERSION = 2;
    /** Fixed-header length in bytes when the CSRC count is zero. */
    public static final int HEADER_LENGTH = 12;

    private final boolean padding;
    private final boolean extension;
    private final int marker;
    private final int payloadType;
    private final int sequenceNumber;
    private final long timestamp;
    private final long ssrc;
    private final byte[] payload;

    /**
     * A packet with no CSRC list — the only shape the 2006 p2p voice path
     * produced or accepted (CSRC lists belong to mixers, which parse() also
     * refuses). Numeric fields keep their RFC 3550 wire widths: the marker
     * is the single M bit of octet 1 (the audio/video profile uses it to
     * mark a talk-spurt start), payloadType its 7 bits (0..127), sequence
     * 0..65535, and the timestamp / SSRC full 32-bit unsigned ranges.
     */
    public RtpPacket(boolean padding, boolean extension, int marker,
                     int payloadType, int sequenceNumber, long timestamp,
                     long ssrc, byte[] payload) {
        if (marker < 0 || marker > 1) {
            throw new IllegalArgumentException("marker out of 1-bit range: " + marker);
        }
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("payload type out of range: " + payloadType);
        }
        if (sequenceNumber < 0 || sequenceNumber > 0xffff) {
            throw new IllegalArgumentException("sequence number out of 16-bit range: "
                    + sequenceNumber);
        }
        if (timestamp < 0 || timestamp > 0xffffffffL) {
            throw new IllegalArgumentException("timestamp out of 32-bit range: " + timestamp);
        }
        if (ssrc < 0 || ssrc > 0xffffffffL) {
            throw new IllegalArgumentException("SSRC out of 32-bit range: " + ssrc);
        }
        this.padding = padding;
        this.extension = extension;
        this.marker = marker;
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.ssrc = ssrc;
        // defensive copy: the packet is a value, the sender may reuse its buffer
        this.payload = payload.clone();
    }

    public boolean isPadding() {
        return padding;
    }

    public boolean isExtension() {
        return extension;
    }

    public int getMarker() {
        return marker;
    }

    public int getPayloadType() {
        return payloadType;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getSsrc() {
        return ssrc;
    }

    /** A copy of the payload, so callers cannot mutate a sent packet. */
    public byte[] getPayload() {
        return payload.clone();
    }

    /**
     * The next sequence number, wrapping at 16 bits. RTP sequence numbers
     * live modulo 2^16; this is the one arithmetic every sender and every
     * jitter buffer repeats.
     */
    public RtpPacket nextSequence() {
        return withSequence((sequenceNumber + 1) & 0xffff);
    }

    /** The same packet fields under a different sequence number. */
    public RtpPacket withSequence(int newSequenceNumber) {
        return new RtpPacket(padding, extension, marker, payloadType,
                newSequenceNumber, timestamp, ssrc, payload);
    }

    /**
     * True when {@code a} precedes {@code b} on the modulo-2^16 sequence
     * circle, using the RFC 3550 half-circle rule: within a window of 2^15
     * the smaller number is earlier. Comparing raw ints would call sequence
     * 65535 later than 1 after one wrap; this does not.
     */
    public static boolean seqBefore(int a, int b) {
        int delta = (b - a) & 0xffff;
        return delta != 0 && delta < 0x8000;
    }

    /** Serialise the fixed header (no CSRC) plus the payload, in the exact
     *  RFC 3550 octet layout: V|P|X|CC in octet 0, M|PT in octet 1. When the
     *  padding flag is set a single pad octet counting 1 is appended, the
     *  minimal form parse() reads back. */
    public byte[] toByteArray() {
        int padOctets = padding ? 1 : 0;
        byte[] out = new byte[HEADER_LENGTH + payload.length + padOctets];
        out[0] = (byte) ((VERSION << 6) | (padding ? 0x20 : 0)
                | (extension ? 0x10 : 0));
        out[1] = (byte) ((marker << 7) | payloadType);
        out[2] = (byte) (sequenceNumber >>> 8);
        out[3] = (byte) sequenceNumber;
        out[4] = (byte) (timestamp >>> 24);
        out[5] = (byte) (timestamp >>> 16);
        out[6] = (byte) (timestamp >>> 8);
        out[7] = (byte) timestamp;
        out[8] = (byte) (ssrc >>> 24);
        out[9] = (byte) (ssrc >>> 16);
        out[10] = (byte) (ssrc >>> 8);
        out[11] = (byte) ssrc;
        System.arraycopy(payload, 0, out, HEADER_LENGTH, payload.length);
        if (padding) {
            out[out.length - 1] = 1;   // one pad octet, self-counting
        }
        return out;
    }

    /**
     * Parse one datagram payload. Rejects (with a descriptive
     * IllegalArgumentException, which the receiver turns into a dropped
     * packet + problem report) anything that is not version 2, shorter than
     * the fixed header, or whose declared CSRC / padding lengths do not fit
     * the datagram.
     */
    public static RtpPacket parse(byte[] wire, int length) {
        if (length < HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "rtp datagram shorter than the fixed header: " + length);
        }
        int first = wire[0] & 0xff;
        if ((first >>> 6) != VERSION) {
            throw new IllegalArgumentException("rtp version " + (first >>> 6)
                    + " is not the only version the port speaks");
        }
        boolean pad = (first & 0x20) != 0;
        boolean ext = (first & 0x10) != 0;
        int csrcCount = first & 0x0f;
        int second = wire[1] & 0xff;
        // RFC 3550: octet 1 is M|PT — the marker is the top bit, the 7-bit
        // payload type below it. The voice path's static G.711 types and a
        // dynamic 96..127 negotiation both decode here; what the port will
        // not do is mis-read the M bit as part of the codec number.
        int marker = second >>> 7;
        int payloadType = second & 0x7f;
        int seq = ((wire[2] & 0xff) << 8) | (wire[3] & 0xff);
        long ts = ((long) (wire[4] & 0xff) << 24) | ((wire[5] & 0xff) << 16)
                | ((wire[6] & 0xff) << 8) | (wire[7] & 0xff);
        long ssrc = ((long) (wire[8] & 0xff) << 24) | ((wire[9] & 0xff) << 16)
                | ((wire[10] & 0xff) << 8) | (wire[11] & 0xff);

        int headerLength = HEADER_LENGTH + 4 * csrcCount;
        if (headerLength > length) {
            throw new IllegalArgumentException("csrc count " + csrcCount
                    + " overruns the datagram");
        }
        int payloadStart = headerLength;
        if (ext) {
            // the extension header is 4 bytes plus a 16-bit-word count of more
            if (payloadStart + 4 > length) {
                throw new IllegalArgumentException("extension header overruns");
            }
            int words = ((wire[payloadStart + 2] & 0xff) << 8)
                    | (wire[payloadStart + 3] & 0xff);
            payloadStart += 4 + 4 * words;
            if (payloadStart > length) {
                throw new IllegalArgumentException("extension body overruns");
            }
        }
        int payloadEnd = length;
        if (pad) {
            int padCount = wire[length - 1] & 0xff;
            if (padCount == 0 || payloadStart + padCount > length) {
                throw new IllegalArgumentException("impossible padding count "
                        + padCount);
            }
            payloadEnd -= padCount;
        }
        byte[] body = new byte[payloadEnd - payloadStart];
        System.arraycopy(wire, payloadStart, body, 0, body.length);
        // CSRC list: preserved is not possible in this immutable shape without
        // a field the voice path never reads, so a non-empty list is refused
        // the same way the 2006 mixer-only client never produced one.
        if (csrcCount > 0) {
            throw new IllegalArgumentException("CSRC lists belong to mixers, "
                    + "which the 2006 p2p client never parsed");
        }
        return new RtpPacket(pad, ext, marker, payloadType, seq, ts, ssrc, body);
    }

    /** Parse directly from a received datagram. */
    public static RtpPacket parse(DatagramPacket packet) {
        return parse(packet.getData(), packet.getLength());
    }

    /** A fresh SSRC: 31 random bits, the RFC's recommendation for a source
     *  identifier that must not collide on the wire. */
    public static long randomSsrc() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong()
                & 0x7fffffffL;
    }

    @Override
    public String toString() {
        return "RTP pt=" + payloadType + " seq=" + sequenceNumber
                + " ts=" + timestamp + " ssrc=" + ssrc
                + " len=" + payload.length
                + (marker == 0 ? "" : " m=" + marker);
    }
}
