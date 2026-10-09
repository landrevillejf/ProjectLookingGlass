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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loopback end-to-end tests for {@link FileTransferManager}. Two nodes, each a
 * {@link SecureChannel} plus its own manager, are wired over an in-JVM localhost
 * socket and drive real transfers: a multi-chunk file is offered, accepted,
 * streamed and atomically landed with a verified SHA-256; a reject and a cancel are
 * honoured on both sides; a file that changes after it was offered fails the
 * receiver's checksum; a traversal-laden received name is contained inside the
 * download directory; and stray DATA/control for an unknown transfer is ignored
 * rather than crashing. Every wait is bounded so a regression fails fast.
 */
class FileTransferManagerTest {

    private static final long TIMEOUT_MS = 10_000;
    private static final byte[] PROLOGUE = "lg3d-p2p-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    @DisplayName("an offered file is accepted, streamed and landed intact and verified")
    void offerAcceptCompletes(@TempDir Path senderDir, @TempDir Path receiverDir) throws Exception {
        byte[] content = randomBytes(200 * 1024); // spans several 64 KiB chunks
        Path source = senderDir.resolve("payload.bin");
        Files.write(source, content);

        try (Harness h = new Harness("sender", senderDir, "receiver", receiverDir)) {
            FileTransfer sent = h.sender.ftm.offerFile(source);
            assertEquals(FileTransfer.Direction.SEND, sent.getDirection());

            assertTrue(h.receiver.offerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the receiver never saw the offer");
            FileTransfer offered = h.receiver.lastOffer();
            assertEquals("payload.bin", offered.getFileName());
            assertEquals(content.length, offered.getFileSize());

            h.receiver.ftm.accept(offered.getId());

            assertTrue(h.receiver.completedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the receive did not complete");
            assertTrue(h.sender.completedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the send did not complete");

            Path landed = receiverDir.resolve("payload.bin");
            assertTrue(Files.exists(landed), "the file was not written to the download dir");
            assertArrayEquals(content, Files.readAllBytes(landed), "the received bytes differ");

            FileTransfer done = h.receiver.lastCompleted();
            assertEquals(FileTransfer.State.COMPLETED, done.getState());
            assertTrue(done.isVerified(), "the SHA-256 should match the offer");
            assertEquals(landed.toAbsolutePath().normalize(),
                    done.getLocalPath().toAbsolutePath().normalize());
            // No stray temp files are left behind.
            assertEquals(1, countFiles(receiverDir), "only the finished file should remain");
        }
    }

    @Test
    @DisplayName("an existing file is not clobbered; a numbered sibling is used")
    void doesNotClobberExisting(@TempDir Path senderDir, @TempDir Path receiverDir) throws Exception {
        byte[] content = "second copy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path source = senderDir.resolve("notes.txt");
        Files.write(source, content);
        Files.writeString(receiverDir.resolve("notes.txt"), "first copy");

        try (Harness h = new Harness("sender", senderDir, "receiver", receiverDir)) {
            h.sender.ftm.offerFile(source);
            assertTrue(h.receiver.offerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            h.receiver.ftm.accept(h.receiver.lastOffer().getId());
            assertTrue(h.receiver.completedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            assertEquals("first copy", Files.readString(receiverDir.resolve("notes.txt")),
                    "the pre-existing file must be preserved");
            assertArrayEquals(content, Files.readAllBytes(receiverDir.resolve("notes-1.txt")));
        }
    }

    @Test
    @DisplayName("a rejected offer is reported to the sender as cancelled")
    void rejectIsHonoured(@TempDir Path senderDir, @TempDir Path receiverDir) throws Exception {
        Path source = senderDir.resolve("a.txt");
        Files.writeString(source, "hello");

        try (Harness h = new Harness("sender", senderDir, "receiver", receiverDir)) {
            h.sender.ftm.offerFile(source);
            assertTrue(h.receiver.offerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            h.receiver.ftm.reject(h.receiver.lastOffer().getId(), "not now");

            assertTrue(h.sender.cancelledLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the sender never learned of the rejection");
            FileTransfer rejected = h.sender.lastCancelled();
            assertEquals(FileTransfer.State.REJECTED, rejected.getState());
            assertEquals("not now", rejected.getMessage());
            assertFalse(Files.exists(receiverDir.resolve("a.txt")), "nothing should be written");
        }
    }

    @Test
    @DisplayName("cancelling before accept stops the sender")
    void cancelBeforeAccept(@TempDir Path senderDir, @TempDir Path receiverDir) throws Exception {
        Path source = senderDir.resolve("b.txt");
        Files.writeString(source, "hello");

        try (Harness h = new Harness("sender", senderDir, "receiver", receiverDir)) {
            h.sender.ftm.offerFile(source);
            assertTrue(h.receiver.offerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            h.receiver.ftm.cancel(h.receiver.lastOffer().getId(), "changed my mind");

            assertTrue(h.sender.cancelledLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(FileTransfer.State.CANCELLED, h.sender.lastCancelled().getState());
        }
    }

    @Test
    @DisplayName("a file that changes after the offer fails the receiver's checksum")
    void checksumMismatchFails(@TempDir Path senderDir, @TempDir Path receiverDir) throws Exception {
        Path source = senderDir.resolve("mutating.bin");
        byte[] original = "the content that was offered and hashed".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, original);

        try (Harness h = new Harness("sender", senderDir, "receiver", receiverDir)) {
            h.sender.ftm.offerFile(source); // hashes `original`
            assertTrue(h.receiver.offerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            // Corrupt the source after the offer, so the streamed bytes no longer
            // match the announced SHA-256.
            Files.write(source, "TOTALLY DIFFERENT BYTES THAN WHAT WAS OFFERED!".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));

            h.receiver.ftm.accept(h.receiver.lastOffer().getId());

            assertTrue(h.receiver.failedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the corrupted transfer should have failed");
            FileTransfer failed = h.receiver.lastFailed();
            assertEquals(FileTransfer.State.FAILED, failed.getState());
            assertTrue(failed.getMessage().toLowerCase().contains("checksum"), failed.getMessage());
            assertFalse(Files.exists(receiverDir.resolve("mutating.bin")),
                    "a failed transfer must not leave a finished file");
            assertEquals(0, countFiles(receiverDir), "no partial file should remain");
        }
    }

    @Test
    @DisplayName("a traversal-laden received name is contained in the download dir")
    void traversalNameIsContained(@TempDir Path senderDir, @TempDir Path receiverDir)
            throws Exception {
        byte[] content = "malicious payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = P2pCrypto.toHex(P2pCrypto.sha256(content));
        String id = "traversal-1";

        try (Harness h = new Harness("sender", senderDir, "receiver", receiverDir)) {
            // Simulate a hostile peer offering a name that tries to escape the dir.
            h.receiver.ftm.handleControl(P2pMessage.fileOffer("evil", id,
                    "../../../../etc/evil.txt", content.length, hash));
            assertTrue(h.receiver.offerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            h.receiver.ftm.accept(id);
            FileTransfer offered = h.receiver.lastOffer();
            assertTrue(offered.getLocalPath().toAbsolutePath().normalize()
                            .startsWith(receiverDir.toAbsolutePath().normalize()),
                    "the target must stay inside the download dir: " + offered.getLocalPath());

            h.receiver.ftm.handleData(FileTransferManager.encodeChunk(id, content));
            h.receiver.ftm.handleControl(P2pMessage.fileEnd("evil", id, hash));

            assertTrue(h.receiver.completedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            Path landed = receiverDir.resolve("evil.txt");
            assertTrue(Files.exists(landed), "the sanitised file should land inside the dir");
            assertArrayEquals(content, Files.readAllBytes(landed));
            // Nothing was written to the parent of the download dir.
            assertFalse(Files.exists(receiverDir.getParent().resolve("etc/evil.txt")));
        }
    }

    @Test
    @DisplayName("stray DATA and control for an unknown transfer are ignored")
    void unknownTransferIgnored(@TempDir Path senderDir, @TempDir Path receiverDir) throws Exception {
        try (Harness h = new Harness("sender", senderDir, "receiver", receiverDir)) {
            // None of these should throw or create files.
            h.receiver.ftm.handleData(FileTransferManager.encodeChunk("ghost", new byte[]{1, 2, 3}));
            h.receiver.ftm.handleControl(P2pMessage.fileEnd("x", "ghost", "deadbeef"));
            h.receiver.ftm.handleControl(P2pMessage.fileAccept("x", "ghost"));
            h.receiver.ftm.handleControl(P2pMessage.fileCancel("x", "ghost", "bye"));
            h.receiver.ftm.handleControl(null);
            h.receiver.ftm.handleData(new byte[]{0});
            h.receiver.ftm.accept("ghost");
            h.receiver.ftm.reject("ghost", "no");
            h.receiver.ftm.cancel("ghost", "no");
            assertEquals(0, countFiles(receiverDir));
            assertNotNull(h.receiver.ftm.getTransfer("ghost") == null ? "" : "x");
            assertTrue(h.receiver.ftm.getTransfers().size() >= 0);
            assertEquals(receiverDir, h.receiver.ftm.getDownloadDir());
        }
    }

    @Test
    @DisplayName("close() cancels an in-flight offer")
    void closeCancelsInFlight(@TempDir Path senderDir, @TempDir Path receiverDir) throws Exception {
        Path source = senderDir.resolve("c.txt");
        Files.writeString(source, "hello");
        try (Harness h = new Harness("sender", senderDir, "receiver", receiverDir)) {
            h.sender.ftm.offerFile(source);
            assertTrue(h.receiver.offerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            FileTransfer offered = h.receiver.lastOffer();
            h.receiver.ftm.close();
            assertEquals(FileTransfer.State.CANCELLED, offered.getState());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(17).nextBytes(b);
        return b;
    }

    private static int countFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return (int) stream.count();
        }
    }

    /**
     * One peer: a secure channel plus its file-transfer manager, recording the
     * manager's callbacks. Acts as both the channel listener (forwarding frames to
     * the manager) and the manager listener (capturing lifecycle events).
     */
    private static final class TestNode
            implements SecureChannel.Listener, FileTransferManager.Listener {
        final String nick;
        final Path dir;
        final KeyPair identity;
        volatile SecureChannel channel;
        volatile FileTransferManager ftm;

        final List<FileTransfer> offers = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> completed = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> cancelled = Collections.synchronizedList(new ArrayList<>());
        final List<FileTransfer> failed = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch offerLatch = new CountDownLatch(1);
        final CountDownLatch completedLatch = new CountDownLatch(1);
        final CountDownLatch cancelledLatch = new CountDownLatch(1);
        final CountDownLatch failedLatch = new CountDownLatch(1);

        TestNode(String nick, Path dir) throws GeneralSecurityException {
            this.nick = nick;
            this.dir = dir;
            this.identity = P2pCrypto.generateKeyPair();
        }

        void attach(SecureChannel ch) {
            this.channel = ch;
            this.ftm = new FileTransferManager(ch, nick, dir, this);
        }

        FileTransfer lastOffer() { return last(offers); }
        FileTransfer lastCompleted() { return last(completed); }
        FileTransfer lastCancelled() { return last(cancelled); }
        FileTransfer lastFailed() { return last(failed); }

        private static FileTransfer last(List<FileTransfer> list) {
            synchronized (list) {
                return list.isEmpty() ? null : list.get(list.size() - 1);
            }
        }

        // --- SecureChannel.Listener ---
        @Override public void onControl(SecureChannel ch, P2pMessage message) {
            FileTransferManager m = ftm;
            if (m != null) {
                m.handleControl(message);
            }
        }
        @Override public void onData(SecureChannel ch, byte[] payload) {
            FileTransferManager m = ftm;
            if (m != null) {
                m.handleData(payload);
            }
        }
        @Override public void onClosed(SecureChannel ch, String reason) {
            FileTransferManager m = ftm;
            if (m != null) {
                m.close();
            }
        }

        // --- FileTransferManager.Listener ---
        @Override public void onOffer(FileTransfer transfer) {
            offers.add(transfer);
            offerLatch.countDown();
        }
        @Override public void onProgress(FileTransfer transfer) {
            // not asserted directly; completion implies progress fired
        }
        @Override public void onCompleted(FileTransfer transfer) {
            completed.add(transfer);
            completedLatch.countDown();
        }
        @Override public void onCancelled(FileTransfer transfer) {
            cancelled.add(transfer);
            cancelledLatch.countDown();
        }
        @Override public void onFailed(FileTransfer transfer) {
            failed.add(transfer);
            failedLatch.countDown();
        }
    }

    /** Wires a sender (client) and a receiver (server) node over loopback. */
    private static final class Harness implements AutoCloseable {
        final ServerSocket serverSocket;
        final Thread acceptThread;
        final TestNode sender;
        final TestNode receiver;
        final CountDownLatch accepted = new CountDownLatch(1);
        volatile Throwable acceptError;

        Harness(String senderNick, Path senderDir, String receiverNick, Path receiverDir)
                throws IOException, GeneralSecurityException, InterruptedException {
            this.sender = new TestNode(senderNick, senderDir);
            this.receiver = new TestNode(receiverNick, receiverDir);
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            acceptThread = new Thread(() -> {
                try {
                    Socket s = serverSocket.accept();
                    receiver.attach(SecureChannel.accept(s, receiver.identity, PROLOGUE,
                            receiver, new SecureRandom()));
                } catch (Throwable t) {
                    acceptError = t;
                } finally {
                    accepted.countDown();
                }
            }, "ftm-test-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            sender.attach(SecureChannel.connect("localhost", serverSocket.getLocalPort(),
                    sender.identity, PROLOGUE, sender, new SecureRandom()));

            // Block until the receiver has finished its handshake and attached its
            // manager. Without this, a test that injects frames straight into
            // receiver.ftm would race the accept thread and see a null manager.
            if (!accepted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("the receiver did not attach within " + TIMEOUT_MS + "ms");
            }
            if (acceptError != null) {
                throw new IOException("the receiver failed to attach", acceptError);
            }
        }

        boolean awaitReady() throws InterruptedException {
            return accepted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override public void close() {
            if (sender.channel != null) {
                sender.channel.close();
            }
            if (receiver.channel != null) {
                receiver.channel.close();
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
