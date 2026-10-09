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

import java.util.Arrays;

/**
 * One unit of transport traffic: a <em>type</em> plus an opaque <em>payload</em>.
 * A frame is the thing {@link FrameCodec} seals into a length-prefixed,
 * authenticated wire record and opens back on the far side; {@link SecureChannel}
 * moves frames over a socket after the {@link NoiseXXHandshake}.
 *
 * <p>There are exactly two types, matching the plan's wire contract:</p>
 * <ul>
 *   <li>{@link Type#CONTROL} - a {@link P2pMessage} serialised as JSON (chat,
 *       presence, typing, the file-transfer control plane, invites, keep-alive);</li>
 *   <li>{@link Type#DATA} - a binary file chunk (see {@link FileTransferManager}).</li>
 * </ul>
 *
 * <p>The type is bound into the frame's GCM tag as additional authenticated data,
 * so a {@code CONTROL} frame can never be replayed or reinterpreted as a
 * {@code DATA} frame. Instances are immutable; the payload array is defensively
 * copied on the way in and out.</p>
 */
public final class SecureFrame {

    /** The kind of payload a frame carries; also its one-byte wire tag and AAD. */
    public enum Type {
        /** A JSON {@link P2pMessage}. */
        CONTROL((byte) 1),
        /** A binary file chunk. */
        DATA((byte) 2);

        private final byte code;

        Type(byte code) {
            this.code = code;
        }

        /** @return the one-byte wire code for this type. */
        public byte code() {
            return code;
        }

        /**
         * Resolves a wire code back to a type.
         *
         * @param code the one-byte code read from the wire
         * @return the matching type, or null if the code is unknown
         */
        public static Type fromCode(byte code) {
            for (Type t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            return null;
        }
    }

    private final Type type;
    private final byte[] payload;

    /**
     * Builds a frame.
     *
     * @param type    the frame type (never null)
     * @param payload the plaintext payload (may be null, treated as empty);
     *                defensively copied
     */
    public SecureFrame(Type type, byte[] payload) {
        if (type == null) {
            throw new IllegalArgumentException("frame type is required");
        }
        this.type = type;
        this.payload = (payload == null) ? new byte[0] : payload.clone();
    }

    /** Convenience factory for a CONTROL frame carrying a {@link P2pMessage}. */
    public static SecureFrame control(P2pMessage message) {
        return new SecureFrame(Type.CONTROL,
                message == null ? new byte[0] : message.toJsonBytes());
    }

    /** Convenience factory for a DATA frame carrying a raw file chunk. */
    public static SecureFrame data(byte[] chunk) {
        return new SecureFrame(Type.DATA, chunk);
    }

    /** @return the frame type, never null. */
    public Type getType() {
        return type;
    }

    /** @return a copy of the plaintext payload, never null. */
    public byte[] getPayload() {
        return payload.clone();
    }

    /** @return the payload length in bytes (without copying the array). */
    public int getPayloadLength() {
        return payload.length;
    }

    /**
     * Decodes a CONTROL frame's payload as a {@link P2pMessage}.
     *
     * @return the message, or null if this is not a CONTROL frame or the JSON is
     *         malformed
     */
    public P2pMessage toMessage() {
        if (type != Type.CONTROL) {
            return null;
        }
        return P2pMessage.fromJsonBytes(payload);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SecureFrame other)) {
            return false;
        }
        return type == other.type && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "SecureFrame[" + type + ", " + payload.length + " bytes]";
    }
}
