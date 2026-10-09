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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.util.Random;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link FrameCodec} and {@link SecureFrame}: the
 * length-prefixed, authenticated wire record. They assert a CONTROL and a DATA
 * frame survive an encode/decode round-trip, that the type byte is bound into the
 * GCM tag (flipping it fails the decode rather than reclassifying the frame), that
 * a tampered ciphertext or a wrong counter fails, that an unknown type code is
 * rejected, and that the oversize guard refuses a huge declared length before any
 * allocation and refuses an oversized local payload.
 */
class FrameCodecTest {

    private static SecretKey key(int seed) {
        return P2pCrypto.aesKey(P2pCrypto.randomBytes(P2pCrypto.KEY_BYTES, new Random(seed)));
    }

    /** Splits a wire record into its int32 length prefix and the body it covers. */
    private static byte[] bodyOf(byte[] wire) {
        byte[] body = new byte[wire.length - FrameCodec.LENGTH_PREFIX_BYTES];
        System.arraycopy(wire, FrameCodec.LENGTH_PREFIX_BYTES, body, 0, body.length);
        return body;
    }

    /** The 4-byte int32 length prefix of a wire record. */
    private static byte[] prefixOf(byte[] wire) {
        return java.util.Arrays.copyOf(wire, FrameCodec.LENGTH_PREFIX_BYTES);
    }

    @Test
    @DisplayName("a CONTROL frame round-trips through encode/decode")
    void controlRoundTrip() throws Exception {
        SecretKey k = key(1);
        SecureFrame frame = SecureFrame.control(P2pMessage.chat("alice", "hello p2p"));

        byte[] wire = FrameCodec.encode(frame, k, 7);
        // The length prefix must equal the body length.
        int declared = FrameCodec.readLengthPrefix(prefixOf(wire));
        assertEquals(wire.length - FrameCodec.LENGTH_PREFIX_BYTES, declared);
        FrameCodec.checkLength(declared);

        SecureFrame decoded = FrameCodec.decode(bodyOf(wire), k, 7);
        assertEquals(SecureFrame.Type.CONTROL, decoded.getType());
        P2pMessage msg = decoded.toMessage();
        assertNotNull(msg);
        assertEquals(P2pMessage.Kind.CHAT, msg.getKind());
        assertEquals("alice", msg.getFrom());
        assertEquals("hello p2p", msg.getText());
    }

    @Test
    @DisplayName("a DATA frame round-trips arbitrary binary bytes")
    void dataRoundTrip() throws Exception {
        SecretKey k = key(2);
        byte[] chunk = new byte[1024];
        new Random(3).nextBytes(chunk);
        SecureFrame frame = SecureFrame.data(chunk);

        byte[] wire = FrameCodec.encode(frame, k, 0);
        SecureFrame decoded = FrameCodec.decode(bodyOf(wire), k, 0);
        assertEquals(SecureFrame.Type.DATA, decoded.getType());
        assertArrayEquals(chunk, decoded.getPayload());
        // toMessage() on a DATA frame is null, not a parse attempt.
        assertEquals(null, decoded.toMessage());
    }

    @Test
    @DisplayName("the ciphertext carries the 128-bit tag and hides the plaintext")
    void ciphertextIsSealed() throws Exception {
        SecretKey k = key(4);
        byte[] payload = "plain-visible-text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] wire = FrameCodec.encode(SecureFrame.data(payload), k, 1);
        // The raw payload must not appear in the sealed record.
        String wireStr = new String(wire, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(wireStr.indexOf("plain-visible-text") < 0,
                "plaintext leaked into the wire record");
        // Body = 1 type byte + payload + 16-byte tag.
        assertEquals(1 + payload.length + FrameCodec.TAG_BYTES,
                wire.length - FrameCodec.LENGTH_PREFIX_BYTES);
    }

    @Test
    @DisplayName("flipping the type byte fails the AAD-bound tag check")
    void typeByteIsAuthenticated() throws Exception {
        SecretKey k = key(5);
        byte[] wire = FrameCodec.encode(SecureFrame.control(P2pMessage.ping()), k, 3);
        byte[] body = bodyOf(wire);
        body[0] = SecureFrame.Type.DATA.code(); // CONTROL -> DATA
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.decode(body, k, 3));
    }

