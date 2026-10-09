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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link NoiseXXHandshake}. They drive a real initiator
 * and responder against each other entirely in memory (no sockets) and assert:
 * both sides complete; the derived transport keys cross-match (initiator send ==
 * responder receive and vice versa); the transcript hash is identical on both
 * sides; each side learns the other's true static fingerprint; a man-in-the-middle
 * who substitutes a static key is <em>detected</em> as a fingerprint change (the
 * anchor for TOFU pinning); a tampered message or a mismatched prologue fails the
 * authenticated exchange; and the step ordering guards reject out-of-turn calls.
 */
class NoiseXXHandshakeTest {

    private static final byte[] PROLOGUE = "lg3d-p2p-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Runs the three-message XX exchange between two static identities. */
    private static void runHandshake(NoiseXXHandshake initiator, NoiseXXHandshake responder)
            throws GeneralSecurityException {
        assertFalse(initiator.isComplete());
        assertFalse(responder.isComplete());
        // -> e
        byte[] m1 = initiator.writeMessage(new byte[0]);
        responder.readMessage(m1);
        // <- e, ee, s, es
        byte[] m2 = responder.writeMessage(new byte[0]);
        initiator.readMessage(m2);
        // -> s, se
        byte[] m3 = initiator.writeMessage(new byte[0]);
        responder.readMessage(m3);
        assertTrue(initiator.isComplete());
        assertTrue(responder.isComplete());
    }

    private static NoiseXXHandshake initiator(KeyPair s) throws GeneralSecurityException {
        return new NoiseXXHandshake(NoiseXXHandshake.Role.INITIATOR, s, PROLOGUE, new SecureRandom());
    }

    private static NoiseXXHandshake responder(KeyPair s) throws GeneralSecurityException {
        return new NoiseXXHandshake(NoiseXXHandshake.Role.RESPONDER, s, PROLOGUE, new SecureRandom());
    }

    @Test
    @DisplayName("a full XX exchange completes on both sides")
    void handshakeCompletes() throws Exception {
        KeyPair si = P2pCrypto.generateKeyPair();
        KeyPair sr = P2pCrypto.generateKeyPair();
        NoiseXXHandshake i = initiator(si);
        NoiseXXHandshake r = responder(sr);

        runHandshake(i, r);

        assertEquals(NoiseXXHandshake.MESSAGE_COUNT, i.getMessageIndex());
        assertEquals(NoiseXXHandshake.MESSAGE_COUNT, r.getMessageIndex());
        assertNotNull(i.cipherKeys());
        assertNotNull(r.cipherKeys());
    }

    @Test
    @DisplayName("both sides derive cross-matching, direction-separated keys")
    void keysCrossMatch() throws Exception {
        NoiseXXHandshake i = initiator(P2pCrypto.generateKeyPair());
        NoiseXXHandshake r = responder(P2pCrypto.generateKeyPair());
        runHandshake(i, r);

        NoiseXXHandshake.CipherKeys ki = i.cipherKeys();
        NoiseXXHandshake.CipherKeys kr = r.cipherKeys();
        // Initiator's send key is the responder's receive key, and vice versa.
        assertArrayEquals(ki.sendKey().getEncoded(), kr.recvKey().getEncoded());
        assertArrayEquals(ki.recvKey().getEncoded(), kr.sendKey().getEncoded());
        // The two directions use different keys (a single key would be a flaw).
        assertNotEquals(0, java.util.Arrays.compare(ki.sendKey().getEncoded(),
                ki.recvKey().getEncoded()));
        // The transcript hash is identical on both sides.
        assertArrayEquals(ki.handshakeHash(), kr.handshakeHash());
        assertEquals(P2pCrypto.HASH_BYTES, ki.handshakeHash().length);
    }

