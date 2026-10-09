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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Random;
import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link P2pCrypto}, the transport's cryptographic seam.
 * They cover the whole contract with no display and no network: X25519 key
 * generation (including a deterministic seeded keypair and a matching
 * Diffie-Hellman secret in both directions), public/private key DER round-trips,
 * the SHA-256 fingerprint and its tolerant comparison, HKDF-SHA256 expansion,
 * the per-direction counter-nonce codec, AES-256-GCM seal/open (with AAD binding
 * and a tamper &rarr; {@link AEADBadTagException}), and the hex/base64 codecs.
 */
class P2pCryptoTest {

    /** A seeded, deterministic SHA1PRNG so keypairs and fingerprints are stable. */
    private static SecureRandom seeded(String seed) throws GeneralSecurityException {
        SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
        sr.setSeed(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return sr;
    }

    // ------------------------------------------------------------------
    // Random material
    // ------------------------------------------------------------------

    @Test
    @DisplayName("randomBytes honours length and the injected source")
    void randomBytesLength() {
        assertEquals(0, P2pCrypto.randomBytes(0, new Random(1)).length);
        assertEquals(0, P2pCrypto.randomBytes(-5, new Random(1)).length);
        assertEquals(16, P2pCrypto.randomBytes(16, new Random(1)).length);
        // A null source yields zeros rather than throwing.
        assertArrayEquals(new byte[4], P2pCrypto.randomBytes(4, null));
        // The same seed gives the same bytes (deterministic for tests).
        assertArrayEquals(P2pCrypto.randomBytes(8, new Random(42)),
                P2pCrypto.randomBytes(8, new Random(42)));
        assertEquals(P2pCrypto.NONCE_BYTES, P2pCrypto.newNonce().length);
    }

    // ------------------------------------------------------------------
    // X25519 keys and Diffie-Hellman
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keypairs are X25519 and a seeded generator is deterministic")
    void keyPairGeneration() throws Exception {
        KeyPair a = P2pCrypto.generateKeyPair();
        assertNotNull(a.getPrivate());
        assertNotNull(a.getPublic());
        assertEquals("X.509", a.getPublic().getFormat());
        assertEquals("PKCS#8", a.getPrivate().getFormat());

        KeyPair b1 = P2pCrypto.generateKeyPair(seeded("identity-seed"));
        KeyPair b2 = P2pCrypto.generateKeyPair(seeded("identity-seed"));
        assertArrayEquals(b1.getPrivate().getEncoded(), b2.getPrivate().getEncoded(),
                "the same seed must give the same static keypair");

        // A null source falls back to a fresh SecureRandom rather than throwing.
        assertNotNull(P2pCrypto.generateKeyPair(null));
    }

    @Test
    @DisplayName("both sides of an X25519 agreement derive the same 32-byte secret")
    void diffieHellmanAgreement() throws Exception {
        KeyPair a = P2pCrypto.generateKeyPair();
        KeyPair b = P2pCrypto.generateKeyPair();
        byte[] sa = P2pCrypto.agreement(a.getPrivate(), b.getPublic());
        byte[] sb = P2pCrypto.agreement(b.getPrivate(), a.getPublic());
        assertEquals(P2pCrypto.HASH_BYTES, sa.length);
        assertArrayEquals(sa, sb, "ECDH must be symmetric");
        // A third party derives a different secret.
        KeyPair c = P2pCrypto.generateKeyPair();
        assertNotEquals(0, java.util.Arrays.compare(sa,
                P2pCrypto.agreement(c.getPrivate(), b.getPublic())));
    }

    @Test
    @DisplayName("public and private keys survive a DER round-trip")
    void keyEncodingRoundTrip() throws Exception {
        KeyPair kp = P2pCrypto.generateKeyPair();
        byte[] pubEnc = P2pCrypto.encodePublicKey(kp.getPublic());
        byte[] privEnc = P2pCrypto.encodePrivateKey(kp.getPrivate());
        assertTrue(pubEnc.length > 0);
        assertTrue(privEnc.length > 0);

        assertArrayEquals(pubEnc,
                P2pCrypto.encodePublicKey(P2pCrypto.decodePublicKey(pubEnc)));
        assertArrayEquals(privEnc,
                P2pCrypto.encodePrivateKey(P2pCrypto.decodePrivateKey(privEnc)));

        // A decoded public key still agrees with the original private key.
        assertArrayEquals(P2pCrypto.agreement(kp.getPrivate(), P2pCrypto.decodePublicKey(pubEnc)),
                P2pCrypto.agreement(P2pCrypto.decodePrivateKey(privEnc), kp.getPublic()));

        assertArrayEquals(new byte[0], P2pCrypto.encodePublicKey(null));
        assertArrayEquals(new byte[0], P2pCrypto.encodePrivateKey(null));
        assertThrows(GeneralSecurityException.class,
                () -> P2pCrypto.decodePublicKey(new byte[]{1, 2, 3}));
        assertThrows(GeneralSecurityException.class,
                () -> P2pCrypto.decodePrivateKey(new byte[]{1, 2, 3}));
    }

    // ------------------------------------------------------------------
    // Hashing and fingerprinting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sha256 produces a stable 32-byte digest")
    void sha256Digest() throws Exception {
        assertEquals(P2pCrypto.HASH_BYTES, P2pCrypto.sha256("abc".getBytes()).length);
        assertArrayEquals(P2pCrypto.sha256("abc".getBytes()),
                P2pCrypto.sha256("abc".getBytes()));
        assertArrayEquals(P2pCrypto.sha256(new byte[0]), P2pCrypto.sha256(null));
        assertNotEquals(0, java.util.Arrays.compare(P2pCrypto.sha256("a".getBytes()),
                P2pCrypto.sha256("b".getBytes())));
    }

    @Test
    @DisplayName("a fingerprint is a stable colon-grouped SHA-256 of the public key")
    void fingerprintIsStable() throws Exception {
        KeyPair kp = P2pCrypto.generateKeyPair(seeded("fp-seed"));
        String fp = P2pCrypto.fingerprint(kp.getPublic());
        assertEquals(fp, P2pCrypto.fingerprint(kp.getPublic()));
        // 32 bytes -> 32 hex pairs joined by 31 colons.
        assertEquals(32 * 3 - 1, fp.length());
        assertEquals(31, fp.chars().filter(c -> c == ':').count());
        assertTrue(fp.matches("[0-9a-f:]+"), "lower-case colon hex: " + fp);
        // A different key has a different fingerprint.
        assertNotEquals(fp, P2pCrypto.fingerprint(P2pCrypto.generateKeyPair().getPublic()));
        assertEquals("", P2pCrypto.fingerprint(null));
    }

    @Test
    @DisplayName("fingerprintOfBytes mirrors fingerprint for the same encoding")
    void fingerprintOfBytes() throws Exception {
        KeyPair kp = P2pCrypto.generateKeyPair();
        assertEquals(P2pCrypto.fingerprint(kp.getPublic()),
                P2pCrypto.fingerprintOfBytes(P2pCrypto.encodePublicKey(kp.getPublic())));
    }

    @Test
    @DisplayName("fingerprintsMatch ignores case and separators but not content")
    void fingerprintComparison() throws Exception {
        String fp = P2pCrypto.fingerprint(P2pCrypto.generateKeyPair().getPublic());
        assertTrue(P2pCrypto.fingerprintsMatch(fp, fp));
        assertTrue(P2pCrypto.fingerprintsMatch(fp, fp.toUpperCase(java.util.Locale.ROOT)));
        assertTrue(P2pCrypto.fingerprintsMatch(fp, fp.replace(":", "")));
        assertTrue(P2pCrypto.fingerprintsMatch(fp, "  " + fp + "  "));
        assertFalse(P2pCrypto.fingerprintsMatch(fp, null));
        assertFalse(P2pCrypto.fingerprintsMatch(null, fp));
        assertFalse(P2pCrypto.fingerprintsMatch("", ""));
        assertFalse(P2pCrypto.fingerprintsMatch(fp,
                P2pCrypto.fingerprint(P2pCrypto.generateKeyPair().getPublic())));
    }

    @Test
    @DisplayName("constantTimeEquals compares by content, null-safe")
    void constantTimeEquals() {
        assertTrue(P2pCrypto.constantTimeEquals(new byte[]{1, 2, 3}, new byte[]{1, 2, 3}));
        assertFalse(P2pCrypto.constantTimeEquals(new byte[]{1, 2, 3}, new byte[]{1, 2, 4}));
        assertFalse(P2pCrypto.constantTimeEquals(new byte[]{1, 2}, new byte[]{1, 2, 3}));
        assertFalse(P2pCrypto.constantTimeEquals(null, new byte[]{1}));
        assertFalse(P2pCrypto.constantTimeEquals(new byte[]{1}, null));
    }

    // ------------------------------------------------------------------
    // HKDF-SHA256
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hmacSha256 is deterministic and 32 bytes")
    void hmac() throws Exception {
        byte[] k = "key".getBytes();
        assertEquals(P2pCrypto.HASH_BYTES, P2pCrypto.hmacSha256(k, "data".getBytes()).length);
        assertArrayEquals(P2pCrypto.hmacSha256(k, "data".getBytes()),
                P2pCrypto.hmacSha256(k, "data".getBytes()));
        assertNotEquals(0, java.util.Arrays.compare(P2pCrypto.hmacSha256(k, "a".getBytes()),
                P2pCrypto.hmacSha256(k, "b".getBytes())));
        // An empty/null key is treated as a zero-filled key, not an error.
        assertEquals(P2pCrypto.HASH_BYTES, P2pCrypto.hmacSha256(new byte[0], "d".getBytes()).length);
        assertEquals(P2pCrypto.HASH_BYTES, P2pCrypto.hmacSha256(k, null).length);
    }

    @Test
    @DisplayName("hkdf expands to the requested length, deterministically")
    void hkdfExpansion() throws Exception {
        byte[] ikm = P2pCrypto.randomBytes(32, new Random(7));
        byte[] salt = "salt".getBytes();
        byte[] info = "p2p-send".getBytes();

        byte[] out = P2pCrypto.hkdf(ikm, salt, info, 64);
        assertEquals(64, out.length);
        assertArrayEquals(out, P2pCrypto.hkdf(ikm, salt, info, 64),
                "HKDF is deterministic for the same inputs");

        // Different info yields independent keys (the basis of key separation).
        byte[] other = P2pCrypto.hkdf(ikm, salt, "p2p-recv".getBytes(), 64);
        assertNotEquals(0, java.util.Arrays.compare(out, other));

        // A prefix of a longer expansion matches the shorter expansion's start
        // only up to the first block boundary; here we assert 32 vs 64 differ in
        // total length but share the first 32 bytes (RFC 5869 expand structure).
        byte[] out32 = P2pCrypto.hkdf(ikm, salt, info, 32);
        assertArrayEquals(java.util.Arrays.copyOf(out, 32), out32);

        // Null salt/info are tolerated; multi-block expansion beyond 32 works.
        assertEquals(100, P2pCrypto.hkdf(ikm, null, null, 100).length);
        // Out-of-range lengths are rejected.
        assertThrows(GeneralSecurityException.class, () -> P2pCrypto.hkdf(ikm, salt, info, 0));
        assertThrows(GeneralSecurityException.class,
                () -> P2pCrypto.hkdf(ikm, salt, info, 255 * 32 + 1));
    }

    @Test
    @DisplayName("aesKey wraps raw material as an AES key")
    void aesKeyWrapping() {
        byte[] material = P2pCrypto.randomBytes(P2pCrypto.KEY_BYTES, new Random(3));
        SecretKey key = P2pCrypto.aesKey(material);
        assertEquals("AES", key.getAlgorithm());
        assertArrayEquals(material, key.getEncoded());
        assertEquals(P2pCrypto.KEY_BYTES, key.getEncoded().length);
    }

    // ------------------------------------------------------------------
    // Counter-nonce codec
    // ------------------------------------------------------------------

    @Test
    @DisplayName("counterNonce is 12 bytes, monotonic and round-trips")
    void counterNonceCodec() {
        assertEquals(P2pCrypto.NONCE_BYTES, P2pCrypto.counterNonce(0).length);
        // Counter 0 is all zeros; counter 1 sets the last byte.
        assertArrayEquals(new byte[12], P2pCrypto.counterNonce(0));
        byte[] one = P2pCrypto.counterNonce(1);
        assertEquals(1, one[11]);
        assertEquals(0, one[0]);
        // Distinct counters give distinct nonces (no reuse under a key).
        assertNotEquals(0, java.util.Arrays.compare(P2pCrypto.counterNonce(1),
                P2pCrypto.counterNonce(2)));
        // Round-trip, including a large counter.
        assertEquals(0, P2pCrypto.readCounter(P2pCrypto.counterNonce(0)));
        assertEquals(1, P2pCrypto.readCounter(P2pCrypto.counterNonce(1)));
        long big = 0x123456789ABCDEFL;
        assertEquals(big, P2pCrypto.readCounter(P2pCrypto.counterNonce(big)));
        // A malformed nonce reads back as -1 rather than throwing.
        assertEquals(-1, P2pCrypto.readCounter(null));
        assertEquals(-1, P2pCrypto.readCounter(new byte[8]));
    }

    // ------------------------------------------------------------------
    // AES-256-GCM seal / open
    // ------------------------------------------------------------------

    @Test
    @DisplayName("seal/open round-trips plaintext under a counter nonce")
    void aeadRoundTrip() throws Exception {
        SecretKey key = P2pCrypto.aesKey(P2pCrypto.randomBytes(P2pCrypto.KEY_BYTES, new Random(9)));
        byte[] nonce = P2pCrypto.counterNonce(1);
        byte[] plaintext = "the quick brown fox".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] ct = P2pCrypto.seal(plaintext, key, nonce);
        // Ciphertext carries the 128-bit tag.
        assertEquals(plaintext.length + P2pCrypto.TAG_BITS / 8, ct.length);
        assertArrayEquals(plaintext, P2pCrypto.open(ct, key, nonce));
        // Null plaintext seals to just a tag and opens to empty.
        assertEquals(0, P2pCrypto.open(P2pCrypto.seal(null, key, P2pCrypto.counterNonce(2)),
                key, P2pCrypto.counterNonce(2)).length);
    }