    @Test
    @DisplayName("a tampered ciphertext or a wrong counter fails to decode")
    void tamperAndCounterMismatch() throws Exception {
        SecretKey k = key(6);
        byte[] wire = FrameCodec.encode(SecureFrame.control(P2pMessage.chat("a", "b")), k, 9);
        byte[] body = bodyOf(wire);
        body[body.length - 1] ^= 0x01;
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.decode(body, k, 9));

        byte[] good = bodyOf(FrameCodec.encode(SecureFrame.control(P2pMessage.chat("a", "b")), k, 9));
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.decode(good, k, 10));
    }

    @Test
    @DisplayName("a wrong key fails to decode")
    void wrongKeyFails() throws Exception {
        byte[] wire = FrameCodec.encode(SecureFrame.control(P2pMessage.ping()), key(7), 1);
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.decode(bodyOf(wire), key(8), 1));
    }

    @Test
    @DisplayName("an unknown frame type code is rejected")
    void unknownTypeRejected() throws Exception {
        SecretKey k = key(9);
        // Hand-build a body with an unknown type byte but a well-formed length.
        byte[] body = new byte[1 + FrameCodec.TAG_BYTES];
        body[0] = (byte) 99;
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.decode(body, k, 0));
        assertEquals(null, SecureFrame.Type.fromCode((byte) 99));
        assertEquals(SecureFrame.Type.CONTROL, SecureFrame.Type.fromCode((byte) 1));
        assertEquals(SecureFrame.Type.DATA, SecureFrame.Type.fromCode((byte) 2));
    }

    @Test
    @DisplayName("a short or null body is rejected")
    void shortBodyRejected() throws Exception {
        SecretKey k = key(10);
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.decode(null, k, 0));
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.decode(new byte[]{1}, k, 0));
        assertThrows(GeneralSecurityException.class,
                () -> FrameCodec.decode(new byte[FrameCodec.TAG_BYTES], k, 0));
    }

    @Test
    @DisplayName("checkLength guards against a tiny or a huge declared length")
    void lengthGuard() throws Exception {
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.checkLength(0));
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.checkLength(FrameCodec.TAG_BYTES));
        assertThrows(GeneralSecurityException.class,
                () -> FrameCodec.checkLength(FrameCodec.MAX_BODY_BYTES + 1));
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.checkLength(Integer.MAX_VALUE));
        // A legitimate small length passes.
        FrameCodec.checkLength(1 + FrameCodec.TAG_BYTES);
        FrameCodec.checkLength(FrameCodec.MAX_BODY_BYTES);
    }

    @Test
    @DisplayName("readLengthPrefix decodes big-endian and rejects a bad prefix")
    void lengthPrefixCodec() throws Exception {
        SecretKey k = key(11);
        byte[] wire = FrameCodec.encode(SecureFrame.control(P2pMessage.ping()), k, 0);
        int declared = FrameCodec.readLengthPrefix(prefixOf(wire));
        assertEquals(wire.length - FrameCodec.LENGTH_PREFIX_BYTES, declared);
        assertThrows(GeneralSecurityException.class, () -> FrameCodec.readLengthPrefix(null));
        assertThrows(GeneralSecurityException.class,
                () -> FrameCodec.readLengthPrefix(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("encoding an oversized payload is refused before sealing")
    void oversizedPayloadRefused() {
        SecretKey k = key(12);
        byte[] huge = new byte[FrameCodec.MAX_PAYLOAD_BYTES + 1];
        assertThrows(GeneralSecurityException.class,
                () -> FrameCodec.encode(SecureFrame.data(huge), k, 0));
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(null, k, 0));
    }

    @Test
    @DisplayName("SecureFrame is immutable and value-equal")
    void frameValueSemantics() {
        byte[] payload = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SecureFrame f = new SecureFrame(SecureFrame.Type.DATA, payload);
        // Mutating the source array must not change the frame.
        payload[0] = 'z';
        assertArrayEquals("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8), f.getPayload());
        // Mutating the returned copy must not change the frame either.
        f.getPayload()[0] = 'q';
        assertEquals('a', f.getPayload()[0]);
        assertEquals(3, f.getPayloadLength());

        SecureFrame same = new SecureFrame(SecureFrame.Type.DATA,
                "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(f, same);
        assertEquals(f.hashCode(), same.hashCode());
        assertNotNull(f.toString());
        assertThrows(IllegalArgumentException.class, () -> new SecureFrame(null, payload));
        // A null payload becomes empty.
        assertEquals(0, new SecureFrame(SecureFrame.Type.CONTROL, null).getPayloadLength());
    }
}
