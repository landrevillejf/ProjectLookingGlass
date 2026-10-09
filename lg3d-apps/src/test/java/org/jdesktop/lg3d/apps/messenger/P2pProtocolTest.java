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
package org.jdesktop.lg3d.apps.messenger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jdesktop.lg3d.apps.p2p.IdentityStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loopback integration tests for {@link P2pProtocol}: the native peer-to-peer
 * Messenger backend. Two backends, each with its own {@link IdentityStore} in a
 * separate temp directory (so their identities differ), come up over
 * {@code localhost} - one listening, one dialling the other's port - and the
 * protocol-neutral mapping is asserted: metadata and capabilities, an inert
 * headless constructor, chat/action/roster events delivered as {@link ChatMessage},
 * a full file offer/accept/verify cycle delivered as {@link FileTransferEvent},
 * trust-on-first-use pinning (a matching pin is admitted, a mismatch is refused
 * with a man-in-the-middle warning), the unknown-peer error path, and clean
 * disconnect. LAN discovery is disabled so the tests stay hermetic (multicast has
 * its own {@code LanDiscoveryTest}); every asynchronous wait polls a bounded
 * {@link #awaitTrue} rather than sleeping a fixed time.
 */
class P2pProtocolTest {

    private static final long TIMEOUT_MS = 15_000;

    @Test
    @DisplayName("metadata, capabilities and an inert headless constructor")
    void metadataAndInertConstruction(@TempDir Path dir) {
        P2pProtocol p = new P2pProtocol(new IdentityStore(dir), dir);
        assertEquals("p2p", p.id());
        assertEquals("P2P (Direct)", p.displayName());
        assertTrue(p.isNative());
        assertNotNull(p.description());
        assertTrue(p.description().toLowerCase().contains("peer"));
        assertTrue(p.capabilities().contains(MessengerProtocol.Capability.FILE_TRANSFER));
        assertTrue(p.capabilities().contains(MessengerProtocol.Capability.NATIVE));
        assertTrue(p.capabilities().contains(MessengerProtocol.Capability.CHAT));
        assertTrue(p.capabilities().contains(MessengerProtocol.Capability.PRESENCE));
        assertTrue(p.capabilities().contains(MessengerProtocol.Capability.ACTIONS));
        assertTrue(p.capabilities().contains(MessengerProtocol.Capability.TLS));

        // The constructor opens no socket and starts no thread.
        assertFalse(p.isConnected());
        assertEquals(null, p.getNode());
        assertTrue(p.getPeers().isEmpty());
        assertTrue(p.getDiscoveredPeers().isEmpty());
        assertTrue(p.isDiscoveryEnabled(), "discovery defaults on");
        assertEquals(dir, p.getDownloadDir());

        // Our identity is available (and stable) without connecting.
        String fp = p.getOurFingerprint();
        assertNotNull(fp);
        assertTrue(fp.contains(":"), "the fingerprint is colon-hex: " + fp);
        assertEquals(fp, p.getOurFingerprint(), "the identity is stable across reads");
        assertNotNull(p.getIdentityStore());

        // A send before connect is a silent no-op, not a crash.
        p.sendMessage("anyone", "hello");
        p.disconnect(); // never connected: no event, no throw
    }

    @Test
    @DisplayName("a listening node and a dialling node exchange chat, actions and roster")
    void chatActionAndRoster(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        P2pProtocol a = newProtocol(dirA);
        P2pProtocol b = newProtocol(dirB);
        Recorder la = new Recorder();
        Recorder lb = new Recorder();
        try {
            connectPair(a, la, b, lb, null);
            assertTrue(awaitTrue(() -> a.getPeers().size() == 1 && b.getPeers().size() == 1),
                    "both backends should register one peer");
            assertTrue(awaitTrue(() -> !la.connected.isEmpty() && !lb.connected.isEmpty()),
                    "both listeners should see onConnected");

            // Nicknames are learned from the presence exchange; wait before name routing.
            assertTrue(awaitTrue(() -> b.getPeers().stream().anyMatch(
                            p -> "alice".equals(p.getNickname()))
                            && a.getPeers().stream().anyMatch(p -> "bob".equals(p.getNickname()))),
                    "each side learns the other's nickname");

            b.sendMessage("alice", "hi alice");
            assertTrue(awaitTrue(() -> la.messages.stream().anyMatch(m ->
                            m.getKind() == ChatMessage.Kind.PRIVMSG
                                    && "hi alice".equals(m.getText())
                                    && "bob".equals(m.getFrom()))),
                    "A receives B's chat as a PRIVMSG from bob");

            a.sendAction("bob", "waves");
            assertTrue(awaitTrue(() -> lb.messages.stream().anyMatch(m ->
                            m.getKind() == ChatMessage.Kind.ACTION
                                    && "waves".equals(m.getText())
                                    && "alice".equals(m.getFrom()))),
                    "B receives A's action");

            // The connected-peer roster is reported under the pseudo-channel.
            assertTrue(awaitTrue(() -> la.rosters.stream().anyMatch(r -> r.contains("bob"))),
                    "A's roster lists the peer");

            // JOIN presence was surfaced as a chat event when the peer arrived.
            assertTrue(la.messages.stream().anyMatch(m -> m.getKind() == ChatMessage.Kind.JOIN),
                    "a peer arrival is reported as a JOIN");
        } finally {
            a.disconnect();
            b.disconnect();
        }
    }

    @Test
    @DisplayName("a file is offered, accepted, streamed and verified between backends")
    void fileTransferBetweenBackends(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        byte[] content = new byte[200 * 1024];
        new java.util.Random(11).nextBytes(content);
        Path source = dirB.resolve("report.bin");
        Files.write(source, content);

        P2pProtocol a = newProtocol(dirA); // receiver: files land in dirA
        P2pProtocol b = newProtocol(dirB); // sender
        Recorder la = new Recorder();
        Recorder lb = new Recorder();
        try {
            connectPair(a, la, b, lb, null);
            assertTrue(awaitTrue(() -> a.getPeers().size() == 1 && b.getPeers().size() == 1));

            String aFp = a.getOurFingerprint();
            assertTrue(b.sendFile(aFp, source), "the offer should be queued");
            // The sender sees its own OFFERED (SEND) event immediately.
            assertTrue(awaitTrue(() -> lb.files.stream().anyMatch(e ->
                            e.getDirection() == FileTransferEvent.Direction.SEND
                                    && e.getState() == FileTransferEvent.State.OFFERED)),
                    "B surfaces its outbound offer");

            // The receiver sees an inbound OFFERED (RECEIVE) event.
            assertTrue(awaitTrue(() -> la.files.stream().anyMatch(e ->
                            e.getDirection() == FileTransferEvent.Direction.RECEIVE
                                    && e.getState() == FileTransferEvent.State.OFFERED
                                    && "report.bin".equals(e.getFileName())
                                    && e.getFileSize() == content.length)),
                    "A sees the inbound offer with the right name and size");
            FileTransferEvent offer = la.files.stream()
                    .filter(e -> e.getDirection() == FileTransferEvent.Direction.RECEIVE
                            && e.getState() == FileTransferEvent.State.OFFERED)
                    .findFirst().orElseThrow();

            a.acceptFile(offer.getTransferId());
            assertTrue(awaitTrue(() -> la.files.stream().anyMatch(e ->
                            e.getState() == FileTransferEvent.State.COMPLETED && e.isVerified())
                            && lb.files.stream().anyMatch(e ->
                            e.getState() == FileTransferEvent.State.COMPLETED && e.isVerified())),
                    "both sides complete and verify the transfer");

            Path landed = dirA.resolve("report.bin");
            assertTrue(Files.exists(landed), "the file lands in A's download dir");
            assertArrayEquals(content, Files.readAllBytes(landed));
        } finally {
            a.disconnect();
            b.disconnect();
        }
    }

    @Test
    @DisplayName("an inbound file can be rejected, and the sender is told")
    void rejectInboundFile(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        Path source = dirB.resolve("nope.txt");
        Files.writeString(source, "declined");

        P2pProtocol a = newProtocol(dirA);
        P2pProtocol b = newProtocol(dirB);
        Recorder la = new Recorder();
        Recorder lb = new Recorder();
        try {
            connectPair(a, la, b, lb, null);
            assertTrue(awaitTrue(() -> a.getPeers().size() == 1 && b.getPeers().size() == 1));

            b.sendFile(a.getOurFingerprint(), source);
            assertTrue(awaitTrue(() -> la.files.stream().anyMatch(e ->
                    e.getDirection() == FileTransferEvent.Direction.RECEIVE
                            && e.getState() == FileTransferEvent.State.OFFERED)));
            FileTransferEvent offer = la.files.stream()
                    .filter(e -> e.getState() == FileTransferEvent.State.OFFERED
                            && e.getDirection() == FileTransferEvent.Direction.RECEIVE)
                    .findFirst().orElseThrow();

            a.rejectFile(offer.getTransferId(), "not now");
            assertTrue(awaitTrue(() -> la.files.stream().anyMatch(e ->
                            e.getState() == FileTransferEvent.State.REJECTED)),
                    "A records the rejection locally");
            assertTrue(awaitTrue(() -> lb.files.stream().anyMatch(e ->
                            e.getState() == FileTransferEvent.State.REJECTED
                                    || e.getState() == FileTransferEvent.State.CANCELLED)),
                    "B learns the offer was declined");
            assertFalse(Files.exists(dirA.resolve("nope.txt")), "a rejected file is never written");
        } finally {
            a.disconnect();
            b.disconnect();
        }
    }

    @Test
    @DisplayName("a matching pin admits the peer")
    void pinnedMatchIsAccepted(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        P2pProtocol a = newProtocol(dirA);
        P2pProtocol b = newProtocol(dirB);
        Recorder la = new Recorder();
        Recorder lb = new Recorder();
        try {
            String bFp = b.getOurFingerprint();
            connectPair(a, la, b, lb, bFp); // A pins B's real identity
            assertTrue(awaitTrue(() -> a.getPeers().size() == 1 && b.getPeers().size() == 1),
                    "a matching pin admits the peer");
            assertTrue(la.errors.stream().noneMatch(e -> e.contains("man-in-the-middle")),
                    "no security warning for a matching pin");
        } finally {
            a.disconnect();
            b.disconnect();
        }
    }

    @Test
    @DisplayName("a mismatched pin is refused with a man-in-the-middle warning")
    void pinnedMismatchIsRefused(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        P2pProtocol a = newProtocol(dirA);
        P2pProtocol b = newProtocol(dirB);
        Recorder la = new Recorder();
        Recorder lb = new Recorder();
        try {
            // A pins a bogus fingerprint, so B's real identity will not match.
            connectPair(a, la, b, lb, "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff");
            assertTrue(awaitTrue(() -> la.errors.stream()
                            .anyMatch(e -> e.toLowerCase().contains("man-in-the-middle"))),
                    "A refuses the peer and warns about a possible MITM");
            assertTrue(awaitTrue(() -> a.getPeers().isEmpty()),
                    "the refused peer never joins A's mesh");
        } finally {
            a.disconnect();
            b.disconnect();
        }
    }

    @Test
    @DisplayName("messaging an unknown peer reports an error rather than throwing")
    void unknownPeerReportsError(@TempDir Path dir) throws Exception {
        P2pProtocol a = newProtocol(dir);
        Recorder la = new Recorder();
        try {
            a.connect(new AccountConfig("A", "p2p", "", "alice"), la);
            assertTrue(awaitTrue(a::isConnected));
            a.sendMessage("no-such-peer", "hello?");
            a.sendFile("no-such-peer", dir.resolve("missing.bin"));
            assertTrue(awaitTrue(() -> la.errors.stream().anyMatch(e -> e.contains("No such peer"))),
                    "an unroutable target is reported");
        } finally {
            a.disconnect();
        }
    }

    @Test
    @DisplayName("disconnect tears the node down on both sides and fires onDisconnected")
    void disconnectTearsDown(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        P2pProtocol a = newProtocol(dirA);
        P2pProtocol b = newProtocol(dirB);
        Recorder la = new Recorder();
        Recorder lb = new Recorder();
        try {
            connectPair(a, la, b, lb, null);
            assertTrue(awaitTrue(() -> a.getPeers().size() == 1 && b.getPeers().size() == 1));

            a.disconnect();
            assertFalse(a.isConnected(), "A is down after disconnect");
            assertTrue(a.getPeers().isEmpty(), "A dropped its peers");
            assertEquals(1, la.disconnected.size(), "A fired onDisconnected once");
            assertTrue(awaitTrue(() -> b.getPeers().isEmpty()),
                    "B observes the link close");
        } finally {
            a.disconnect();
            b.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A backend over its own store, with LAN discovery disabled to stay hermetic. */
    private static P2pProtocol newProtocol(Path dir) {
        P2pProtocol p = new P2pProtocol(new IdentityStore(dir), dir);
        p.setDiscoveryEnabled(false);
        return p;
    }

    /**
     * Brings {@code a} up listening (with no host to dial), then points {@code b} at
     * A's ephemeral port and connects it. {@code aPinForB}, when non-null, is set as
     * A's pinned peer fingerprint before A connects.
     */
    private static void connectPair(P2pProtocol a, Recorder la, P2pProtocol b, Recorder lb,
                                    String aPinForB) throws InterruptedException {
        AccountConfig accA = new AccountConfig("A", "p2p", "", "alice");
        if (aPinForB != null) {
            accA.setPeerFingerprint(aPinForB);
        }
        a.connect(accA, la);
        assertTrue(awaitTrue(a::isConnected), "A should come up listening");
        assertTrue(a.getListenPort() > 0, "A should have a real listen port");

        AccountConfig accB = new AccountConfig("B", "p2p", "localhost", "bob");
        accB.setPort(a.getListenPort());
        b.connect(accB, lb);
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

    /** Captures every protocol event into synchronised lists for later assertion. */
    private static final class Recorder implements ProtocolListener {
        final List<AccountConfig> connected = Collections.synchronizedList(new ArrayList<>());
        final List<String> disconnected = Collections.synchronizedList(new ArrayList<>());
        final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());
        final List<String> status = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransferEvent> files = Collections.synchronizedList(new ArrayList<>());
        final List<List<String>> rosters = Collections.synchronizedList(new ArrayList<>());

        @Override public void onConnected(AccountConfig account) { connected.add(account); }
        @Override public void onDisconnected(AccountConfig account, String reason) {
            disconnected.add(reason);
        }
        @Override public void onMessage(AccountConfig account, ChatMessage message) {
            messages.add(message);
        }
        @Override public void onStatus(AccountConfig account, String s) { status.add(s); }
        @Override public void onError(AccountConfig account, String error) { errors.add(error); }
        @Override public void onFileTransfer(AccountConfig account, FileTransferEvent event) {
            files.add(event);
        }
        @Override public void onRosterUpdate(AccountConfig account, String channel,
                                             List<String> members) {
            rosters.add(new ArrayList<>(members));
        }
    }
}
