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

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.SecretKey;

/**
 * Turns a {@link SecureFrame} into the transport's length-prefixed, authenticated
 * wire record and back:
 *
 * <pre>
 *   [int32 body-length][1-byte type][AES-256-GCM ciphertext + 128-bit tag]
 * </pre>
 *
 * <p>The 32-bit big-endian length covers everything after itself (the type byte
 * plus the ciphertext), so a reader can {@code readInt()} then read exactly that
 * many bytes - no scanning, no delimiters. The frame type is passed as the GCM
 * <em>additional authenticated data</em>, which cryptographically binds it to the
 * payload: flipping the type byte on the wire makes the tag check fail rather than
 * reclassifying the frame. The per-frame nonce is the caller-supplied counter (see
 * {@link P2pCrypto#counterNonce(long)}), so a nonce is never reused under the
 * session key.</p>
 *
 * <p><strong>Oversize guard.</strong> A malicious or corrupt length prefix could
 * otherwise make the reader allocate gigabytes before any tag is checked.
 * {@link #checkLength(int)} validates the declared body length against
 * {@link #MAX_BODY_BYTES} (a 4&nbsp;MiB plaintext cap plus framing) <em>before</em>
 * the buffer is allocated, and {@link #encode} refuses an oversized local payload.
 * All methods are pure and stateless; {@link SecureChannel} owns the counters and
 * the socket I/O.</p>
 */
public final class FrameCodec {

    /** The maximum plaintext payload a single frame may carry (4 MiB). */
    public static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

    /** The GCM authentication-tag length in bytes (128 bits). */
    public static final int TAG_BYTES = P2pCrypto.TAG_BITS / 8;

    /**
     * The maximum wire body length (type byte + ciphertext + tag) accepted by
     * {@link #checkLength(int)}: the plaintext cap plus the type byte and tag.
     */
    public static final int MAX_BODY_BYTES = MAX_PAYLOAD_BYTES + TAG_BYTES + 1;

    /** The length of the int32 body-length prefix. */
    public static final int LENGTH_PREFIX_BYTES = 4;

    private FrameCodec() {
        // no instances
    }

    /**
     * Seals a frame into a complete wire record ready to write to the socket.
     *
     * @param frame   the frame to encode (never null)
     * @param key     the direction's AES-256-GCM key
     * @param counter the per-direction message counter used as the nonce
     * @return the wire bytes {@code [int32 len][type][ciphertext+tag]}
     * @throws GeneralSecurityException if the payload is oversized or sealing fails
     */
    public static byte[] encode(SecureFrame frame, SecretKey key, long counter)
            throws GeneralSecurityException {
        if (frame == null) {
            throw new IllegalArgumentException("frame is required");
        }
        byte[] payload = frame.getPayload();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new GeneralSecurityException(
                    "frame payload exceeds the 4 MiB cap: " + payload.length);
        }
        byte typeCode = frame.getType().code();
        byte[] aad = new byte[]{typeCode};
        byte[] ciphertext = P2pCrypto.seal(payload, key, P2pCrypto.counterNonce(counter), aad);

        int bodyLength = 1 + ciphertext.length;
        byte[] wire = new byte[LENGTH_PREFIX_BYTES + bodyLength];
        wire[0] = (byte) (bodyLength >>> 24);
        wire[1] = (byte) (bodyLength >>> 16);
        wire[2] = (byte) (bodyLength >>> 8);
        wire[3] = (byte) bodyLength;
        wire[LENGTH_PREFIX_BYTES] = typeCode;
        System.arraycopy(ciphertext, 0, wire, LENGTH_PREFIX_BYTES + 1, ciphertext.length);
        return wire;
    }

    /**
     * Validates a declared body length <em>before</em> the reader allocates its
     * buffer, rejecting anything too small to hold a type+tag or larger than the
     * {@link #MAX_BODY_BYTES} cap.
     *
     * @param bodyLength the int32 length read from the wire
     * @throws GeneralSecurityException if the length is out of the accepted range
     */
    public static void checkLength(int bodyLength) throws GeneralSecurityException {
        if (bodyLength < 1 + TAG_BYTES) {
            throw new GeneralSecurityException("frame body too short: " + bodyLength);
        }
        if (bodyLength > MAX_BODY_BYTES) {
            throw new GeneralSecurityException("frame body exceeds the cap: " + bodyLength);
        }
    }

    /**
     * Opens a wire body (the type byte plus ciphertext, with the length prefix
     * already consumed) back into a {@link SecureFrame}.
     *
     * @param body    the bytes {@code [type][ciphertext+tag]}
     * @param key     the direction's AES-256-GCM key
     * @param counter the per-direction message counter used as the nonce
     * @return the decoded frame, never null
     * @throws GeneralSecurityException if the body is malformed, the type is
     *                                  unknown, or the tag/transcript does not
     *                                  verify (tampering or a desynced counter)
     */
    public static SecureFrame decode(byte[] body, SecretKey key, long counter)
            throws GeneralSecurityException {
        if (body == null || body.length < 1 + TAG_BYTES) {
            throw new GeneralSecurityException("frame body too short");
        }
        byte typeCode = body[0];
        SecureFrame.Type type = SecureFrame.Type.fromCode(typeCode);
        if (type == null) {
            throw new GeneralSecurityException("unknown frame type: " + typeCode);
        }
        byte[] ciphertext = Arrays.copyOfRange(body, 1, body.length);
        byte[] aad = new byte[]{typeCode};
        byte[] plaintext = P2pCrypto.open(ciphertext, key, P2pCrypto.counterNonce(counter), aad);
        if (plaintext.length > MAX_PAYLOAD_BYTES) {
            throw new GeneralSecurityException("decoded payload exceeds the cap");
        }
        return new SecureFrame(type, plaintext);
    }

    /**
     * Reads the int32 big-endian body length from a 4-byte prefix.
     *
     * @param prefix the four length bytes
     * @return the body length
     * @throws GeneralSecurityException if the prefix is not four bytes
     */
    public static int readLengthPrefix(byte[] prefix) throws GeneralSecurityException {
        if (prefix == null || prefix.length != LENGTH_PREFIX_BYTES) {
            throw new GeneralSecurityException("bad length prefix");
        }
        return ((prefix[0] & 0xFF) << 24) | ((prefix[1] & 0xFF) << 16)
                | ((prefix[2] & 0xFF) << 8) | (prefix[3] & 0xFF);
    }
}