    @Test
    @DisplayName("each side learns the other's true static fingerprint")
    void mutualAuthentication() throws Exception {
        KeyPair si = P2pCrypto.generateKeyPair();
        KeyPair sr = P2pCrypto.generateKeyPair();
        NoiseXXHandshake i = initiator(si);
        NoiseXXHandshake r = responder(sr);

        // Before message 2 the initiator does not yet know the responder's key.
        assertNull(i.getRemoteStatic());
        assertNull(i.getRemoteFingerprint());

        runHandshake(i, r);

        assertEquals(P2pCrypto.fingerprint(sr.getPublic()), i.getRemoteFingerprint());
        assertEquals(P2pCrypto.fingerprint(si.getPublic()), r.getRemoteFingerprint());
        assertEquals(P2pCrypto.fingerprint(si.getPublic()), i.getLocalFingerprint());
        assertEquals(P2pCrypto.fingerprint(sr.getPublic()), r.getLocalFingerprint());
        // The CipherKeys carry the same fingerprint.
        assertEquals(i.getRemoteFingerprint(), i.cipherKeys().remoteFingerprint());
        assertNotNull(i.getRemoteStatic());
    }

    @Test
    @DisplayName("a man-in-the-middle static key shows up as a fingerprint change")
    void manInTheMiddleIsDetected() throws Exception {
        KeyPair si = P2pCrypto.generateKeyPair();
        KeyPair expectedResponder = P2pCrypto.generateKeyPair();
        // The attacker terminates the handshake with its own identity instead of
        // relaying the real responder's. XX cannot stop this on its own; it makes
        // it *visible* - the initiator pins a different fingerprint.
        KeyPair impostor = P2pCrypto.generateKeyPair();

        NoiseXXHandshake i = initiator(si);
        NoiseXXHandshake attacker = responder(impostor);
        runHandshake(i, attacker);

        String pinned = i.getRemoteFingerprint();
        assertEquals(P2pCrypto.fingerprint(impostor.getPublic()), pinned);
        assertNotEquals(P2pCrypto.fingerprint(expectedResponder.getPublic()), pinned,
                "an impostor must not match the expected peer's fingerprint");
        assertFalse(P2pCrypto.fingerprintsMatch(pinned,
                P2pCrypto.fingerprint(expectedResponder.getPublic())));
    }

    @Test
    @DisplayName("a tampered handshake message fails the authenticated exchange")
    void tamperedMessageFails() throws Exception {
        NoiseXXHandshake i = initiator(P2pCrypto.generateKeyPair());
        NoiseXXHandshake r = responder(P2pCrypto.generateKeyPair());

        byte[] m1 = i.writeMessage(new byte[0]);
        r.readMessage(m1);
        byte[] m2 = r.writeMessage(new byte[0]);
        // Flip a byte in the encrypted static-key region of message 2.
        m2[m2.length - 3] ^= 0x01;
        assertThrows(GeneralSecurityException.class, () -> i.readMessage(m2));
        assertFalse(i.isComplete());
    }

    @Test
    @DisplayName("a mismatched prologue makes the two sides disagree and fail")
    void prologueMismatchFails() throws Exception {
        NoiseXXHandshake i = new NoiseXXHandshake(NoiseXXHandshake.Role.INITIATOR,
                P2pCrypto.generateKeyPair(), "context-A".getBytes(), new SecureRandom());
        NoiseXXHandshake r = new NoiseXXHandshake(NoiseXXHandshake.Role.RESPONDER,
                P2pCrypto.generateKeyPair(), "context-B".getBytes(), new SecureRandom());

        // Message 1 has no cipher key, so it passes; the disagreement surfaces when
        // the initiator authenticates message 2 against a different transcript hash.
        byte[] m1 = i.writeMessage(new byte[0]);
        r.readMessage(m1);
        byte[] m2 = r.writeMessage(new byte[0]);
        assertThrows(GeneralSecurityException.class, () -> i.readMessage(m2));
    }

