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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKey;

/**
 * A Noise-XX-style handshake: three messages that mutually authenticate two
 * long-term X25519 identities and agree a pair of forward-secret,
 * direction-separated AES-256-GCM keys, with no third-party dependency. The
 * pattern is
 *
 * <pre>
 *   -&gt; e
 *   &lt;- e, ee, s, es
 *   -&gt; s, se
 * </pre>
 *
 * which gives <em>mutual</em> authentication (both static keys are exchanged and
 * bound into the transcript) and <em>forward secrecy</em> (the session keys come
 * from fresh ephemeral X25519 keypairs that are discarded afterwards). The
 * transcript hash {@code h} binds every handshake byte, so a man-in-the-middle who
 * substitutes a static key produces a different handshake hash and a different
 * peer fingerprint - the basis of the app's TOFU pinning ({@link TrustDecision}).
 *
 * <p>The class is a <strong>pure state machine</strong>: it turns handshake
 * messages in and out and derives keys, but never touches a socket. The caller
 * ({@link SecureChannel}) writes each {@link #writeMessage(byte[])} result to the
 * wire and feeds each received blob to {@link #readMessage(byte[])}. Because the
 * steps are explicit, the whole handshake is unit-testable headless and the two
 * halves can even be driven against each other in one JVM.</p>
 *
 * <p>Instances are <strong>not thread-safe</strong>; a single connecting thread
 * drives one handshake to completion. Handshake payloads are optional: the first
 * message has no cipher key yet, so per the Noise specification a message-1
 * payload would travel in the clear - the transport therefore exchanges identity
 * and presence in encrypted frames <em>after</em> the handshake and passes empty
 * payloads here.</p>
 *
 * <p>The AEAD, KDF and DH primitives are exactly those of {@link P2pCrypto}; the
 * cipher-key schedule uses a zero nonce because each handshake key is used for a
 * single seal/open, and the running transcript hash is passed as additional
 * authenticated data so every handshake ciphertext is bound to its position in
 * the exchange.</p>
 */
public final class NoiseXXHandshake {

    /** Which side of the exchange this instance plays. */
    public enum Role {
        /** The side that dials and sends the first handshake message. */
        INITIATOR,
        /** The side that accepts and answers the first handshake message. */
        RESPONDER
    }

    /**
     * The Noise protocol name; it seeds the initial handshake hash exactly as the
     * specification prescribes (padded with zeros to the hash length, or hashed
     * if longer), so both sides start from an identical transcript state.
     */
    static final String PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256";

    /** The number of handshake messages in the XX pattern. */
    public static final int MESSAGE_COUNT = 3;

    private static final byte[] ZERO_NONCE = P2pCrypto.counterNonce(0L);

    private final Role role;
    private final KeyPair localStatic;
    private final SecureRandom random;

    private byte[] h;                 // running handshake (transcript) hash
    private byte[] ck;                // chaining key
    private SecretKey tempKey;        // current handshake cipher key (null before first DH)

    private KeyPair localEphemeral;   // e
    private PublicKey remoteEphemeral; // re
    private PublicKey remoteStatic;    // rs

    private int messageIndex;         // next message number (0..MESSAGE_COUNT)
    private boolean complete;

    private CipherKeys keys;

    /**
     * Begins a handshake.
     *
     * @param role        which side to play
     * @param localStatic our long-term static X25519 identity keypair
     * @param prologue    optional context both sides must agree on (may be null);
     *                    it is hashed into the transcript so a mismatch fails the
     *                    handshake
     * @param random      the randomness source for the ephemeral keypair (a
     *                    {@link SecureRandom}; a seeded one makes tests
     *                    deterministic)
     * @throws GeneralSecurityException if the initial hash cannot be computed
     */
    public NoiseXXHandshake(Role role, KeyPair localStatic, byte[] prologue, SecureRandom random)
            throws GeneralSecurityException {
        if (role == null || localStatic == null) {
            throw new IllegalArgumentException("role and localStatic are required");
        }
        this.role = role;
        this.localStatic = localStatic;
        this.random = (random == null) ? new SecureRandom() : random;

        byte[] name = PROTOCOL_NAME.getBytes(StandardCharsets.UTF_8);
        if (name.length <= P2pCrypto.HASH_BYTES) {
            this.h = new byte[P2pCrypto.HASH_BYTES];
            System.arraycopy(name, 0, this.h, 0, name.length);
        } else {
            this.h = P2pCrypto.sha256(name);
        }
        this.ck = this.h.clone();
        mixHash(prologue == null ? new byte[0] : prologue);
    }

