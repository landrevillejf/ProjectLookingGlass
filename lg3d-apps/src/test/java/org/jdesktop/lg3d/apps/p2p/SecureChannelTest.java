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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Loopback end-to-end tests for {@link SecureChannel}. Two channels are wired
 * together over an in-JVM {@link ServerSocket} on an ephemeral localhost port (no
 * external network, so CI-safe) and driven through a real Noise-XX handshake:
 * mutual authentication and matching fingerprints, encrypted CONTROL traffic in
 * both directions, intact binary DATA frames (including a ~1&nbsp;MiB chunk that
 * exercises multi-read reassembly), the keep-alive PING/PONG that holds a live
 * link up, dead-link detection against a peer that completes the handshake then
 * goes silent, a mismatched-prologue handshake failure, the visibility of an
 * impostor identity, and clean close on both sides. Every wait is bounded so a
 * regression fails fast instead of hanging the build.
 */
class SecureChannelTest {

    private static final long TIMEOUT_MS = 5_000;
    private static final byte[] PROLOGUE = "lg3d-p2p-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a loopback handshake authenticates both identities")
    void handshakeAuthenticates() throws Exception {
        KeyPair clientKey = P2pCrypto.generateKeyPair();
        KeyPair serverKey = P2pCrypto.generateKeyPair();
        RecordingListener clientL = new RecordingListener();
        RecordingListener serverL = new RecordingListener();

        try (ServerSide server = new ServerSide(serverKey, PROLOGUE, serverL)) {
            SecureChannel client = SecureChannel.connect("localhost", server.getPort(),
                    clientKey, PROLOGUE, clientL, new SecureRandom());
            assertTrue(server.awaitReady(TIMEOUT_MS), "server did not accept: " + server.error);
            assertNotNull(server.channel);

            assertTrue(client.isOpen());
            assertTrue(server.channel.isOpen());
            // Each side sees the other's true fingerprint.
            assertEquals(P2pCrypto.fingerprint(serverKey.getPublic()), client.getRemoteFingerprint());
            assertEquals(P2pCrypto.fingerprint(clientKey.getPublic()), server.channel.getRemoteFingerprint());
            // The transcript hash matches on both sides.
            assertArrayEquals(client.getHandshakeHash(), server.channel.getHandshakeHash());
            assertEquals(NoiseXXHandshake.Role.INITIATOR, client.getRole());
            assertEquals(NoiseXXHandshake.Role.RESPONDER, server.channel.getRole());
            assertNotNull(client.getRemoteAddress());
            assertTrue(client.getLocalPort() > 0);

            client.close();
        }
    }

    @Test
    @DisplayName("CONTROL messages flow both ways and decrypt intact")
    void controlBothWays() throws Exception {
        RecordingListener clientL = new RecordingListener();
        RecordingListener serverL = new RecordingListener();
        try (ServerSide server = new ServerSide(P2pCrypto.generateKeyPair(), PROLOGUE, serverL)) {
            SecureChannel client = SecureChannel.connect("localhost", server.getPort(),
                    P2pCrypto.generateKeyPair(), PROLOGUE, clientL, new SecureRandom());
            assertTrue(server.awaitReady(TIMEOUT_MS));

            client.sendControl(P2pMessage.chat("alice", "hello over p2p"));
            P2pMessage got = serverL.awaitControl(m -> m.getKind() == P2pMessage.Kind.CHAT, TIMEOUT_MS);
            assertNotNull(got, "server received nothing: " + serverL.controls);
            assertEquals("alice", got.getFrom());
            assertEquals("hello over p2p", got.getText());

            server.channel.sendControl(P2pMessage.chat("bob", "hi back"));
            P2pMessage back = clientL.awaitControl(m -> "hi back".equals(m.getText()), TIMEOUT_MS);
            assertNotNull(back, "client received nothing: " + clientL.controls);
            assertEquals("bob", back.getFrom());

            client.close();
        }
    }

    @Test
    @DisplayName("a DATA frame delivers binary bytes intact")
    void dataFrameIntact() throws Exception {
        RecordingListener serverL = new RecordingListener();
        byte[] chunk = new byte[4096];
        new Random(5).nextBytes(chunk);
        try (ServerSide server = new ServerSide(P2pCrypto.generateKeyPair(), PROLOGUE, serverL)) {
            SecureChannel client = SecureChannel.connect("localhost", server.getPort(),
                    P2pCrypto.generateKeyPair(), PROLOGUE, new RecordingListener(), new SecureRandom());
            assertTrue(server.awaitReady(TIMEOUT_MS));

            client.sendData(chunk);
            assertTrue(serverL.firstData.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "no DATA frame arrived");
            assertArrayEquals(chunk, serverL.dataSnapshot().get(0));

            client.close();
        }
    }

    @Test
    @DisplayName("a ~1 MiB DATA frame is reassembled intact across reads")
    void largeDataFrame() throws Exception {
        RecordingListener serverL = new RecordingListener();
        byte[] big = new byte[1024 * 1024];
        new Random(6).nextBytes(big);
        try (ServerSide server = new ServerSide(P2pCrypto.generateKeyPair(), PROLOGUE, serverL)) {
            SecureChannel client = SecureChannel.connect("localhost", server.getPort(),
                    P2pCrypto.generateKeyPair(), PROLOGUE, new RecordingListener(), new SecureRandom());
            assertTrue(server.awaitReady(TIMEOUT_MS));

            client.sendData(big);
            assertTrue(serverL.firstData.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the large DATA frame did not arrive");
            assertArrayEquals(big, serverL.dataSnapshot().get(0));

            client.close();
        }
    }

    @Test
    @DisplayName("the keep-alive PING/PONG holds a live link up")
    void keepAliveHoldsLinkUp() throws Exception {
        RecordingListener clientL = new RecordingListener();
        RecordingListener serverL = new RecordingListener();
        try (ServerSide server = new ServerSide(P2pCrypto.generateKeyPair(), PROLOGUE, serverL)) {
            SecureChannel client = SecureChannel.connect("localhost", server.getPort(),
                    P2pCrypto.generateKeyPair(), PROLOGUE, clientL, new SecureRandom());
            assertTrue(server.awaitReady(TIMEOUT_MS));
            // Ping fast; the peer auto-PONGs, so neither side should drop the link.
            client.setKeepAliveSeconds(1);
            server.channel.setKeepAliveSeconds(1);

            Thread.sleep(2_500);
            assertTrue(client.isOpen(), "a live, pinged link should stay open");
            assertTrue(server.channel.isOpen());
            assertTrue(clientL.closedSnapshot().isEmpty(), "unexpected close: " + clientL.closed);

            client.close();
        }
    }

    @Test
    @DisplayName("a silent peer is dropped as unresponsive")
    void deadLinkDetected() throws Exception {
        KeyPair clientKey = P2pCrypto.generateKeyPair();
        RecordingListener clientL = new RecordingListener();
        // A responder that completes the handshake, then never reads or replies.
        try (SilentResponder silent = new SilentResponder(P2pCrypto.generateKeyPair(), PROLOGUE)) {
            SecureChannel client = SecureChannel.connect("localhost", silent.getPort(),
                    clientKey, PROLOGUE, clientL, new SecureRandom());
            assertTrue(silent.awaitHandshake(TIMEOUT_MS), "silent responder failed: " + silent.error);
            client.setKeepAliveSeconds(1);
            client.setDeadLinkSeconds(1);

            assertTrue(clientL.closedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the silent peer was not dropped in time");
            assertFalse(client.isOpen());
            assertTrue(clientL.closedSnapshot().stream().anyMatch(r -> r.contains("unresponsive")),
                    "expected an unresponsive reason: " + clientL.closed);
        }
    }

    // ------------------------------------------------------------------
    // Failure and teardown paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a mismatched prologue fails the handshake on both sides")
    void prologueMismatchFails() throws Exception {
        byte[] otherPrologue = "different-context".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RecordingListener serverL = new RecordingListener();
        try (ServerSide server = new ServerSide(P2pCrypto.generateKeyPair(), otherPrologue, serverL)) {
            assertThrows(GeneralSecurityException.class, () -> SecureChannel.connect(
                    "localhost", server.getPort(), P2pCrypto.generateKeyPair(), PROLOGUE,
                    new RecordingListener(), new SecureRandom()));
            assertTrue(server.awaitReady(TIMEOUT_MS));
            // The responder side also failed (its message-3 read hit a closed socket).
            assertNotNull(server.error, "the responder should have failed too");
            assertEquals(null, server.channel);
        }
    }

    @Test
    @DisplayName("an impostor identity shows up as a different fingerprint")
    void impostorIsVisible() throws Exception {
        KeyPair clientKey = P2pCrypto.generateKeyPair();
        KeyPair expectedServerKey = P2pCrypto.generateKeyPair();
        KeyPair impostorKey = P2pCrypto.generateKeyPair();
        try (ServerSide server = new ServerSide(impostorKey, PROLOGUE, new RecordingListener())) {
            SecureChannel client = SecureChannel.connect("localhost", server.getPort(),
                    clientKey, PROLOGUE, new RecordingListener(), new SecureRandom());
            assertTrue(server.awaitReady(TIMEOUT_MS));
            // XX cannot stop an impostor, but it makes the substitution visible.
            assertEquals(P2pCrypto.fingerprint(impostorKey.getPublic()), client.getRemoteFingerprint());
            assertNotEquals(P2pCrypto.fingerprint(expectedServerKey.getPublic()),
                    client.getRemoteFingerprint());
            client.close();
        }
    }

    @Test
    @DisplayName("close() tears down both sides and fires onClosed once each")
    void closeTearsDownBothSides() throws Exception {
        RecordingListener clientL = new RecordingListener();
        RecordingListener serverL = new RecordingListener();
        try (ServerSide server = new ServerSide(P2pCrypto.generateKeyPair(), PROLOGUE, serverL)) {
            SecureChannel client = SecureChannel.connect("localhost", server.getPort(),
                    P2pCrypto.generateKeyPair(), PROLOGUE, clientL, new SecureRandom());
            assertTrue(server.awaitReady(TIMEOUT_MS));

            client.close("user left");
            assertTrue(clientL.closedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the closing side did not report onClosed");
            assertFalse(client.isOpen());
            assertEquals(1, clientL.closedSnapshot().size(), "onClosed must fire exactly once");
            assertEquals("user left", clientL.closedSnapshot().get(0));

            assertTrue(serverL.closedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the peer did not observe the close");
            assertFalse(server.channel.isOpen());

            // A second close is a harmless no-op.
            client.close();
            assertEquals(1, clientL.closedSnapshot().size());
        }
    }

    @Test
    @DisplayName("sending after close is silently ignored")
    void sendAfterCloseIsIgnored() throws Exception {
        try (ServerSide server = new ServerSide(P2pCrypto.generateKeyPair(), PROLOGUE,
                new RecordingListener())) {
            SecureChannel client = SecureChannel.connect("localhost", server.getPort(),
                    P2pCrypto.generateKeyPair(), PROLOGUE, new RecordingListener(), new SecureRandom());
            assertTrue(server.awaitReady(TIMEOUT_MS));
            client.close();
            // None of these should throw.
            client.sendControl(P2pMessage.chat("a", "b"));
            client.sendData(new byte[]{1, 2, 3});
            client.sendControl(null);
            client.sendData(null);
            client.sendData(new byte[0]);
            assertFalse(client.isOpen());
        }
    }

    @Test
    @DisplayName("accept() rejects a null socket")
    void acceptRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> SecureChannel.accept(
                null, P2pCrypto.generateKeyPair(), PROLOGUE, null, new SecureRandom()));
    }

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    /** Records a channel's callbacks so the exchange can be asserted on. */
    private static final class RecordingListener implements SecureChannel.Listener {
        final List<P2pMessage> controls = Collections.synchronizedList(new ArrayList<>());
        final List<byte[]> data = Collections.synchronizedList(new ArrayList<>());
        final List<String> closed = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch firstControl = new CountDownLatch(1);
        final CountDownLatch firstData = new CountDownLatch(1);
        final CountDownLatch closedLatch = new CountDownLatch(1);

        List<P2pMessage> controlsSnapshot() {
            synchronized (controls) {
                return new ArrayList<>(controls);
            }
        }

        List<byte[]> dataSnapshot() {
            synchronized (data) {
                return new ArrayList<>(data);
            }
        }

        List<String> closedSnapshot() {
            synchronized (closed) {
                return new ArrayList<>(closed);
            }
        }

        P2pMessage awaitControl(Predicate<P2pMessage> p, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                for (P2pMessage m : controlsSnapshot()) {
                    if (p.test(m)) {
                        return m;
                    }
                }
                Thread.sleep(15);
            }
            return null;
        }

        @Override public void onControl(SecureChannel channel, P2pMessage message) {
            controls.add(message);
            firstControl.countDown();
        }
        @Override public void onData(SecureChannel channel, byte[] payload) {
            data.add(payload);
            firstData.countDown();
        }
        @Override public void onClosed(SecureChannel channel, String reason) {
            closed.add(reason);
            closedLatch.countDown();
        }
        @Override public void onError(SecureChannel channel, String error) {
            errors.add(error);
        }
    }

    /** Accepts one loopback connection and builds the responder channel on a thread. */
    private static final class ServerSide implements AutoCloseable {
        final ServerSocket serverSocket;
        final Thread thread;
        final CountDownLatch done = new CountDownLatch(1);
        volatile SecureChannel channel;
        volatile Throwable error;

        ServerSide(KeyPair staticKey, byte[] prologue, SecureChannel.Listener listener)
                throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            thread = new Thread(() -> {
                try {
                    Socket s = serverSocket.accept();
                    channel = SecureChannel.accept(s, staticKey, prologue, listener, new SecureRandom());
                } catch (Throwable t) {
                    error = t;
                } finally {
                    done.countDown();
                }
            }, "test-p2p-accept");
            thread.setDaemon(true);
            thread.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        boolean awaitReady(long ms) throws InterruptedException {
            return done.await(ms, TimeUnit.MILLISECONDS);
        }

        @Override public void close() {
            if (channel != null) {
                channel.close();
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /**
     * Completes the responder handshake with raw stream I/O, then goes silent so the
     * initiator's keep-alive has an unresponsive peer to detect.
     */
    private static final class SilentResponder implements AutoCloseable {
        final ServerSocket serverSocket;
        final Thread thread;
        final CountDownLatch done = new CountDownLatch(1);
        volatile Socket socket;
        volatile Throwable error;

        SilentResponder(KeyPair staticKey, byte[] prologue) throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            thread = new Thread(() -> {
                try {
                    socket = serverSocket.accept();
                    DataInputStream din = new DataInputStream(socket.getInputStream());
                    DataOutputStream dout = new DataOutputStream(socket.getOutputStream());
                    NoiseXXHandshake hs = new NoiseXXHandshake(
                            NoiseXXHandshake.Role.RESPONDER, staticKey, prologue, new SecureRandom());
                    hs.readMessage(readBlob(din));
                    writeBlob(dout, hs.writeMessage(new byte[0]));
                    hs.readMessage(readBlob(din));
                    // Deliberately never read or reply again.
                } catch (Throwable t) {
                    error = t;
                } finally {
                    done.countDown();
                }
            }, "test-p2p-silent");
            thread.setDaemon(true);
            thread.start();
        }

        private static byte[] readBlob(DataInputStream in) throws IOException {
            int len = in.readInt();
            byte[] buf = new byte[len];
            in.readFully(buf);
            return buf;
        }

        private static void writeBlob(DataOutputStream out, byte[] msg) throws IOException {
            out.writeInt(msg.length);
            out.write(msg);
            out.flush();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        boolean awaitHandshake(long ms) throws InterruptedException {
            return done.await(ms, TimeUnit.MILLISECONDS);
        }

        @Override public void close() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
                // best-effort
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