    @Test
    @DisplayName("a tampered ciphertext fails the GCM tag check")
    void aeadDetectsTampering() throws Exception {
        SecretKey key = P2pCrypto.aesKey(P2pCrypto.randomBytes(P2pCrypto.KEY_BYTES, new Random(11)));
        byte[] nonce = P2pCrypto.counterNonce(5);
        byte[] ct = P2pCrypto.seal("secret payload".getBytes(), key, nonce);
        ct[0] ^= 0x01;
        assertThrows(AEADBadTagException.class, () -> P2pCrypto.open(ct, key, nonce));
    }

    @Test
    @DisplayName("the wrong key or nonce fails to open")
    void aeadWrongKeyOrNonce() throws Exception {
        SecretKey key = P2pCrypto.aesKey(P2pCrypto.randomBytes(P2pCrypto.KEY_BYTES, new Random(13)));
        SecretKey other = P2pCrypto.aesKey(P2pCrypto.randomBytes(P2pCrypto.KEY_BYTES, new Random(14)));
        byte[] nonce = P2pCrypto.counterNonce(6);
        byte[] ct = P2pCrypto.seal("payload".getBytes(), key, nonce);
        assertThrows(GeneralSecurityException.class, () -> P2pCrypto.open(ct, other, nonce));
        assertThrows(GeneralSecurityException.class,
                () -> P2pCrypto.open(ct, key, P2pCrypto.counterNonce(7)));
    }