    /** The immutable result of a completed handshake. */
    public record CipherKeys(SecretKey sendKey, SecretKey recvKey, byte[] handshakeHash,
                             PublicKey remoteStatic, String remoteFingerprint) {
    }

    // ------------------------------------------------------------------
    // State accessors
    // ------------------------------------------------------------------

    /** @return this instance's role. */
    public Role getRole() {
        return role;
    }

    /** @return true once both directions' keys have been derived. */
    public boolean isComplete() {
        return complete;
    }

    /** @return the number of the next message this side must write or read. */
    public int getMessageIndex() {
        return messageIndex;
    }

    /**
     * The derived keys, available once {@link #isComplete()}.
     *
     * @return the cipher keys, or null before completion
     */
    public CipherKeys cipherKeys() {
        return keys;
    }

    /**
     * The remote peer's static public key, available once it has been received
     * (message 2 for the initiator, message 3 for the responder).
     *
     * @return the remote static key, or null if not yet known
     */
    public PublicKey getRemoteStatic() {
        return remoteStatic;
    }

    /**
     * The SHA-256 fingerprint of the remote static key - the peer identity used
     * for TOFU pinning - available once {@link #getRemoteStatic()} is known.
     *
     * @return the fingerprint, or null if the remote key is not yet known
     * @throws GeneralSecurityException if SHA-256 is unavailable
     */
    public String getRemoteFingerprint() throws GeneralSecurityException {
        return (remoteStatic == null) ? null : P2pCrypto.fingerprint(remoteStatic);
    }

    /** The SHA-256 fingerprint of our own static key. */
    public String getLocalFingerprint() throws GeneralSecurityException {
        return P2pCrypto.fingerprint(localStatic.getPublic());
    }

    // ------------------------------------------------------------------
    // Message exchange
    // ------------------------------------------------------------------

    /**
     * Produces the next outgoing handshake message.
     *
     * @param payload optional application payload to encrypt into this message
     *                (pass an empty array; see the class javadoc on message-1
     *                payloads)
     * @return the message bytes to send to the peer
     * @throws GeneralSecurityException if this side should not write next, the
     *                                  handshake is already complete, or crypto
     *                                  fails
     */
    public byte[] writeMessage(byte[] payload) throws GeneralSecurityException {
        ensureCanWrite();
        byte[] body = (payload == null) ? new byte[0] : payload;
        byte[] message;
        int index = messageIndex;
        if (role == Role.INITIATOR && index == 0) {
            message = writeMessage1(body);
        } else if (role == Role.RESPONDER && index == 1) {
            message = writeMessage2(body);
        } else if (role == Role.INITIATOR && index == 2) {
            message = writeMessage3(body);
        } else {
            throw new GeneralSecurityException("unexpected write at message " + index);
        }
        messageIndex++;
        if (messageIndex == MESSAGE_COUNT) {
            finish();
        }
        return message;
    }

    /**
     * Consumes the next incoming handshake message.
     *
     * @param message the message bytes received from the peer
     * @return the decrypted application payload (empty for the transport's use)
     * @throws GeneralSecurityException if this side should not read next, the
     *                                  message is malformed, the tag/transcript
     *                                  does not verify, or crypto fails
     */
    public byte[] readMessage(byte[] message) throws GeneralSecurityException {
        ensureCanRead();
        if (message == null) {
            throw new GeneralSecurityException("null handshake message");
        }
        Reader r = new Reader(message);
        byte[] payload;
        int index = messageIndex;
        if (role == Role.RESPONDER && index == 0) {
            payload = readMessage1(r);
        } else if (role == Role.INITIATOR && index == 1) {
            payload = readMessage2(r);
        } else if (role == Role.RESPONDER && index == 2) {
            payload = readMessage3(r);
        } else {
            throw new GeneralSecurityException("unexpected read at message " + index);
        }
        messageIndex++;
        if (messageIndex == MESSAGE_COUNT) {
            finish();
        }
        return payload;
    }

