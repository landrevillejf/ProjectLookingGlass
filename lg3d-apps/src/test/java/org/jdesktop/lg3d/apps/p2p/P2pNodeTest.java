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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loopback integration tests for {@link P2pNode} and {@link P2pServer}. Real nodes
 * dial and accept over {@code localhost} ephemeral ports, then the mesh behaviour
 * is asserted: mutual registration and fingerprint identity, presence/nickname
 * learning, one-to-one chat and action routing, broadcast fan-out, typing and
 * video-conference invites, a full file transfer driven through the node, TOFU
 * trust rejection (the dialling side gets a null peer, the accepting side ends up
 * with none), and clean disconnect/BYE/close teardown. Mesh events are inherently
 * asynchronous, so every wait polls a bounded {@link #awaitTrue} rather than
 * sleeping a fixed time - a regression fails fast instead of hanging.
 */
class P2pNodeTest {

    private static final long TIMEOUT_MS = 10_000;

    @Test
    @DisplayName("two nodes connect over loopback, identify each other and chat")
    void connectIdentifyAndChat() throws Exception {
        Recorder recA = new Recorder();
        Recorder recB = new Recorder();
        try (P2pNode a = newNode("alice", null, recA);
             P2pNode b = newNode("bob", null, recB)) {
            a.start();
            assertTrue(a.isRunning(), "the server should be running after start()");
            assertTrue(a.getPort() > 0, "an ephemeral port should have been assigned");

            P2pNode.Peer bViewOfA = b.connect("localhost", a.getPort());
            assertNotNull(bViewOfA, "the dialling node should register the peer");
            assertEquals(a.getFingerprint(), bViewOfA.getFingerprint());
            assertFalse(bViewOfA.isInbound(), "B dialled out, so its peer is outbound");

            assertTrue(awaitTrue(() -> a.getPeerCount() == 1 && b.getPeerCount() == 1),
                    "both nodes should register exactly one peer");
            P2pNode.Peer aViewOfB = a.getPeer(b.getFingerprint());
            assertNotNull(aViewOfB, "A should have B in its peer map");
            assertTrue(aViewOfB.isInbound(), "A accepted the link, so its peer is inbound");
            assertEquals(b.getFingerprint(), a.getPeer(b.getFingerprint()).getFingerprint());

            // Presence announced on connect teaches each side the other's nickname.
            assertTrue(awaitTrue(() -> "bob".equals(aViewOfB.getNickname())
                            && "alice".equals(bViewOfA.getNickname())),
                    "nicknames should be learned from the presence exchange");

            assertTrue(b.sendChat(a.getFingerprint(), "hi alice"), "sendChat should reach A");
            assertTrue(awaitTrue(() -> recA.chats.stream()
                            .anyMatch(m -> "hi alice".equals(m.getText()) && "bob".equals(m.getFrom()))),
                    "A should receive the chat tagged with B's nickname");

            a.sendAction(b.getFingerprint(), "waves");
            assertTrue(awaitTrue(() -> recB.chats.stream()
                            .anyMatch(m -> m.getKind() == P2pMessage.Kind.ACTION
                                    && "waves".equals(m.getText()))),
                    "B should receive the action");

            // Sending to an unknown fingerprint is a silent no-op, not a crash.
            assertFalse(a.sendChat("no-such-peer", "hello?"));
        }
    }

    @Test
    @DisplayName("a broadcast fans out to every connected peer")
    void broadcastFansOut() throws Exception {
        Recorder recHub = new Recorder();
        Recorder rec1 = new Recorder();
        Recorder rec2 = new Recorder();
        try (P2pNode hub = newNode("hub", null, recHub);
             P2pNode s1 = newNode("one", null, rec1);
             P2pNode s2 = newNode("two", null, rec2)) {
            hub.start();
            s1.connect("localhost", hub.getPort());
            s2.connect("localhost", hub.getPort());
            assertTrue(awaitTrue(() -> hub.getPeerCount() == 2), "the hub should see both spokes");

            int reached = hub.broadcastChat("hello all");
            assertEquals(2, reached, "the broadcast should reach both peers");
            assertTrue(awaitTrue(() -> rec1.chats.stream().anyMatch(m -> "hello all".equals(m.getText()))
                            && rec2.chats.stream().anyMatch(m -> "hello all".equals(m.getText()))),
                    "both spokes should receive the broadcast");
        }
    }

    @Test
    @DisplayName("typing and invite signals route to the right peer")
    void typingAndInviteRoute() throws Exception {
        Recorder recA = new Recorder();
        Recorder recB = new Recorder();
        try (P2pNode a = newNode("alice", null, recA);
             P2pNode b = newNode("bob", null, recB)) {
            a.start();
            b.connect("localhost", a.getPort());
            assertTrue(awaitTrue(() -> a.getPeerCount() == 1 && b.getPeerCount() == 1));

            b.sendTyping(a.getFingerprint(), true);
            assertTrue(awaitTrue(() -> recA.typing.contains(Boolean.TRUE)), "A should see typing");

            b.sendInvite(a.getFingerprint(), "weekly-standup", "https://meet.example/standup");
            assertTrue(awaitTrue(() -> recA.invites.stream().anyMatch(
                            i -> "weekly-standup".equals(i[0]) && "https://meet.example/standup".equals(i[1]))),
                    "A should receive the invite with its room and URL");
        }
    }

    @Test
    @DisplayName("a file offered through the node is accepted, streamed and verified")
    void fileTransferThroughNode(@TempDir Path senderDir, @TempDir Path receiverDir) throws Exception {
        byte[] content = new byte[200 * 1024];
        new java.util.Random(7).nextBytes(content);
        Path source = senderDir.resolve("report.bin");
        Files.write(source, content);

        Recorder recA = new Recorder();
        Recorder recB = new Recorder();
        // A is the receiver (its download dir is receiverDir); B is the sender and
        // reads the source out of senderDir.
        try (P2pNode a = newNode("alice", receiverDir, recA);
             P2pNode b = newNode("bob", senderDir, recB)) {
            a.start();
            b.connect("localhost", a.getPort());
            assertTrue(awaitTrue(() -> a.getPeerCount() == 1 && b.getPeerCount() == 1));

            // B offers a file to A; A is the receiver here.
            FileTransfer offered = b.offerFile(a.getFingerprint(), source);
            assertNotNull(offered, "the offer should return a pending transfer");
            assertTrue(awaitTrue(() -> !recA.offers.isEmpty()), "A should see the offer");
            FileTransfer inbound = recA.offers.get(0);
            assertEquals("report.bin", inbound.getFileName());
            assertEquals(content.length, inbound.getFileSize());

            a.acceptFile(b.getFingerprint(), inbound.getId());
            assertTrue(awaitTrue(() -> recA.completed.stream().anyMatch(FileTransfer::isVerified)
                            && recB.completed.stream().anyMatch(FileTransfer::isVerified)),
                    "both sides should complete and verify the transfer");

            Path landed = receiverDir.resolve("report.bin");
            assertTrue(Files.exists(landed), "the file should land in A's download dir");
            assertArrayEquals(content, Files.readAllBytes(landed));
        }
    }

    @Test
    @DisplayName("an untrusted peer is rejected by the trust verifier")
    void untrustedPeerRejected(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        Recorder recA = new Recorder();
        Recorder recB = new Recorder();
        try (P2pNode a = newNode("alice", dirA, recA);
             P2pNode b = newNode("bob", dirB, recB)) {
            a.start();
            // B refuses everyone: the handshake completes but B declines to adopt it.
            b.setPeerVerifier(fingerprint -> false);

            P2pNode.Peer peer = b.connect("localhost", a.getPort());
            assertNull(peer, "a rejected peer must not be returned");
            assertEquals(0, b.getPeerCount(), "the rejected peer must not join B's mesh");
            assertTrue(awaitTrue(() -> recB.errors.stream().anyMatch(e -> e.contains("untrusted"))),
                    "the rejection should be reported as an error");
            // A briefly saw the inbound link, then B tore it down.
            assertTrue(awaitTrue(() -> a.getPeerCount() == 0),
                    "A should end with no peers once B closes the rejected link");
        }
    }

    @Test
    @DisplayName("a trusted verifier admits the peer normally")
    void trustedVerifierAdmits(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        Recorder recA = new Recorder();
        Recorder recB = new Recorder();
        try (P2pNode a = newNode("alice", dirA, recA);
             P2pNode b = newNode("bob", dirB, recB)) {
            a.start();
            b.setPeerVerifier(fingerprint -> true);
            assertNotNull(b.connect("localhost", a.getPort()));
            assertTrue(awaitTrue(() -> a.getPeerCount() == 1 && b.getPeerCount() == 1));
        }
    }

    @Test
    @DisplayName("disconnect() drops one peer on both sides")
    void disconnectRemovesPeer() throws Exception {
        Recorder recA = new Recorder();
        Recorder recB = new Recorder();
        try (P2pNode a = newNode("alice", null, recA);
             P2pNode b = newNode("bob", null, recB)) {
            a.start();
            b.connect("localhost", a.getPort());
            assertTrue(awaitTrue(() -> a.getPeerCount() == 1 && b.getPeerCount() == 1));

            a.disconnect(b.getFingerprint());
            assertTrue(awaitTrue(() -> a.getPeerCount() == 0 && b.getPeerCount() == 0),
                    "both sides should drop the peer after a disconnect");
            // Disconnecting an unknown fingerprint is a harmless no-op.
            a.disconnect("no-such-peer");
        }
    }

    @Test
    @DisplayName("close() sends BYE and the peer observes the goodbye")
    void byeReachesPeer() throws Exception {
        Recorder recA = new Recorder();
        Recorder recB = new Recorder();
        P2pNode a = newNode("alice", null, recA);
        try (P2pNode b = newNode("bob", null, recB)) {
            a.start();
            b.connect("localhost", a.getPort());
            assertTrue(awaitTrue(() -> a.getPeerCount() == 1 && b.getPeerCount() == 1));

            a.close(); // sends BYE to B, then closes the link
            assertTrue(awaitTrue(() -> b.getPeerCount() == 0), "B should drop A after the BYE");
            assertTrue(awaitTrue(() -> recB.disconnected.stream()
                            .anyMatch(r -> r != null && r.toLowerCase().contains("goodbye"))),
                    "B should record the goodbye reason");
            assertFalse(a.isRunning(), "a closed node is not running");
        }
    }

    @Test
    @DisplayName("start() is idempotent and keeps the same port")
    void startIsIdempotent() throws Exception {
        try (P2pNode a = newNode("alice", null, new Recorder())) {
            assertFalse(a.isRunning());
            assertEquals(-1, a.getPort(), "an unstarted node has no port");
            a.start();
            int port = a.getPort();
            assertTrue(port > 0);
            a.start();      // a second start must not rebind
            a.start(port);  // nor must an explicit restart
            assertEquals(port, a.getPort(), "the listening port is stable across restarts");
            assertTrue(a.isRunning());
        }
    }

    @Test
    @DisplayName("identity, nickname and query accessors behave")
    void accessorsAndDefaults() throws Exception {
        Recorder rec = new Recorder();
        try (P2pNode blank = newNode("   ", null, rec)) {
            assertNotNull(blank.getIdentity());
            assertNotNull(blank.getFingerprint());
            assertTrue(blank.getFingerprint().contains(":"), "the fingerprint is colon-hex");
            assertTrue(blank.getNickname().startsWith("peer-"),
                    "a blank nickname falls back to a fingerprint tag: " + blank.getNickname());
            assertNotNull(blank.getDownloadDir(), "a null download dir falls back to a default");
            assertEquals(0, blank.getPeerCount());
            assertTrue(blank.getPeers().isEmpty());
            assertNull(blank.getPeer(null));
            assertNull(blank.getPeer("unknown"));

            blank.setNickname("zed");
            assertEquals("zed", blank.getNickname());
            // A null verifier is accepted (restores the default allow-all behaviour).
            blank.setPeerVerifier(null);
        }
    }

    @Test
    @DisplayName("the constructor rejects a null identity and honours a set download dir")
    void constructorGuards(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new P2pNode(null, "x", dir, null));
        try (P2pNode n = newNode("alice", dir, null)) {
            assertEquals(dir, n.getDownloadDir());
        }
    }

    @Test
    @DisplayName("offering a file to an unknown peer returns null without throwing")
    void offerToUnknownPeerIsNull(@TempDir Path dir) throws Exception {
        try (P2pNode a = newNode("alice", dir, new Recorder())) {
            Path f = dir.resolve("x.txt");
            Files.writeString(f, "data");
            assertNull(a.offerFile("no-such-peer", f));
            // The file-control no-ops must be safe with no such peer.
            a.acceptFile("no-such-peer", "t");
            a.rejectFile("no-such-peer", "t", "no");
            a.cancelFile("no-such-peer", "t", "no");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static P2pNode newNode(String nickname, Path downloadDir, P2pNode.Listener listener)
            throws GeneralSecurityException {
        return new P2pNode(P2pCrypto.generateKeyPair(), nickname, downloadDir, listener);
    }

    private static boolean awaitTrue(BooleanSupplier condition) throws InterruptedException {
        return awaitTrue(condition, TIMEOUT_MS);
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /** Captures every node event into synchronised lists for later assertion. */
    private static final class Recorder implements P2pNode.Listener {
        final List<P2pNode.Peer> connected = Collections.synchronizedList(new ArrayList<>());
        final List<String> disconnected = Collections.synchronizedList(new ArrayList<>());
        final List<P2pMessage> chats = Collections.synchronizedList(new ArrayList<>());
        final List<String> presence = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> typing = Collections.synchronizedList(new ArrayList<>());
        final List<String[]> invites = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> offers = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> completed = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> cancelled = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> failed = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onPeerConnected(P2pNode.Peer peer) {
            connected.add(peer);
        }

        @Override
        public void onPeerDisconnected(P2pNode.Peer peer, String reason) {
            disconnected.add(reason);
        }

        @Override
        public void onChat(P2pNode.Peer peer, P2pMessage message) {
            chats.add(message);
        }

        @Override
        public void onPresence(P2pNode.Peer peer, String status) {
            presence.add(status);
        }

        @Override
        public void onTyping(P2pNode.Peer peer, boolean isTyping) {
            typing.add(isTyping);
        }

        @Override
        public void onInvite(P2pNode.Peer peer, String room, String url) {
            invites.add(new String[]{room, url});
        }

        @Override
        public void onFileOffer(P2pNode.Peer peer, FileTransfer transfer) {
            offers.add(transfer);
        }

        @Override
        public void onFileProgress(P2pNode.Peer peer, FileTransfer transfer) {
            // not asserted directly
        }

        @Override
        public void onFileCompleted(P2pNode.Peer peer, FileTransfer transfer) {
            completed.add(transfer);
        }

        @Override
        public void onFileCancelled(P2pNode.Peer peer, FileTransfer transfer) {
            cancelled.add(transfer);
        }

        @Override
        public void onFileFailed(P2pNode.Peer peer, FileTransfer transfer) {
            failed.add(transfer);
        }

        @Override
        public void onError(String error) {
            errors.add(error);
        }
    }
}