    @Test
    @DisplayName("AAD is bound into the tag; a mismatched AAD fails to open")
    void aadBinding() throws Exception {
        SecretKey key = P2pCrypto.aesKey(P2pCrypto.randomBytes(P2pCrypto.KEY_BYTES, new Random(15)));
        byte[] nonce = P2pCrypto.counterNonce(8);
        byte[] body = "frame-body".getBytes();
        byte[] ct = P2pCrypto.seal(body, key, nonce, new byte[]{1});
        // Same AAD opens.
        assertArrayEquals(body, P2pCrypto.open(ct, key, nonce, new byte[]{1}));
        // A different AAD (e.g. replaying CONTROL as DATA) fails the tag.
        assertThrows(AEADBadTagException.class, () -> P2pCrypto.open(ct, key, nonce, new byte[]{2}));
        // A null/empty AAD on open also fails, since sealing bound a real one.
        assertThrows(AEADBadTagException.class, () -> P2pCrypto.open(ct, key, nonce, null));
        // Sealing with no AAD then opening with none round-trips.
        byte[] ct2 = P2pCrypto.seal(body, key, P2pCrypto.counterNonce(9), null);
        assertArrayEquals(body, P2pCrypto.open(ct2, key, P2pCrypto.counterNonce(9), null));
    }