    // --- message 1: -> e -------------------------------------------------

    private byte[] writeMessage1(byte[] payload) throws GeneralSecurityException {
        localEphemeral = P2pCrypto.generateKeyPair(random);
        byte[] eBytes = P2pCrypto.encodePublicKey(localEphemeral.getPublic());
        mixHash(eBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, eBytes);
        writeChunk(out, encryptAndHash(payload)); // no key yet -> cleartext, hash-bound
        return out.toByteArray();
    }

    private byte[] readMessage1(Reader r) throws GeneralSecurityException {
        byte[] eBytes = r.readChunk();
        mixHash(eBytes);
        remoteEphemeral = P2pCrypto.decodePublicKey(eBytes);
        return decryptAndHash(r.readChunk());
    }

    // --- message 2: <- e, ee, s, es --------------------------------------

    private byte[] writeMessage2(byte[] payload) throws GeneralSecurityException {
        localEphemeral = P2pCrypto.generateKeyPair(random);
        byte[] eBytes = P2pCrypto.encodePublicKey(localEphemeral.getPublic());
        mixHash(eBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, eBytes);
        // ee: DH(responder_ephemeral, initiator_ephemeral)
        mixKey(P2pCrypto.agreement(localEphemeral.getPrivate(), remoteEphemeral));
        // s: the responder's static key, now encrypted under the ee-derived key.
        writeChunk(out, encryptAndHash(P2pCrypto.encodePublicKey(localStatic.getPublic())));
        // es: DH(responder_static, initiator_ephemeral)
        mixKey(P2pCrypto.agreement(localStatic.getPrivate(), remoteEphemeral));
        writeChunk(out, encryptAndHash(payload));
        return out.toByteArray();
    }

    private byte[] readMessage2(Reader r) throws GeneralSecurityException {
        byte[] eBytes = r.readChunk();
        mixHash(eBytes);
        remoteEphemeral = P2pCrypto.decodePublicKey(eBytes);
        // ee: DH(initiator_ephemeral, responder_ephemeral)
        mixKey(P2pCrypto.agreement(localEphemeral.getPrivate(), remoteEphemeral));
        // s: decrypt the responder's static key.
        byte[] sPlain = decryptAndHash(r.readChunk());
        remoteStatic = P2pCrypto.decodePublicKey(sPlain);
        // es: DH(initiator_ephemeral, responder_static)
        mixKey(P2pCrypto.agreement(localEphemeral.getPrivate(), remoteStatic));
        return decryptAndHash(r.readChunk());
    }

    // --- message 3: -> s, se ---------------------------------------------

