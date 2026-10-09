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
package org.jdesktop.lg3d.apps.videoconference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jdesktop.lg3d.apps.p2p.FileTransfer;
import org.jdesktop.lg3d.apps.p2p.IdentityStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loopback integration tests for {@link P2pSideChannel}: the Video Conference
 * app's encrypted signaling/chat/file controller over the shared {@code apps.p2p}
 * transport. Two channels, each with its own {@link IdentityStore} in a separate
 * temp directory (so their identities differ), come up over {@code localhost} -
 * one listening, one dialling the other's port - and the mapping is asserted:
 * metadata and an inert headless constructor, a meeting invite and a chat line
 * delivered to the peer, and a full file offer/accept/verify cycle. LAN discovery
 * is disabled so the tests stay hermetic (multicast has its own
 * {@code LanDiscoveryTest}); every asynchronous wait polls a bounded
 * {@link #awaitTrue} rather than sleeping a fixed time.
 */
class P2pSideChannelTest {

    private static final long TIMEOUT_MS = 15_000;

    @Test
    @DisplayName("metadata and an inert construction that opens no socket")
    void metadataAndInertConstruction(@TempDir Path dir) {
        Recorder r = new Recorder();
        P2pSideChannel ch = new P2pSideChannel(new IdentityStore(dir), dir, r);
        try {
            assertFalse(ch.isRunning());
            assertEquals(-1, ch.getPort());
            assertNull(ch.getOurFingerprint());
            assertNull(ch.getNode());
            assertTrue(ch.getPeers().isEmpty());
            assertTrue(ch.getDiscoveredPeers().isEmpty());
            assertTrue(ch.isDiscoveryEnabled(), "discovery defaults on");
            assertNotNull(ch.getIdentityStore());

            // Every send is a silent no-op before start(), never a crash.
            assertFalse(ch.sendChat("anyone", "hi"));
            assertEquals(0, ch.broadcastChat("hi"));
            assertFalse(ch.sendInvite("anyone", "room", "url"));
            assertEquals(0, ch.broadcastInvite("room", "url"));
            assertFalse(ch.offerFile("anyone", dir));
            assertFalse(ch.connectToPeer("localhost", 1));
            ch.acceptFile("nope");
            ch.rejectFile("nope", "x");
        } finally {
            ch.close();
        }
        assertFalse(ch.isRunning());
    }

    @Test
    @DisplayName("a null identity store is rejected")
    void rejectsNullIdentityStore(@TempDir Path dir) {
        try {
            new P2pSideChannel(null, dir, null);
            org.junit.jupiter.api.Assertions.fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("identity"));
        }
    }

    @Test
    @DisplayName("two channels exchange an invite, a chat line and a verified file")
    void inviteChatAndFileOverLoopback(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        byte[] content = new byte[150 * 1024];
        new java.util.Random(7).nextBytes(content);
        Path source = dirB.resolve("deck.pdf");
        Files.write(source, content);

        Recorder ra = new Recorder();
        Recorder rb = new Recorder();
        P2pSideChannel a = newChannel(dirA, ra); // listener + receiver
        P2pSideChannel b = newChannel(dirB, rb); // dialler + sender
        try {
            assertTrue(a.start("alice", 0), "A should bind an ephemeral port");
            assertTrue(awaitTrue(a::isRunning), "A should be running");
            int aPort = a.getPort();
            assertTrue(aPort > 0, "A reports its listen port");
            assertNotNull(a.getOurFingerprint());

            assertTrue(b.start("bob", 0));
            assertTrue(b.connectToPeer("localhost", aPort), "the dial is dispatched");
            assertTrue(awaitTrue(() -> a.getPeers().size() == 1 && b.getPeers().size() == 1),
                    "both channels register one peer");

            // An invite from B reaches A with the room and the share URL.
            String aFp = a.getOurFingerprint();
            assertTrue(b.sendInvite(aFp, "Room42", "https://meet.jit.si/Room42"));
            assertTrue(awaitTrue(() -> ra.invites.stream().anyMatch(inv ->
                            "Room42".equals(inv[1]) && "https://meet.jit.si/Room42".equals(inv[2]))),
                    "A receives the meeting invite");

            // A chat line from B reaches A.
            assertTrue(b.sendChat(aFp, "see you there"));
            assertTrue(awaitTrue(() -> ra.chat.stream().anyMatch(c ->
                            c.contains("see you there"))),
                    "A receives the chat");

            // A file offer from B is accepted by A, streamed and verified.
            assertTrue(b.offerFile(aFp, source), "the offer is queued");
            assertTrue(awaitTrue(() -> !ra.offers.isEmpty()), "A sees the inbound offer");
            FileTransfer offer = ra.offers.get(0);
            assertEquals("deck.pdf", offer.getFileName());
            assertEquals(content.length, offer.getFileSize());
            a.acceptFile(offer.getId());
            assertTrue(awaitTrue(() -> ra.completed.stream().anyMatch(FileTransfer::isVerified)
                            && rb.completed.stream().anyMatch(FileTransfer::isVerified)),
                    "both sides complete and verify the transfer");

            Path landed = dirA.resolve("deck.pdf");
            assertTrue(Files.exists(landed), "the file lands in A's download dir");
            assertArrayEquals(content, Files.readAllBytes(landed));
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    @DisplayName("close() stops the channel and is idempotent")
    void closeTearsDown(@TempDir Path dir) {
        Recorder r = new Recorder();
        P2pSideChannel ch = newChannel(dir, r);
        assertTrue(ch.start("solo", 0));
        assertTrue(ch.isRunning());
        ch.close();
        assertFalse(ch.isRunning());
        assertNull(ch.getNode());
        ch.close(); // idempotent
        assertFalse(ch.isRunning());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static P2pSideChannel newChannel(Path dir, Recorder listener) {
        P2pSideChannel ch = new P2pSideChannel(new IdentityStore(dir), dir, listener);
        ch.setDiscoveryEnabled(false); // hermetic: no multicast in the test JVM
        return ch;
    }

    private static boolean awaitTrue(BooleanSupplier condition) {
        return awaitTrue(condition, TIMEOUT_MS);
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** Records every side-channel callback on a synchronized list. */
    private static final class Recorder implements P2pSideChannel.Listener {
        final List<String> peersChanged = Collections.synchronizedList(new ArrayList<>());
        final List<String> chat = Collections.synchronizedList(new ArrayList<>());
        final List<String[]> invites = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> offers = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> completed = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> closed = Collections.synchronizedList(new ArrayList<>());
        final List<String> status = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        @Override public void onPeersChanged() { peersChanged.add("changed"); }
        @Override public void onChat(String peerName, String text, boolean action) {
            chat.add((action ? "* " : "") + peerName + ": " + text);
        }
        @Override public void onInvite(String peerName, String room, String url) {
            invites.add(new String[] {peerName, room, url});
        }
        @Override public void onFileOffer(String peerName, FileTransfer transfer) {
            offers.add(transfer);
        }
        @Override public void onFileProgress(String peerName, FileTransfer transfer) {
            // not asserted; progress is high-frequency
        }
        @Override public void onFileComplete(String peerName, FileTransfer transfer) {
            completed.add(transfer);
        }
        @Override public void onFileClosed(String peerName, FileTransfer transfer) {
            closed.add(transfer);
        }
        @Override public void onStatus(String s) { status.add(s); }
        @Override public void onError(String error) { errors.add(error); }
    }
}