    // ------------------------------------------------------------------
    // Hex / base64 codecs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hex and base64 codecs round-trip and are null-safe")
    void codecs() {
        byte[] data = new byte[]{0x00, 0x0F, (byte) 0xAB, (byte) 0xFF, 0x10};
        String hex = P2pCrypto.toHex(data);
        assertEquals("000fabff10", hex);
        assertArrayEquals(data, P2pCrypto.fromHex(hex));
        // fromHex tolerates separators and upper-case, and a null input.
        assertArrayEquals(data, P2pCrypto.fromHex(hex.toUpperCase(java.util.Locale.ROOT)));
        assertArrayEquals(new byte[]{(byte) 0xAB}, P2pCrypto.fromHex("ab"));
        assertArrayEquals(new byte[0], P2pCrypto.fromHex(null));
        assertEquals("", P2pCrypto.toHex(null));

        String b64 = P2pCrypto.toBase64(data);
        assertArrayEquals(data, P2pCrypto.fromBase64(b64));
        assertEquals("", P2pCrypto.toBase64(null));
        assertArrayEquals(new byte[0], P2pCrypto.fromBase64(null));
        assertArrayEquals(new byte[0], P2pCrypto.fromBase64(""));
        // Malformed base64 decodes defensively to empty rather than throwing.
        assertArrayEquals(new byte[0], P2pCrypto.fromBase64("!!!not-base64!!!"));
    }
}