    private byte[] writeMessage3(byte[] payload) throws GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // s: the initiator's static key, encrypted under the message-2 key.
        writeChunk(out, encryptAndHash(P2pCrypto.encodePublicKey(localStatic.getPublic())));
        // se: DH(initiator_static, responder_ephemeral)
        mixKey(P2pCrypto.agreement(localStatic.getPrivate(), remoteEphemeral));
        writeChunk(out, encryptAndHash(payload));
        return out.toByteArray();
    }

    private byte[] readMessage3(Reader r) throws GeneralSecurityException {
        byte[] sPlain = decryptAndHash(r.readChunk());
        remoteStatic = P2pCrypto.decodePublicKey(sPlain);
        // se: DH(responder_ephemeral, initiator_static)
        mixKey(P2pCrypto.agreement(localEphemeral.getPrivate(), remoteStatic));
        return decryptAndHash(r.readChunk());
    }

    // ------------------------------------------------------------------
    // Symmetric-state primitives (Noise framework)
    // ------------------------------------------------------------------

    private void finish() throws GeneralSecurityException {
        // Split(): derive the two direction-separated transport keys.
        byte[] out = P2pCrypto.hkdf(ck, new byte[0], null, 2 * P2pCrypto.KEY_BYTES);
        SecretKey k1 = P2pCrypto.aesKey(Arrays.copyOfRange(out, 0, P2pCrypto.KEY_BYTES));
        SecretKey k2 = P2pCrypto.aesKey(
                Arrays.copyOfRange(out, P2pCrypto.KEY_BYTES, 2 * P2pCrypto.KEY_BYTES));
        SecretKey send = (role == Role.INITIATOR) ? k1 : k2;
        SecretKey recv = (role == Role.INITIATOR) ? k2 : k1;
        String remoteFp = (remoteStatic == null) ? null : P2pCrypto.fingerprint(remoteStatic);
        this.keys = new CipherKeys(send, recv, h.clone(), remoteStatic, remoteFp);
        this.complete = true;
        // Discard the ephemeral private key material reference (forward secrecy:
        // it is never reused and becomes eligible for collection immediately).
        this.localEphemeral = null;
        this.tempKey = null;
    }

    private void mixHash(byte[] data) throws GeneralSecurityException {
        byte[] joined = new byte[h.length + data.length];
        System.arraycopy(h, 0, joined, 0, h.length);
        System.arraycopy(data, 0, joined, h.length, data.length);
        h = P2pCrypto.sha256(joined);
    }

    private void mixKey(byte[] inputKeyMaterial) throws GeneralSecurityException {
        byte[] out = P2pCrypto.hkdf(ck, inputKeyMaterial, null, 2 * P2pCrypto.KEY_BYTES);
        ck = Arrays.copyOfRange(out, 0, P2pCrypto.KEY_BYTES);
        tempKey = P2pCrypto.aesKey(Arrays.copyOfRange(out, P2pCrypto.KEY_BYTES,
                2 * P2pCrypto.KEY_BYTES));
    }

    private byte[] encryptAndHash(byte[] plaintext) throws GeneralSecurityException {
        byte[] ciphertext;
        if (tempKey == null) {
            // No key established yet: the data is sent in the clear but still
            // bound into the transcript hash (Noise's EncryptAndHash degenerate case).
            ciphertext = plaintext;
            mixHash(plaintext);
        } else {
            ciphertext = P2pCrypto.seal(plaintext, tempKey, ZERO_NONCE, h);
            mixHash(ciphertext);
        }
        return ciphertext;
    }

    private byte[] decryptAndHash(byte[] ciphertext) throws GeneralSecurityException {
        byte[] plaintext;
        if (tempKey == null) {
            plaintext = ciphertext;
            mixHash(ciphertext);
        } else {
            plaintext = P2pCrypto.open(ciphertext, tempKey, ZERO_NONCE, h);
            mixHash(ciphertext);
        }
        return plaintext;
    }

    private void ensureCanWrite() throws GeneralSecurityException {
        if (complete) {
            throw new GeneralSecurityException("handshake already complete");
        }
        boolean shouldWrite = (role == Role.INITIATOR)
                ? (messageIndex == 0 || messageIndex == 2)
                : (messageIndex == 1);
        if (!shouldWrite) {
            throw new GeneralSecurityException(
                    role + " must not write at message " + messageIndex);
        }
    }

    private void ensureCanRead() throws GeneralSecurityException {
        if (complete) {
            throw new GeneralSecurityException("handshake already complete");
        }
        boolean shouldRead = (role == Role.INITIATOR)
                ? (messageIndex == 1)
                : (messageIndex == 0 || messageIndex == 2);
        if (!shouldRead) {
            throw new GeneralSecurityException(
                    role + " must not read at message " + messageIndex);
        }
    }

    // ------------------------------------------------------------------
    // Length-prefixed chunk framing for handshake messages
    // ------------------------------------------------------------------

    private static void writeChunk(ByteArrayOutputStream out, byte[] data) {
        int len = data.length;
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(data, 0, len);
    }

    /** A minimal cursor over a handshake message that reads length-prefixed chunks. */
    private static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf;
        }

        byte[] readChunk() throws GeneralSecurityException {
            if (pos + 2 > buf.length) {
                throw new GeneralSecurityException("truncated handshake message");
            }
            int len = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 2;
            if (len < 0 || pos + len > buf.length) {
                throw new GeneralSecurityException("truncated handshake chunk");
            }
            byte[] out = Arrays.copyOfRange(buf, pos, pos + len);
            pos += len;
            return out;
        }
    }
}
