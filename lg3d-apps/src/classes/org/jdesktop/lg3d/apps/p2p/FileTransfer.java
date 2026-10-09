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

import java.nio.file.Path;

/**
 * The pure state of one file transfer, in either direction. This is the model half
 * of the file-transfer feature: it holds the identity, name, size, expected
 * SHA-256, the byte count, and a small guarded state machine
 * ({@code OFFERED -> ACCEPTED -> IN_PROGRESS -> COMPLETED}, with
 * {@code REJECTED}/{@code CANCELLED}/{@code FAILED} as terminal off-ramps). It
 * performs no I/O and touches no socket - {@link FileTransferManager} drives it and
 * the UI observes it - so every transition is unit-testable headless.
 *
 * <p>Instances are thread-safe: the sending worker thread and the channel's reader
 * thread may both advance the same transfer, so the mutators are synchronised and
 * the frequently-read counters are {@code volatile}. Transition methods return
 * whether the move was legal, so a caller can tell a real state change from a
 * late or duplicate signal (for example a {@code FILE_END} after a cancel).</p>
 */
public final class FileTransfer {

    /** Which way the bytes flow for this transfer. */
    public enum Direction {
        /** We are sending the file. */
        SEND,
        /** We are receiving the file. */
        RECEIVE
    }

    /** The lifecycle state of a transfer. */
    public enum State {
        /** Offered, awaiting the peer's accept/reject. */
        OFFERED,
        /** Accepted, not yet streaming. */
        ACCEPTED,
        /** Bytes are flowing. */
        IN_PROGRESS,
        /** Finished and verified. */
        COMPLETED,
        /** The offer was declined. */
        REJECTED,
        /** Aborted by either side. */
        CANCELLED,
        /** Failed (I/O error or checksum mismatch). */
        FAILED;

        /** @return true for the states from which no further progress is possible. */
        public boolean isTerminal() {
            return this == COMPLETED || this == REJECTED || this == CANCELLED || this == FAILED;
        }
    }

    private final String id;
    private final Direction direction;
    private final String fileName;
    private final long fileSize;
    private final String expectedHash;
    private final long createdAtMs;

    private State state = State.OFFERED;
    private volatile long bytesTransferred;
    private volatile String actualHash;
    private volatile String message;
    private volatile Path localPath;

    /**
     * Creates a transfer.
     *
     * @param id           the correlation id shared by both peers
     * @param direction    send or receive
     * @param fileName     the offered file's base name
     * @param fileSize     the offered size in bytes
     * @param expectedHash the sender's SHA-256 (hex) used to verify the result
     */
    public FileTransfer(String id, Direction direction, String fileName, long fileSize,
                        String expectedHash) {
        this.id = (id == null) ? "" : id;
        this.direction = (direction == null) ? Direction.RECEIVE : direction;
        this.fileName = (fileName == null) ? "" : fileName;
        this.fileSize = Math.max(0, fileSize);
        this.expectedHash = expectedHash;
        this.createdAtMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    /** OFFERED &rarr; ACCEPTED. */
    public synchronized boolean accept() {
        if (state == State.OFFERED) {
            state = State.ACCEPTED;
            return true;
        }
        return false;
    }

    /** OFFERED &rarr; REJECTED, recording a reason. */
    public synchronized boolean reject(String reason) {
        if (state == State.OFFERED) {
            state = State.REJECTED;
            this.message = reason;
            return true;
        }
        return false;
    }

    /** ACCEPTED &rarr; IN_PROGRESS. */
    public synchronized boolean start() {
        if (state == State.ACCEPTED || state == State.OFFERED) {
            state = State.IN_PROGRESS;
            return true;
        }
        return false;
    }

    /** Any non-terminal state &rarr; COMPLETED, recording the verified hash. */
    public synchronized boolean complete(String hash) {
        if (!state.isTerminal()) {
            state = State.COMPLETED;
            this.actualHash = hash;
            this.bytesTransferred = fileSize;
            return true;
        }
        return false;
    }

    /** Any non-terminal state &rarr; CANCELLED, recording a reason. */
    public synchronized boolean cancel(String reason) {
        if (!state.isTerminal()) {
            state = State.CANCELLED;
            this.message = reason;
            return true;
        }
        return false;
    }

    /** Any non-terminal state &rarr; FAILED, recording a reason. */
    public synchronized boolean fail(String reason) {
        if (!state.isTerminal()) {
            state = State.FAILED;
            this.message = reason;
            return true;
        }
        return false;
    }

    /**
     * Adds to the transferred byte count (clamped at the file size).
     *
     * @param n the bytes just moved
     */
    public void addBytes(long n) {
        if (n <= 0) {
            return;
        }
        long updated = bytesTransferred + n;
        bytesTransferred = (fileSize > 0) ? Math.min(fileSize, updated) : updated;
    }

    /**
     * Sets the transferred byte count from a peer-confirmed value (the sender
     * reconciles with the receiver's {@code FILE_PROGRESS}).
     *
     * @param bytes the confirmed byte count
     */
    public void setBytesTransferred(long bytes) {
        this.bytesTransferred = (fileSize > 0) ? Math.min(fileSize, Math.max(0, bytes))
                : Math.max(0, bytes);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getId() { return id; }
    public Direction getDirection() { return direction; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public String getExpectedHash() { return expectedHash; }
    public long getCreatedAtMs() { return createdAtMs; }

    public synchronized State getState() { return state; }
    public synchronized boolean isTerminal() { return state.isTerminal(); }
    public synchronized String getMessage() { return message; }

    public long getBytesTransferred() { return bytesTransferred; }
    public String getActualHash() { return actualHash; }

    /** The local file: the source for a send, the written destination for a receive. */
    public Path getLocalPath() { return localPath; }
    public void setLocalPath(Path localPath) { this.localPath = localPath; }

    /**
     * The completion fraction in {@code [0, 1]}, or 0 when the size is unknown.
     *
     * @return the progress
     */
    public double getProgress() {
        if (fileSize <= 0) {
            return 0d;
        }
        double p = (double) bytesTransferred / (double) fileSize;
        return Math.max(0d, Math.min(1d, p));
    }

    /** True if the received content matched the offered SHA-256. */
    public boolean isVerified() {
        return state == State.COMPLETED && expectedHash != null
                && P2pCrypto.fingerprintsMatch(expectedHash, actualHash);
    }

    @Override
    public String toString() {
        return "FileTransfer[" + direction + " " + fileName + " " + state + " "
                + bytesTransferred + "/" + fileSize + "]";
    }
}
