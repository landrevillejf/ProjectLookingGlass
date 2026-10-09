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
package org.jdesktop.lg3d.apps.p2p;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The LAN auto-discovery announcement, as a small versioned binary record. A node
 * periodically multicasts one of these so other nodes on the same subnet learn that
 * it exists, what it is called, which fingerprint it presents, and which TCP port to
 * dial. {@link LanDiscovery} does the socket I/O; this class is the pure codec, kept
 * separate so it is trivially unit-testable without binding a socket.
 *
 * <p><strong>Discovery is a hint, not a trust decision.</strong> These packets are
 * sent in the clear and are trivially spoofable - any host on the LAN can announce
 * any nickname and fingerprint. That is acceptable because nothing here is trusted:
 * the packet only suggests a {@code host:port} to connect to, and the peer's real
 * identity is then established cryptographically by the {@link NoiseXXHandshake}
 * (which yields the fingerprint) and pinned by TOFU. Only public, non-secret data
 * is carried: a fingerprint is derived from a public key, and the nickname and port
 * are not secrets. No private key material ever goes on the wire.</p>
 *
 * <p>Wire format (big-endian):
 * <pre>
 *   u8  version      ({@link #VERSION})
 *   u8  type         (0 = ANNOUNCE, 1 = GOODBYE)
 *   i64 epochMs      (sender clock; used for TTL bookkeeping display only)
 *   i32 port         (the sender's TCP listen port)
 *   u16 fpLen        + fpLen bytes UTF-8   (identity fingerprint)
 *   u16 nickLen      + nickLen bytes UTF-8 (display name)
 * </pre>
 * Instances are immutable and safe to share across threads.</p>
 */
public final class DiscoveryPacket {

    /** The wire format version this build speaks; a mismatch is rejected on decode. */
    public static final int VERSION = 1;

    /** The largest packet we will encode or accept, well within one UDP datagram. */
    public static final int MAX_BYTES = 2048;

    /** The minimum length of a well-formed packet (both strings empty). */
    static final int MIN_BYTES = 1 + 1 + 8 + 4 + 2 + 2;

    private static final int TYPE_ANNOUNCE = 0;
    private static final int TYPE_GOODBYE = 1;

    /** Whether a node is announcing itself or withdrawing. */
    public enum Type {
        /** "Here I am" - broadcast periodically while running. */
        ANNOUNCE,
        /** "I'm leaving" - broadcast once on shutdown so peers drop us promptly. */
        GOODBYE
    }

    private final Type type;
    private final String fingerprint;
    private final String nickname;
    private final int port;
    private final long epochMs;

    /**
     * Builds a packet.
     *
     * @param type        ANNOUNCE or GOODBYE (null defaults to ANNOUNCE)
     * @param fingerprint the sender's identity fingerprint (may be null)
     * @param nickname    the sender's display name (may be null)
     * @param port        the sender's TCP listen port
     * @param epochMs     the sender's timestamp
     */
    public DiscoveryPacket(Type type, String fingerprint, String nickname, int port, long epochMs) {
        this.type = (type == null) ? Type.ANNOUNCE : type;
        this.fingerprint = fingerprint;
        this.nickname = nickname;
        this.port = port;
        this.epochMs = epochMs;
    }

    /** An ANNOUNCE stamped with the current time. */
    public static DiscoveryPacket announce(String fingerprint, String nickname, int port) {
        return new DiscoveryPacket(Type.ANNOUNCE, fingerprint, nickname, port,
                System.currentTimeMillis());
    }

    /** A GOODBYE stamped with the current time. */
    public static DiscoveryPacket goodbye(String fingerprint, String nickname, int port) {
        return new DiscoveryPacket(Type.GOODBYE, fingerprint, nickname, port,
                System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Codec
    // ------------------------------------------------------------------

    /**
     * Encodes this packet to its wire form.
     *
     * @return the bytes, never null
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(VERSION);
            out.writeByte(type == Type.GOODBYE ? TYPE_GOODBYE : TYPE_ANNOUNCE);
            out.writeLong(epochMs);
            out.writeInt(port);
            writeString(out, fingerprint);
            writeString(out, nickname);
            out.flush();
            return bos.toByteArray();
        } catch (IOException ex) {
            // A ByteArrayOutputStream/DataOutputStream over memory cannot fail here.
            throw new IllegalStateException("cannot encode a discovery packet", ex);
        }
    }

    /**
     * Decodes a packet from a whole byte array.
     *
     * @param data the datagram bytes
     * @return the packet
     * @throws IOException if the bytes are missing, truncated, oversized or a
     *                     different version
     */
    public static DiscoveryPacket decode(byte[] data) throws IOException {
        return decode(data, 0, (data == null) ? 0 : data.length);
    }

    /**
     * Decodes a packet from a slice of a byte array (as delivered by a
     * {@code DatagramPacket}, which carries an offset and a length).
     *
     * @param data   the buffer
     * @param offset the start of the packet within the buffer
     * @param length the number of packet bytes
     * @return the packet
     * @throws IOException if the slice is truncated, oversized, malformed or a
     *                     different version
     */
    public static DiscoveryPacket decode(byte[] data, int offset, int length) throws IOException {
        if (data == null || offset < 0 || length < 0 || offset + length > data.length) {
            throw new IOException("bad discovery buffer bounds");
        }
        if (length < MIN_BYTES) {
            throw new IOException("discovery packet too short: " + length);
        }
        if (length > MAX_BYTES) {
            throw new IOException("discovery packet too large: " + length);
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, offset, length));
        int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("unsupported discovery version: " + version);
        }
        int typeCode = in.readUnsignedByte();
        Type type = (typeCode == TYPE_GOODBYE) ? Type.GOODBYE : Type.ANNOUNCE;
        long epochMs = in.readLong();
        int port = in.readInt();
        if (port < 0 || port > 0xFFFF) {
            throw new IOException("discovery port out of range: " + port);
        }
        String fingerprint = readString(in);
        String nickname = readString(in);
        return new DiscoveryPacket(type, fingerprint, nickname, port, epochMs);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            bytes = Arrays.copyOf(bytes, 0xFFFF);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes); // throws EOFException (an IOException) if truncated
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Type getType() {
        return type;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getNickname() {
        return nickname;
    }

    public int getPort() {
        return port;
    }

    public long getEpochMs() {
        return epochMs;
    }

    /** @return true if this is a GOODBYE (withdrawal) rather than an ANNOUNCE. */
    public boolean isGoodbye() {
        return type == Type.GOODBYE;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DiscoveryPacket)) {
            return false;
        }
        DiscoveryPacket p = (DiscoveryPacket) other;
        return port == p.port && epochMs == p.epochMs && type == p.type
                && java.util.Objects.equals(fingerprint, p.fingerprint)
                && java.util.Objects.equals(nickname, p.nickname);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(type, fingerprint, nickname, port, epochMs);
    }

    @Override
    public String toString() {
        return "DiscoveryPacket[" + type + " " + nickname + " " + fingerprint + ":" + port + "]";
    }
}
