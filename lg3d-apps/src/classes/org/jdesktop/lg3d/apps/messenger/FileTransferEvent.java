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

/**
 * An immutable, protocol-neutral snapshot of one file transfer, delivered by a
 * {@link MessengerProtocol} to its {@link ProtocolListener} whenever a transfer
 * changes state or makes progress. Like {@link ChatMessage}, it normalises a
 * backend's wire model (for the native P2P transport, a
 * {@code org.jdesktop.lg3d.apps.p2p.FileTransfer}) into one value type so the UI
 * never has to know which protocol a transfer came from.
 *
 * <p>{@code transferId} correlates every event for the same exchange, so the UI
 * can update one progress row in place rather than adding a new one. {@code peer}
 * is the conversation the transfer belongs to (the same target a chat message
 * uses), letting the panel attach the row to the right conversation. {@code
 * localPath} is the source file for a {@link Direction#SEND} and the written
 * destination for a {@link Direction#RECEIVE} (null until known).</p>
 *
 * <p>Instances are handed to the listener on the protocol's I/O thread, never the
 * Swing EDT, so the panel marshals to the EDT before touching any widget.</p>
 */
public final class FileTransferEvent {

    /** Which way the bytes flow. */
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

    private final String transferId;
    private final String peer;
    private final String fileName;
    private final long fileSize;
    private final long bytesTransferred;
    private final Direction direction;
    private final State state;
    private final String localPath;
    private final String message;
    private final boolean verified;
    private final long epochMs;

    public FileTransferEvent(String transferId, String peer, String fileName, long fileSize,
                             long bytesTransferred, Direction direction, State state,
                             String localPath, String message, boolean verified) {
        this(transferId, peer, fileName, fileSize, bytesTransferred, direction, state,
                localPath, message, verified, System.currentTimeMillis());
    }

    public FileTransferEvent(String transferId, String peer, String fileName, long fileSize,
                             long bytesTransferred, Direction direction, State state,
                             String localPath, String message, boolean verified, long epochMs) {
        this.transferId = (transferId == null) ? "" : transferId;
        this.peer = (peer == null) ? "" : peer;
        this.fileName = (fileName == null) ? "" : fileName;
        this.fileSize = Math.max(0, fileSize);
        this.bytesTransferred = Math.max(0, bytesTransferred);
        this.direction = (direction == null) ? Direction.RECEIVE : direction;
        this.state = (state == null) ? State.OFFERED : state;
        this.localPath = localPath;
        this.message = message;
        this.verified = verified;
        this.epochMs = epochMs;
    }

    public String getTransferId() { return transferId; }
    public String getPeer() { return peer; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public long getBytesTransferred() { return bytesTransferred; }
    public Direction getDirection() { return direction; }
    public State getState() { return state; }
    public String getLocalPath() { return localPath; }
    public String getMessage() { return message; }
    public long getEpochMs() { return epochMs; }

    /** True if a completed receive matched the sender's checksum. */
    public boolean isVerified() { return verified; }

    /** True once no further progress is possible. */
    public boolean isTerminal() { return state.isTerminal(); }

    /** True while the transfer is still awaiting a decision or moving bytes. */
    public boolean isActive() { return !state.isTerminal(); }

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

    @Override
    public String toString() {
        return "FileTransferEvent[" + direction + " " + fileName + " " + state + " "
                + bytesTransferred + "/" + fileSize + " peer=" + peer + "]";
    }
}
