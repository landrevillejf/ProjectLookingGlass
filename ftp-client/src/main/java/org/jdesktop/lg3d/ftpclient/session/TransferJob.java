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
package org.jdesktop.lg3d.ftpclient.session;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * One queued file transfer: a direction (upload/download), the local and remote
 * paths, and the live progress/state the transfer-queue table renders.
 *
 * <p>A job is a mutable, thread-safe status object rather than the transfer
 * itself: the {@link TransferManager} runs it (usually on a worker thread) and
 * updates the {@code volatile} counters here, while the Swing table model reads
 * them on the EDT. Cancellation is cooperative - {@link #requestCancel()} sets a
 * flag the manager's {@link org.jdesktop.lg3d.ftpclient.net.ProgressListener}
 * polls, so an in-flight copy stops at the next buffer boundary.</p>
 */
public final class TransferJob {

    /** Which way the bytes move. */
    public enum Direction {
        /** Local file to remote path. */
        UPLOAD,
        /** Remote path to local file. */
        DOWNLOAD
    }

    /** Lifecycle states, in the order a job normally moves through them. */
    public enum State {
        /** Waiting in the queue. */
        QUEUED,
        /** Currently transferring. */
        RUNNING,
        /** Finished successfully. */
        COMPLETED,
        /** Finished with an error after all retries. */
        FAILED,
        /** Stopped by the user before completing. */
        CANCELLED
    }

    private final String id = UUID.randomUUID().toString();
    private final Direction direction;
    private final Path localFile;
    private final String remotePath;

    private volatile long totalBytes = -1L;
    private volatile long transferredBytes;
    private volatile State state = State.QUEUED;
    private volatile String message = "";
    private volatile boolean cancelRequested;

    /**
     * Creates a job.
     *
     * @param direction  upload or download
     * @param localFile  the local file path
     * @param remotePath the absolute remote path
     */
    public TransferJob(Direction direction, Path localFile, String remotePath) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.localFile = Objects.requireNonNull(localFile, "localFile");
        this.remotePath = Objects.requireNonNull(remotePath, "remotePath");
    }

    /** @return the stable job id. */
    public String getId() {
        return id;
    }

    public Direction getDirection() {
        return direction;
    }

    public Path getLocalFile() {
        return localFile;
    }

    public String getRemotePath() {
        return remotePath;
    }

    /** @return the base file name shown in the queue table. */
    public String getDisplayName() {
        Path name = localFile.getFileName();
        return (name != null) ? name.toString() : localFile.toString();
    }

    /** @return the expected total size in bytes, or {@code -1} when unknown. */
    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    /** @return bytes transferred so far (including any resumed prefix). */
    public long getTransferredBytes() {
        return transferredBytes;
    }

    public void setTransferredBytes(long transferredBytes) {
        this.transferredBytes = transferredBytes;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = (state == null) ? State.QUEUED : state;
    }

    /** @return a human-readable status/error message; never {@code null}. */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = (message == null) ? "" : message;
    }

    /**
     * Asks the manager to stop this job at the next safe point. Idempotent.
     */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /** @return {@code true} once cancellation has been requested. */
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /** @return {@code true} while the job has not reached a terminal state. */
    public boolean isActive() {
        return state == State.QUEUED || state == State.RUNNING;
    }

    /**
     * The completion fraction in {@code [0.0, 1.0]}, or {@code 0} when the total
     * size is unknown.
     *
     * @return the progress fraction
     */
    public double getProgressFraction() {
        if (state == State.COMPLETED) {
            return 1.0;
        }
        if (totalBytes <= 0) {
            return 0.0;
        }
        double f = (double) transferredBytes / (double) totalBytes;
        return Math.max(0.0, Math.min(1.0, f));
    }

    @Override
    public String toString() {
        return direction + " " + getDisplayName() + " [" + state + "]";
    }
}
