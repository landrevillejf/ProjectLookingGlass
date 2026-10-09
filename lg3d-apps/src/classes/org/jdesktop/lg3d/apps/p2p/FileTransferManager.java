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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The streaming driver for peer-to-peer file transfer over one {@link SecureChannel}.
 * It owns the offer/accept/reject/progress/cancel control plane (as
 * {@link P2pMessage}s) and the DATA-plane chunking, and advances a {@link FileTransfer}
 * model for each exchange so the UI can observe progress without touching I/O.
 *
 * <p><strong>Sending.</strong> {@link #offerFile(Path)} hashes the file (SHA-256),
 * announces it with a {@code FILE_OFFER}, and - once the peer accepts - streams it
 * in {@value #CHUNK_BYTES}-byte chunks on a dedicated daemon thread (so the
 * channel's reader thread stays free to receive, including a mid-send cancel). A
 * {@code FILE_END} carrying the hash closes the stream.</p>
 *
 * <p><strong>Receiving.</strong> A {@code FILE_OFFER} is surfaced to the
 * {@link Listener} for the user to accept or reject; nothing is written until
 * {@link #accept(String)}. An accepted file is written to a temporary sibling with
 * an incremental SHA-256, and only on a {@code FILE_END} whose hash matches is it
 * atomically renamed into place - so a truncated or tampered transfer never lands
 * as a finished file. The received name is hardened against path traversal
 * ("Zip-Slip") exactly like the Backup app: it is reduced to a base name, resolved
 * under the download directory and normalised, and rejected if it escapes.</p>
 *
 * <p>Listener callbacks fire on the reader thread (for inbound events) or the send
 * worker (for outbound progress); as with the rest of the transport, the caller
 * marshals to the EDT before touching any widget. Instances are thread-safe.</p>
 */
public final class FileTransferManager {

    /** The DATA-frame chunk size: 64 KiB. */
    public static final int CHUNK_BYTES = 64 * 1024;

    /** How often the receiver echoes confirmed progress back to the sender. */
    static final int PROGRESS_EVERY_BYTES = 256 * 1024;

    private static final String HASH = "SHA-256";
    private static final String DEFAULT_NAME = "download";

    /** Observes transfer lifecycle events (on the reader/send thread, not the EDT). */
    public interface Listener {
        /** A peer offered us a file; call {@link #accept}/{@link #reject} to respond. */
        void onOffer(FileTransfer transfer);

        /** Bytes moved; {@link FileTransfer#getProgress()} is updated. */
        void onProgress(FileTransfer transfer);

        /** The transfer finished and (for a receive) verified. */
        void onCompleted(FileTransfer transfer);

        /** The transfer was rejected or cancelled; see {@link FileTransfer#getState()}. */
        void onCancelled(FileTransfer transfer);

        /** The transfer failed (I/O error or checksum mismatch). */
        void onFailed(FileTransfer transfer);
    }

    private final SecureChannel channel;
    private final String localNickname;
    private final Path downloadDir;
    private final Listener listener;

    private final Map<String, FileTransfer> transfers = new ConcurrentHashMap<>();
    private final Map<String, Receiver> receivers = new ConcurrentHashMap<>();

    /**
     * Binds a manager to a channel.
     *
     * @param channel        the secure channel to send/receive over (never null)
     * @param localNickname  our display name, stamped into control messages
     * @param downloadDir    where accepted files are written (never null)
     * @param listener       the lifecycle sink (may be null)
     */
    public FileTransferManager(SecureChannel channel, String localNickname, Path downloadDir,
                               Listener listener) {
        if (channel == null || downloadDir == null) {
            throw new IllegalArgumentException("channel and downloadDir are required");
        }
        this.channel = channel;
        this.localNickname = (localNickname == null) ? "" : localNickname;
        this.downloadDir = downloadDir;
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Offers a local file to the peer. Computes its size and SHA-256, sends a
     * {@code FILE_OFFER}, and returns the pending transfer; streaming begins
     * automatically when the peer accepts.
     *
     * @param file the file to send
     * @return the new transfer, never null
     * @throws IOException if the file is missing/unreadable or cannot be hashed
     */
    public FileTransfer offerFile(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("Not a readable file: " + file);
        }
        long size = Files.size(file);
        String hash = sha256Hex(file);
        String id = UUID.randomUUID().toString();
        String name = (file.getFileName() == null) ? DEFAULT_NAME : file.getFileName().toString();
        FileTransfer transfer = new FileTransfer(id, FileTransfer.Direction.SEND, name, size, hash);
        transfer.setLocalPath(file);
        transfers.put(id, transfer);
        channel.sendControl(P2pMessage.fileOffer(localNickname, id, name, size, hash));
        return transfer;
    }

    private void startSend(FileTransfer transfer) {
        Thread t = new Thread(() -> streamFile(transfer), "p2p-send-" + transfer.getId());
        t.setDaemon(true);
        t.start();
    }

    private void streamFile(FileTransfer transfer) {
        transfer.start();
        Path source = transfer.getLocalPath();
        try (InputStream in = Files.newInputStream(source)) {
            byte[] buf = new byte[CHUNK_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (transfer.getState() == FileTransfer.State.CANCELLED || !channel.isOpen()) {
                    return; // cancelled mid-send, or the link dropped
                }
                byte[] chunk = (n == buf.length) ? buf.clone() : Arrays.copyOf(buf, n);
                channel.sendData(encodeChunk(transfer.getId(), chunk));
                transfer.addBytes(n);
                fireProgress(transfer);
            }
            if (transfer.getState() == FileTransfer.State.CANCELLED || !channel.isOpen()) {
                return;
            }
            channel.sendControl(P2pMessage.fileEnd(localNickname, transfer.getId(),
                    transfer.getExpectedHash()));
            if (transfer.complete(transfer.getExpectedHash())) {
                fireCompleted(transfer);
            }
        } catch (IOException ex) {
            if (transfer.fail("Send failed: " + ex.getMessage())) {
                fireFailed(transfer);
            }
        }
    }

    // ------------------------------------------------------------------
    // Receiving controls
    // ------------------------------------------------------------------

    /**
     * Accepts an offered file: prepares a temporary destination under the download
     * directory and sends {@code FILE_ACCEPT}. Bytes are written as they arrive and
     * atomically renamed into place on a verified {@code FILE_END}.
     *
     * @param transferId the offered transfer's id
     */
    public void accept(String transferId) {
        FileTransfer transfer = transfers.get(transferId);
        if (transfer == null || transfer.getDirection() != FileTransfer.Direction.RECEIVE
                || !transfer.accept()) {
            return;
        }
        try {
            Path target = uniqueTarget(safeResolve(downloadDir, transfer.getFileName()));
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = target.resolveSibling(target.getFileName() + "." + transferId + ".part");
            Receiver r = new Receiver(transfer, temp, target,
                    Files.newOutputStream(temp), MessageDigest.getInstance(HASH));
            receivers.put(transferId, r);
            transfer.setLocalPath(target);
            transfer.start();
            channel.sendControl(P2pMessage.fileAccept(localNickname, transferId));
        } catch (IOException | GeneralSecurityException ex) {
            transfer.fail("Could not start receive: " + ex.getMessage());
            channel.sendControl(P2pMessage.fileReject(localNickname, transferId, ex.getMessage()));
            fireFailed(transfer);
        }
    }

    /**
     * Rejects an offered file and tells the peer why.
     *
     * @param transferId the offered transfer's id
     * @param reason     a human-readable reason (may be null)
     */
    public void reject(String transferId, String reason) {
        FileTransfer transfer = transfers.get(transferId);
        if (transfer != null && transfer.getDirection() == FileTransfer.Direction.RECEIVE
                && transfer.reject(reason)) {
            channel.sendControl(P2pMessage.fileReject(localNickname, transferId, reason));
            fireCancelled(transfer);
        }
    }

    /**
     * Cancels an in-flight transfer on either side, cleaning up any partial file.
     *
     * @param transferId the transfer's id
     * @param reason     a human-readable reason (may be null)
     */
    public void cancel(String transferId, String reason) {
        FileTransfer transfer = transfers.get(transferId);
        if (transfer == null) {
            return;
        }
        if (transfer.cancel(reason)) {
            Receiver r = receivers.remove(transferId);
            if (r != null) {
                closeAndDelete(r);
            }
            channel.sendControl(P2pMessage.fileCancel(localNickname, transferId, reason));
            fireCancelled(transfer);
        }
    }

    /** Tears down every in-flight transfer (used when the channel closes). */
    public void close() {
        for (Receiver r : receivers.values()) {
            closeAndDelete(r);
        }
        receivers.clear();
        for (FileTransfer t : transfers.values()) {
            t.cancel("Channel closed");
        }
    }

    // ------------------------------------------------------------------
    // Inbound dispatch (driven by the channel listener)
    // ------------------------------------------------------------------

    /**
     * Routes an inbound CONTROL message to the file-transfer state machine. Non-file
     * kinds are ignored, so a caller can forward every control message here.
     *
     * @param msg the message (null is ignored)
     */
    public void handleControl(P2pMessage msg) {
        if (msg == null) {
            return;
        }
        switch (msg.getKind()) {
            case FILE_OFFER -> {
                FileTransfer t = new FileTransfer(msg.getTransferId(),
                        FileTransfer.Direction.RECEIVE, msg.getFileName(), msg.getFileSize(),
                        msg.getFileHash());
                transfers.put(t.getId(), t);
                fireOffer(t);
            }
            case FILE_ACCEPT -> {
                FileTransfer t = transfers.get(msg.getTransferId());
                if (t != null && t.getDirection() == FileTransfer.Direction.SEND && t.accept()) {
                    fireProgress(t);
                    startSend(t);
                }
            }
            case FILE_REJECT -> {
                FileTransfer t = transfers.get(msg.getTransferId());
                if (t != null && t.reject(msg.getText())) {
                    fireCancelled(t);
                }
            }
            case FILE_PROGRESS -> {
                FileTransfer t = transfers.get(msg.getTransferId());
                if (t != null && t.getDirection() == FileTransfer.Direction.SEND) {
                    t.setBytesTransferred(msg.getBytesDone());
                    fireProgress(t);
                }
            }
            case FILE_END -> finishReceive(msg);
            case FILE_CANCEL -> {
                FileTransfer t = transfers.get(msg.getTransferId());
                if (t != null && t.cancel(msg.getText())) {
                    Receiver r = receivers.remove(t.getId());
                    if (r != null) {
                        closeAndDelete(r);
                    }
                    fireCancelled(t);
                }
            }
            default -> {
                // Not a file-transfer signal.
            }
        }
    }

    /**
     * Writes one inbound DATA chunk to its transfer's temporary file.
     *
     * @param payload the DATA-frame payload ({@code [idLen][transferId][chunk]})
     */
    public void handleData(byte[] payload) {
        ChunkPayload cp;
        try {
            cp = decodeChunk(payload);
        } catch (IOException ex) {
            return; // malformed chunk; ignore rather than drop the link
        }
        Receiver r = receivers.get(cp.transferId());
        if (r == null) {
            return; // unknown, rejected or already-finished transfer
        }
        try {
            r.out.write(cp.chunk());
            r.digest.update(cp.chunk());
            r.transfer.addBytes(cp.chunk().length);
            long done = r.transfer.getBytesTransferred();
            if (done - r.lastProgressBytes >= PROGRESS_EVERY_BYTES) {
                r.lastProgressBytes = done;
                channel.sendControl(P2pMessage.fileProgress(localNickname, r.transfer.getId(), done));
            }
            fireProgress(r.transfer);
        } catch (IOException ex) {
            receivers.remove(cp.transferId());
            closeAndDelete(r);
            if (r.transfer.fail("Write failed: " + ex.getMessage())) {
                fireFailed(r.transfer);
            }
        }
    }

    private void finishReceive(P2pMessage msg) {
        Receiver r = receivers.remove(msg.getTransferId());
        if (r == null) {
            return;
        }
        try {
            r.out.close();
            String actual = P2pCrypto.toHex(r.digest.digest());
            boolean matchesEnd = (msg.getFileHash() == null)
                    || P2pCrypto.fingerprintsMatch(msg.getFileHash(), actual);
            boolean matchesOffer = (r.transfer.getExpectedHash() == null)
                    || P2pCrypto.fingerprintsMatch(r.transfer.getExpectedHash(), actual);
            if (!matchesEnd || !matchesOffer) {
                Files.deleteIfExists(r.tempPath);
                if (r.transfer.fail("Checksum mismatch: the file was corrupted in transit")) {
                    fireFailed(r.transfer);
                }
                return;
            }
            moveToTarget(r.tempPath, r.targetPath);
            r.transfer.setLocalPath(r.targetPath);
            if (r.transfer.complete(actual)) {
                fireCompleted(r.transfer);
            }
        } catch (IOException ex) {
            closeAndDelete(r);
            if (r.transfer.fail("Receive failed: " + ex.getMessage())) {
                fireFailed(r.transfer);
            }
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** The transfer for {@code id}, or null. */
    public FileTransfer getTransfer(String id) {
        return (id == null) ? null : transfers.get(id);
    }

    /** A snapshot of every transfer this manager has seen. */
    public List<FileTransfer> getTransfers() {
        return new ArrayList<>(transfers.values());
    }

    /** The directory accepted files are written to. */
    public Path getDownloadDir() {
        return downloadDir;
    }

    // ------------------------------------------------------------------
    // DATA payload codec: [2-byte id length][transferId][chunk]
    // ------------------------------------------------------------------

    /** A decoded DATA payload: which transfer it belongs to and the chunk bytes. */
    record ChunkPayload(String transferId, byte[] chunk) {
    }

    static byte[] encodeChunk(String transferId, byte[] chunk) {
        byte[] id = (transferId == null ? "" : transferId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = (chunk == null) ? new byte[0] : chunk;
        byte[] out = new byte[2 + id.length + body.length];
        out[0] = (byte) (id.length >>> 8);
        out[1] = (byte) id.length;
        System.arraycopy(id, 0, out, 2, id.length);
        System.arraycopy(body, 0, out, 2 + id.length, body.length);
        return out;
    }

    static ChunkPayload decodeChunk(byte[] payload) throws IOException {
        if (payload == null || payload.length < 2) {
            throw new IOException("chunk payload too short");
        }
        int idLen = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        if (idLen < 0 || 2 + idLen > payload.length) {
            throw new IOException("bad chunk id length: " + idLen);
        }
        String id = new String(payload, 2, idLen, java.nio.charset.StandardCharsets.UTF_8);
        byte[] chunk = Arrays.copyOfRange(payload, 2 + idLen, payload.length);
        return new ChunkPayload(id, chunk);
    }

    // ------------------------------------------------------------------
    // Path hardening (mirrors the Backup app's Zip-Slip guard)
    // ------------------------------------------------------------------

    /**
     * Reduces a received name to a safe base name: strips any directory components
     * (defeating {@code ../} traversal), removes control characters, and falls back
     * to {@value #DEFAULT_NAME} for a blank or dot name.
     *
     * @param fileName the offered name (may be null)
     * @return a single, safe path component
     */
    static String sanitizeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return DEFAULT_NAME;
        }
        String n = fileName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        n = n.replaceAll("[\\x00-\\x1f]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            return DEFAULT_NAME;
        }
        return n;
    }

    /**
     * Resolves a received name under {@code dir}, hardened against path traversal
     * the same way the Backup app guards a restore: normalise the base, resolve the
     * sanitised name, normalise, and refuse any result that escapes the base.
     *
     * @param dir      the download directory
     * @param fileName the offered name
     * @return a safe absolute path inside {@code dir}
     * @throws IOException if the name would escape the directory
     */
    static Path safeResolve(Path dir, String fileName) throws IOException {
        Path base = dir.toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path resolved = base.resolve(sanitizeName(fileName)).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("Refusing unsafe path (traversal): " + fileName);
        }
        return resolved;
    }

    /** Returns {@code target}, or a numbered sibling if it already exists. */
    static Path uniqueTarget(Path target) {
        if (!Files.exists(target)) {
            return target;
        }
        Path dir = (target.getParent() == null) ? Path.of(".") : target.getParent();
        String name = target.getFileName().toString();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 10_000; i++) {
            Path candidate = dir.resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return dir.resolve(base + "-" + System.nanoTime() + ext);
    }

    private static void moveToTarget(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[CHUNK_BYTES];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return P2pCrypto.toHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
    }

    private void closeAndDelete(Receiver r) {
        try {
            r.out.close();
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            Files.deleteIfExists(r.tempPath);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    // ------------------------------------------------------------------
    // Listener fan-out (null-safe)
    // ------------------------------------------------------------------

    private void fireOffer(FileTransfer t) {
        if (listener != null) {
            listener.onOffer(t);
        }
    }

    private void fireProgress(FileTransfer t) {
        if (listener != null) {
            listener.onProgress(t);
        }
    }

    private void fireCompleted(FileTransfer t) {
        if (listener != null) {
            listener.onCompleted(t);
        }
    }

    private void fireCancelled(FileTransfer t) {
        if (listener != null) {
            listener.onCancelled(t);
        }
    }

    private void fireFailed(FileTransfer t) {
        if (listener != null) {
            listener.onFailed(t);
        }
    }

    /** The mutable receive-side I/O state for one in-flight transfer. */
    private static final class Receiver {
        final FileTransfer transfer;
        final Path tempPath;
        final Path targetPath;
        final OutputStream out;
        final MessageDigest digest;
        long lastProgressBytes;

        Receiver(FileTransfer transfer, Path tempPath, Path targetPath, OutputStream out,
                 MessageDigest digest) {
            this.transfer = transfer;
            this.tempPath = tempPath;
            this.targetPath = targetPath;
            this.out = out;
            this.digest = digest;
        }
    }
}
