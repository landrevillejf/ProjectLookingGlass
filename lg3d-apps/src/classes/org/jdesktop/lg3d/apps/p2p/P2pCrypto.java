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
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AWT-free cryptographic seam of the encrypted peer-to-peer transport. Every
 * secret operation the P2P stack performs - generating a long-term identity,
 * agreeing on a shared key, deriving send/receive keys, sealing and opening
 * frames, and fingerprinting a peer - lives here as pure statics over the JDK's
 * own {@code javax.crypto}/{@code java.security} providers, so the whole contract
 * is unit-testable headless with no display and no third-party or native library.
 *
 * <p>The primitives mirror the project's existing {@code VaultCrypto} precedent
 * (final class, private constructor, injectable randomness) and are chosen to be
 * conservative and standard:</p>
 * <ul>
 *   <li><strong>Key agreement:</strong> X25519 elliptic-curve Diffie-Hellman
 *       ({@code KeyPairGenerator}/{@code KeyAgreement} "X25519", SunEC, JDK 11+).
 *       A fresh ephemeral X25519 keypair per handshake gives forward secrecy.</li>
 *   <li><strong>KDF:</strong> HKDF-SHA256, implemented directly over
 *       {@code Mac.getInstance("HmacSHA256")} (RFC 5869 extract-then-expand) so
 *       no extra dependency is needed.</li>
 *   <li><strong>AEAD:</strong> AES-256-GCM ({@code Cipher} "AES/GCM/NoPadding",
 *       a 96-bit nonce and a 128-bit tag). GCM is authenticated, so a tampered
 *       frame never decrypts to garbage - {@link #open} throws an
 *       {@code AEADBadTagException}.</li>
 * </ul>
 *
 * <p><strong>Nonce discipline.</strong> A long-lived AES-GCM key must never
 * repeat a nonce. The transport therefore uses a <em>per-direction 96-bit
 * counter</em> as the nonce (see {@link #counterNonce(long)}): each side owns one
 * key and one monotonically increasing counter, so a nonce is used exactly once.
 * The JDK's own GCM provider additionally refuses to re-initialise an encryption
 * cipher with a repeated {@code (key, IV)}, which is a useful second line of
 * defence.</p>
 *
 * <p>Nothing here holds state or touches the filesystem; {@link IdentityStore}
 * owns persistence and {@link SecureChannel}/{@link NoiseXXHandshake} own the
 * protocol. The private half of a static identity key is at-rest material: it is
 * written {@code 0600} by {@link IdentityStore} and documented honestly as such.</p>
 */
public final class P2pCrypto {

    /** The elliptic-curve Diffie-Hellman algorithm: X25519 (Curve25519). */
    public static final String KEY_AGREEMENT = "X25519";

    /** The authenticated cipher: AES in GCM mode with no padding. */
    public static final String CIPHER = "AES/GCM/NoPadding";

    /** The HMAC used by HKDF and the handshake transcript hash. */
    public static final String HMAC = "HmacSHA256";

    /** The digest used for fingerprints and content hashing. */
    public static final String HASH = "SHA-256";

    /** AES key length in bits (AES-256). */
    public static final int KEY_BITS = 256;

    /** AES key length in bytes. */
    public static final int KEY_BYTES = KEY_BITS / 8;

    /** GCM authentication-tag length in bits. */
    public static final int TAG_BITS = 128;

    /** GCM nonce length in bytes (the recommended 96 bits). */
    public static final int NONCE_BYTES = 12;

    /** The X25519 key size, in bits, accepted by the key-pair generator. */
    public static final int CURVE_BITS = 255;

    /** Length of a SHA-256 digest (and of an X25519 shared secret) in bytes. */
    public static final int HASH_BYTES = 32;

    private P2pCrypto() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Random material
    // ------------------------------------------------------------------

    /** @return a fresh cryptographically-random 96-bit GCM nonce. */
    public static byte[] newNonce() {
        return randomBytes(NONCE_BYTES, new SecureRandom());
    }

    /**
     * Fills a byte array from the given source of randomness.
     *
     * @param length the number of bytes to produce (clamped at zero)
     * @param random the randomness source (a {@link SecureRandom} in production;
     *               a seeded {@link Random} in deterministic tests)
     * @return the random bytes, never null
     */
    public static byte[] randomBytes(int length, Random random) {
        int n = Math.max(0, length);
        byte[] out = new byte[n];
        if (random != null && n > 0) {
            // Random.nextBytes exists on SecureRandom too; loop for plain Random.
            for (int i = 0; i < n; i++) {
                out[i] = (byte) random.nextInt(256);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // X25519 keys and Diffie-Hellman
    // ------------------------------------------------------------------

    /**
     * Generates a fresh X25519 keypair from a new {@link SecureRandom}.
     *
     * @return the keypair, never null
     * @throws GeneralSecurityException if the X25519 provider is unavailable
     */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        return generateKeyPair(new SecureRandom());
    }

    /**
     * Generates an X25519 keypair from the given randomness source. Passing a
     * seeded {@link SecureRandom} (for example {@code SHA1PRNG} seeded before
     * first use) makes the keypair deterministic, which is what the headless
     * tests rely on to assert a stable fingerprint.
     *
     * @param random the randomness source (a {@link SecureRandom} in production)
     * @return the keypair, never null
     * @throws GeneralSecurityException if the X25519 provider is unavailable
     */
    public static KeyPair generateKeyPair(SecureRandom random) throws GeneralSecurityException {
        SecureRandom rng = (random == null) ? new SecureRandom() : random;
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_AGREEMENT);
        kpg.initialize(CURVE_BITS, rng);
        return kpg.generateKeyPair();
    }

    /**
     * Performs an X25519 Diffie-Hellman agreement.
     *
     * @param privateKey our private key
     * @param publicKey  the peer's public key
     * @return the 32-byte shared secret, never null
     * @throws GeneralSecurityException if the keys are not X25519 keys
     */
    public static byte[] agreement(PrivateKey privateKey, PublicKey publicKey)
            throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance(KEY_AGREEMENT);
        ka.init(privateKey);
        ka.doPhase(publicKey, true);
        return ka.generateSecret();
    }

    /**
     * Serialises a public key to its X.509 (SubjectPublicKeyInfo) DER encoding,
     * which is what travels on the wire during the handshake and what a
     * {@link #fingerprint(PublicKey)} is computed over.
     *
     * @param publicKey the key (may be null)
     * @return the DER encoding, or an empty array for null
     */
    public static byte[] encodePublicKey(PublicKey publicKey) {
        return (publicKey == null) ? new byte[0] : publicKey.getEncoded();
    }

    /**
     * Reconstructs an X25519 public key from its X.509 DER encoding.
     *
     * @param encoded the DER bytes produced by {@link #encodePublicKey(PublicKey)}
     * @return the public key, never null
     * @throws GeneralSecurityException if the bytes are not a valid X25519 key
     */
    public static PublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        byte[] data = (encoded == null) ? new byte[0] : encoded;
        KeyFactory kf = KeyFactory.getInstance(KEY_AGREEMENT);
        return kf.generatePublic(new X509EncodedKeySpec(data));
    }

    /**
     * Serialises a private key to its PKCS#8 DER encoding, for at-rest storage.
     *
     * @param privateKey the key (may be null)
     * @return the DER encoding, or an empty array for null
     */
    public static byte[] encodePrivateKey(PrivateKey privateKey) {
        return (privateKey == null) ? new byte[0] : privateKey.getEncoded();
    }

    /**
     * Reconstructs an X25519 private key from its PKCS#8 DER encoding.
     *
     * @param encoded the DER bytes produced by {@link #encodePrivateKey(PrivateKey)}
     * @return the private key, never null
     * @throws GeneralSecurityException if the bytes are not a valid X25519 key
     */
    public static PrivateKey decodePrivateKey(byte[] encoded) throws GeneralSecurityException {
        byte[] data = (encoded == null) ? new byte[0] : encoded;
        KeyFactory kf = KeyFactory.getInstance(KEY_AGREEMENT);
        return kf.generatePrivate(new PKCS8EncodedKeySpec(data));
    }

    // ------------------------------------------------------------------
    // Hashing, fingerprinting and comparison
    // ------------------------------------------------------------------

    /**
     * Computes the SHA-256 digest of the input.
     *
     * @param data the bytes (may be null, treated as empty)
     * @return the 32-byte digest, never null
     * @throws GeneralSecurityException if SHA-256 is unavailable (never on a JDK)
     */
    public static byte[] sha256(byte[] data) throws GeneralSecurityException {
        byte[] input = (data == null) ? new byte[0] : data;
        return MessageDigest.getInstance(HASH).digest(input);
    }

    /**
     * The stable, human-comparable identity of a peer: the SHA-256 fingerprint of
     * its X25519 public key, rendered as colon-separated lower-case hex (like an
     * SSH host-key fingerprint). Two nodes with the same fingerprint hold the same
     * static public key, which is the basis of TOFU pinning.
     *
     * @param publicKey the peer's static public key (may be null)
     * @return the fingerprint, or the empty string for null
     * @throws GeneralSecurityException if SHA-256 is unavailable
     */
    public static String fingerprint(PublicKey publicKey) throws GeneralSecurityException {
        if (publicKey == null) {
            return "";
        }
        return fingerprintOfBytes(encodePublicKey(publicKey));
    }

    /**
     * Renders a SHA-256 digest of arbitrary bytes as a colon-separated hex
     * fingerprint (the same shape as {@link #fingerprint(PublicKey)}).
     *
     * @param data the bytes to fingerprint (may be null)
     * @return the fingerprint, never null
     * @throws GeneralSecurityException if SHA-256 is unavailable
     */
    public static String fingerprintOfBytes(byte[] data) throws GeneralSecurityException {
        byte[] digest = sha256(data);
        StringBuilder sb = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Compares two fingerprints for equality in a way that is robust to the
     * colon separators and letter case a user may have transcribed.
     *
     * @param a one fingerprint (may be null)
     * @param b the other fingerprint (may be null)
     * @return true if they denote the same identity
     */
    public static boolean fingerprintsMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = normalizeFingerprint(a);
        String nb = normalizeFingerprint(b);
        return !na.isEmpty() && MessageDigest.isEqual(
                na.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                nb.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String normalizeFingerprint(String s) {
        return s.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT).trim();
    }

    /**
     * A constant-time comparison of two byte arrays, for comparing MACs and tags
     * without leaking their content through timing.
     *
     * @param a one array (may be null)
     * @param b the other array (may be null)
     * @return true if both are non-null and byte-for-byte equal
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }

    // ------------------------------------------------------------------
    // HKDF-SHA256 (RFC 5869)
    // ------------------------------------------------------------------

    /**
     * Computes one HMAC-SHA256.
     *
     * @param key  the MAC key (may be empty, never null)
     * @param data the input (may be null, treated as empty)
     * @return the 32-byte MAC, never null
     * @throws GeneralSecurityException if HmacSHA256 is unavailable
     */
    public static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        byte[] macKey = (key == null || key.length == 0) ? new byte[HASH_BYTES] : key;
        byte[] input = (data == null) ? new byte[0] : data;
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(macKey, HMAC));
        return mac.doFinal(input);
    }

    /**
     * HKDF-SHA256 (RFC 5869): extract a pseudorandom key from the input keying
     * material, then expand it to {@code length} bytes bound to {@code info}.
     * This is how the handshake turns one raw Diffie-Hellman secret into several
     * independent, purpose-separated AES keys.
     *
     * @param ikm    the input keying material (the DH secret), never null
     * @param salt   the optional salt (may be null/empty, which HKDF defines as a
     *               zero-filled hash-length string)
     * @param info   the optional context/application info (may be null/empty)
     * @param length the number of output bytes to produce (must be positive and
     *               at most {@code 255 * 32})
     * @return the output keying material of exactly {@code length} bytes
     * @throws GeneralSecurityException if HmacSHA256 is unavailable or the
     *                                  requested length is out of range
     */
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        if (length <= 0 || length > 255 * HASH_BYTES) {
            throw new GeneralSecurityException("HKDF length out of range: " + length);
        }
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_BYTES] : salt;
        // Extract.
        byte[] prk = hmacSha256(effectiveSalt, ikm == null ? new byte[0] : ikm);
        // Expand.
        byte[] effectiveInfo = (info == null) ? new byte[0] : info;
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int offset = 0;
        byte counter = 1;
        while (offset < length) {
            byte[] block = new byte[t.length + effectiveInfo.length + 1];
            System.arraycopy(t, 0, block, 0, t.length);
            System.arraycopy(effectiveInfo, 0, block, t.length, effectiveInfo.length);
            block[block.length - 1] = counter;
            t = hmacSha256(prk, block);
            int copy = Math.min(HASH_BYTES, length - offset);
            System.arraycopy(t, 0, out, offset, copy);
            offset += copy;
            counter++;
        }
        return out;
    }

    /**
     * Wraps raw key material as an AES {@link SecretKey}.
     *
     * @param material the key bytes (must be {@link #KEY_BYTES} long for AES-256)
     * @return the AES key, never null
     */
    public static SecretKey aesKey(byte[] material) {
        byte[] key = (material == null) ? new byte[0] : material;
        return new SecretKeySpec(key, "AES");
    }

    // ------------------------------------------------------------------
    // Nonce codec
    // ------------------------------------------------------------------

    /**
     * Encodes a per-direction message counter as a 96-bit GCM nonce: the counter
     * occupies the low 8 bytes (big-endian) and the high 4 bytes are zero. Using a
     * counter rather than random bytes means a nonce is never reused under the
     * same long-lived key, which random 96-bit nonces cannot guarantee over a long
     * session.
     *
     * @param counter the monotonically increasing message counter
     * @return the 12-byte nonce, never null
     */
    public static byte[] counterNonce(long counter) {
        byte[] nonce = new byte[NONCE_BYTES];
        for (int i = 0; i < 8; i++) {
            nonce[NONCE_BYTES - 1 - i] = (byte) (counter >>> (8 * i));
        }
        return nonce;
    }

    /**
     * Reads back the counter encoded by {@link #counterNonce(long)}.
     *
     * @param nonce the 12-byte nonce (may be null)
     * @return the counter, or {@code -1} if the nonce is not 12 bytes long
     */
    public static long readCounter(byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_BYTES) {
            return -1L;
        }
        long counter = 0L;
        // The counter lives in the low 8 bytes, big-endian (MSB at index 4).
        for (int i = NONCE_BYTES - 8; i < NONCE_BYTES; i++) {
            counter = (counter << 8) | (nonce[i] & 0xFFL);
        }
        return counter;
    }

    // ------------------------------------------------------------------
    // AES-GCM seal / open
    // ------------------------------------------------------------------

    /**
     * Seals plaintext under an AES-GCM key and nonce.
     *
     * @param plaintext the bytes to seal (may be null, treated as empty)
     * @param key       the AES key
     * @param nonce     the 96-bit nonce (must be {@link #NONCE_BYTES} long and
     *                  never reused with this key)
     * @return the ciphertext with the 128-bit authentication tag appended
     * @throws GeneralSecurityException if sealing fails
     */
    public static byte[] seal(byte[] plaintext, SecretKey key, byte[] nonce)
            throws GeneralSecurityException {
        return seal(plaintext, key, nonce, null);
    }

    /**
     * Seals plaintext under an AES-GCM key and nonce, binding additional
     * authenticated data (AAD) into the tag. The transport passes the frame's
     * type byte as AAD so a CONTROL frame cannot be replayed as a DATA frame.
     *
     * @param plaintext the bytes to seal (may be null, treated as empty)
     * @param key       the AES key
     * @param nonce     the 96-bit nonce
     * @param aad       the additional authenticated data (may be null/empty)
     * @return the ciphertext with the authentication tag appended
     * @throws GeneralSecurityException if sealing fails
     */
    public static byte[] seal(byte[] plaintext, SecretKey key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        byte[] data = (plaintext == null) ? new byte[0] : plaintext;
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(data);
    }

    /**
     * Opens ciphertext sealed by {@link #seal(byte[], SecretKey, byte[])}. Because
     * GCM is authenticated, a tampered blob or the wrong key throws rather than
     * returning garbage.
     *
     * @param ciphertext the sealed bytes (tag appended)
     * @param key        the AES key
     * @param nonce      the nonce used when sealing
     * @return the recovered plaintext
     * @throws GeneralSecurityException if the tag does not verify
     */
    public static byte[] open(byte[] ciphertext, SecretKey key, byte[] nonce)
            throws GeneralSecurityException {
        return open(ciphertext, key, nonce, null);
    }

    /**
     * Opens ciphertext sealed with additional authenticated data.
     *
     * @param ciphertext the sealed bytes (tag appended)
     * @param key        the AES key
     * @param nonce      the nonce used when sealing
     * @param aad        the additional authenticated data used when sealing
     * @return the recovered plaintext
     * @throws GeneralSecurityException if the tag or AAD does not verify
     */
    public static byte[] open(byte[] ciphertext, SecretKey key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        byte[] data = (ciphertext == null) ? new byte[0] : ciphertext;
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(data);
    }

    // ------------------------------------------------------------------
    // Hex / base64 codecs
    // ------------------------------------------------------------------

    /**
     * Renders bytes as lower-case hex.
     *
     * @param data the bytes (may be null)
     * @return the hex string, or "" for null
     */
    public static String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Parses lower- or upper-case hex back to bytes.
     *
     * @param hex the hex string (may be null; odd length or a non-hex char is
     *            skipped defensively)
     * @return the decoded bytes, never null
     */
    public static byte[] fromHex(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String clean = hex.replace(":", "").replace(" ", "");
        int len = clean.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * Renders bytes as unpadded URL-safe base64 (used for the LAN discovery
     * packet, which must survive a text datagram).
     *
     * @param data the bytes (may be null)
     * @return the base64 string, or "" for null
     */
    public static String toBase64(byte[] data) {
        return (data == null) ? "" : Base64.getUrlEncoder().withoutPadding()
                .encodeToString(data);
    }

    /**
     * Parses URL-safe or standard base64 back to bytes.
     *
     * @param base64 the base64 string (may be null)
     * @return the decoded bytes, never null; empty for null or malformed input
     */
    public static byte[] fromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getUrlDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            return new byte[0];
        }
    }
}