    @Test
    @DisplayName("truncated and malformed messages are rejected")
    void malformedMessagesRejected() throws Exception {
        NoiseXXHandshake r = responder(P2pCrypto.generateKeyPair());
        assertThrows(GeneralSecurityException.class, () -> r.readMessage(new byte[]{0}));
        assertThrows(GeneralSecurityException.class, () -> r.readMessage(null));
        // A length prefix that runs past the end of the buffer.
        assertThrows(GeneralSecurityException.class,
                () -> r.readMessage(new byte[]{(byte) 0xFF, (byte) 0xFF, 1, 2}));
    }

    @Test
    @DisplayName("out-of-turn writes and reads are refused")
    void stepOrderingGuards() throws Exception {
        NoiseXXHandshake i = initiator(P2pCrypto.generateKeyPair());
        NoiseXXHandshake r = responder(P2pCrypto.generateKeyPair());

        // The responder must read first, not write.
        assertThrows(GeneralSecurityException.class, () -> r.writeMessage(new byte[0]));
        // The initiator must write first, not read.
        assertThrows(GeneralSecurityException.class, () -> i.readMessage(new byte[]{0, 0}));

        byte[] m1 = i.writeMessage(new byte[0]);
        // After writing message 1 the initiator must read next, not write again.
        assertThrows(GeneralSecurityException.class, () -> i.writeMessage(new byte[0]));
        r.readMessage(m1);
        // After reading message 1 the responder must write next, not read again.
        assertThrows(GeneralSecurityException.class, () -> r.readMessage(m1));
    }

    @Test
    @DisplayName("reading or writing after completion is refused")
    void noReuseAfterCompletion() throws Exception {
        NoiseXXHandshake i = initiator(P2pCrypto.generateKeyPair());
        NoiseXXHandshake r = responder(P2pCrypto.generateKeyPair());
        runHandshake(i, r);
        assertThrows(GeneralSecurityException.class, () -> i.writeMessage(new byte[0]));
        assertThrows(GeneralSecurityException.class, () -> r.readMessage(new byte[]{0, 0}));
    }

    @Test
    @DisplayName("the constructor rejects a null role or static key")
    void constructorGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new NoiseXXHandshake(null, P2pCrypto.generateKeyPair(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new NoiseXXHandshake(NoiseXXHandshake.Role.INITIATOR, null, null, null));
    }

    @Test
    @DisplayName("a null prologue and null random are tolerated")
    void nullDefaults() throws Exception {
        NoiseXXHandshake i = new NoiseXXHandshake(NoiseXXHandshake.Role.INITIATOR,
                P2pCrypto.generateKeyPair(), null, null);
        NoiseXXHandshake r = new NoiseXXHandshake(NoiseXXHandshake.Role.RESPONDER,
                P2pCrypto.generateKeyPair(), null, null);
        runHandshake(i, r);
        assertArrayEquals(i.cipherKeys().sendKey().getEncoded(),
                r.cipherKeys().recvKey().getEncoded());
        assertEquals(NoiseXXHandshake.Role.INITIATOR, i.getRole());
        assertEquals(NoiseXXHandshake.Role.RESPONDER, r.getRole());
    }

    @Test
    @DisplayName("a handshake payload survives the round-trip on messages 2 and 3")
    void payloadRoundTrip() throws Exception {
        NoiseXXHandshake i = initiator(P2pCrypto.generateKeyPair());
        NoiseXXHandshake r = responder(P2pCrypto.generateKeyPair());

        byte[] m1 = i.writeMessage(new byte[0]);
        assertArrayEquals(new byte[0], r.readMessage(m1));

        byte[] responderPayload = "hello-from-responder".getBytes();
        byte[] m2 = r.writeMessage(responderPayload);
        assertArrayEquals(responderPayload, i.readMessage(m2));

        byte[] initiatorPayload = "hello-from-initiator".getBytes();
        byte[] m3 = i.writeMessage(initiatorPayload);
        assertArrayEquals(initiatorPayload, r.readMessage(m3));

        assertTrue(i.isComplete());
        assertTrue(r.isComplete());
    }
}
