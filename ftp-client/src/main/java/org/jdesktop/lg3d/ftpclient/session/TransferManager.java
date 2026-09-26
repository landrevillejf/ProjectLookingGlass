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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.net.ProgressListener;
import org.jdesktop.lg3d.ftpclient.net.RemoteClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the transfer queue and the reliability policy around each job: retry
 * with exponential backoff, resume from a partial destination, cooperative
 * cancellation, and progress reporting to {@link TransferListener}s.
 *
 * <p>This class is deliberately transport- and thread-agnostic. It runs a job
 * synchronously through {@link #run(TransferJob)} against whatever
 * {@link RemoteClient} its supplier currently returns, so the UI layer can drive
 * it from a {@code SwingWorker} (keeping the EDT free) while the tests call it
 * directly with a mocked client and a zero backoff. Nothing here touches Swing.</p>
 */
public final class TransferManager {

    private static final Logger LOG = LoggerFactory.getLogger(TransferManager.class);

    private final Supplier<RemoteClient> clientSupplier;
    private final List<TransferJob> queue = new CopyOnWriteArrayList<>();
    private final List<TransferListener> listeners = new CopyOnWriteArrayList<>();
    private volatile AppSettings settings;

    /**
     * Creates a manager that pulls the live client from a supplier.
     *
     * @param clientSupplier returns the connected client, or {@code null} when
     *                       there is no session
     * @param settings       the retry/backoff policy; {@code null} uses defaults
     */
    public TransferManager(Supplier<RemoteClient> clientSupplier, AppSettings settings) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
        this.settings = (settings == null) ? new AppSettings() : settings;
    }

    /** Adopts new settings (called when the user edits preferences). */
    public void setSettings(AppSettings settings) {
        this.settings = (settings == null) ? new AppSettings() : settings;
    }

    public AppSettings getSettings() {
        return settings;
    }

    public void addListener(TransferListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(TransferListener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------
    // queueing
    // ------------------------------------------------------------------

    /**
     * Queues a download of a remote file to a local path.
     *
     * @param remotePath the absolute remote file
     * @param localFile  the local destination
     * @return the queued job
     */
    public TransferJob enqueueDownload(String remotePath, Path localFile) {
        return enqueue(TransferJob.Direction.DOWNLOAD, localFile, remotePath);
    }

    /**
     * Queues an upload of a local file to a remote path.
     *
     * @param localFile  the local source
     * @param remotePath the absolute remote destination
     * @return the queued job
     */
    public TransferJob enqueueUpload(Path localFile, String remotePath) {
        return enqueue(TransferJob.Direction.UPLOAD, localFile, remotePath);
    }

    private TransferJob enqueue(TransferJob.Direction direction, Path localFile, String remotePath) {
        TransferJob job = new TransferJob(direction, localFile, remotePath);
        // Best-effort total size so the queue can show a percentage immediately.
        try {
            if (direction == TransferJob.Direction.UPLOAD && Files.isRegularFile(localFile)) {
                job.setTotalBytes(Files.size(localFile));
            }
        } catch (IOException e) {
            LOG.debug("Could not size local file {}", localFile, e);
        }
        queue.add(job);
        fireStateChanged(job);
        return job;
    }

    /** @return an immutable snapshot of the queue in insertion order. */
    public List<TransferJob> getQueue() {
        return List.copyOf(queue);
    }

    /** Removes finished jobs from the queue. */
    public void clearCompleted() {
        queue.removeIf(j -> !j.isActive());
    }

    /**
     * Requests cancellation of a job. A running job stops at its next buffer
     * boundary; a still-queued job is marked cancelled without ever starting.
     *
     * @param jobId the job id
     * @return {@code true} when a matching job was found
     */
    public boolean cancel(String jobId) {
        for (TransferJob job : queue) {
            if (job.getId().equals(jobId)) {
                job.requestCancel();
                if (job.getState() == TransferJob.State.QUEUED) {
                    setState(job, TransferJob.State.CANCELLED, "Cancelled before starting");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Runs every queued (not yet terminal) job in order. The UI calls this from a
     * worker thread; it is safe to call repeatedly.
     */
    public void processQueue() {
        for (TransferJob job : queue) {
            if (job.getState() == TransferJob.State.QUEUED) {
                run(job);
            }
        }
    }

    // ------------------------------------------------------------------
    // execution
    // ------------------------------------------------------------------

    /**
     * Runs a single job synchronously: retry-with-backoff around a resumable
     * transfer, honouring cancellation. This is the whole reliability policy in
     * one place and is the seam the tests exercise with a mocked client.
     *
     * @param job the job to run
     */
    public void run(TransferJob job) {
        Objects.requireNonNull(job, "job");
        RemoteClient client = clientSupplier.get();
        if (client == null || !client.isConnected()) {
            setState(job, TransferJob.State.FAILED, "Not connected");
            return;
        }
        if (job.isCancelRequested()) {
            setState(job, TransferJob.State.CANCELLED, "Cancelled");
            return;
        }
        setState(job, TransferJob.State.RUNNING, "Transferring");
        int maxAttempts = Math.max(1, settings.getRetryCount() + 1);
        IOException lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (job.isCancelRequested()) {
                setState(job, TransferJob.State.CANCELLED, "Cancelled");
                return;
            }
            try {
                long offset = resumeOffset(client, job);
                if (job.getTotalBytes() <= 0) {
                    job.setTotalBytes(expectedTotal(client, job, offset));
                }
                transfer(client, job, offset);
                setState(job, TransferJob.State.COMPLETED, "Done");
                return;
            } catch (IOException e) {
                lastError = e;
                if (job.isCancelRequested() || isCancellation(e)) {
                    setState(job, TransferJob.State.CANCELLED, "Cancelled");
                    return;
                }
                LOG.debug("Transfer attempt {} for {} failed: {}", attempt + 1, job, e.toString());
                if (attempt < maxAttempts - 1) {
                    job.setMessage("Retrying (" + (attempt + 2) + "/" + maxAttempts + ")");
                    fireStateChanged(job);
                    sleep(settings.backoffFor(attempt));
                }
            }
        }
        setState(job, TransferJob.State.FAILED,
                (lastError != null) ? String.valueOf(lastError.getMessage()) : "Failed");
    }

    private void transfer(RemoteClient client, TransferJob job, long offset) throws IOException {
        ProgressListener pl = new JobProgressListener(job);
        if (job.getDirection() == TransferJob.Direction.DOWNLOAD) {
            client.download(job.getRemotePath(), job.getLocalFile(), offset, pl);
        } else {
            client.upload(job.getLocalFile(), job.getRemotePath(), offset, pl);
        }
    }

    /**
     * How many leading bytes are already present at the destination, so a retry
     * or a restart resumes instead of starting over.
     */
    private long resumeOffset(RemoteClient client, TransferJob job) throws IOException {
        if (job.getDirection() == TransferJob.Direction.DOWNLOAD) {
            Path local = job.getLocalFile();
            if (Files.isRegularFile(local)) {
                return Files.size(local);
            }
            return 0L;
        }
        if (client.exists(job.getRemotePath())) {
            long remoteSize = client.size(job.getRemotePath());
            return Math.max(0L, remoteSize);
        }
        return 0L;
    }

    private long expectedTotal(RemoteClient client, TransferJob job, long offset) {
        try {
            if (job.getDirection() == TransferJob.Direction.DOWNLOAD) {
                long size = client.size(job.getRemotePath());
                return (size >= 0) ? size : offset;
            }
            if (Files.isRegularFile(job.getLocalFile())) {
                return Files.size(job.getLocalFile());
            }
        } catch (IOException e) {
            LOG.debug("Could not determine total size for {}", job, e);
        }
        return offset;
    }

    private static boolean isCancellation(IOException e) {
        String m = e.getMessage();
        return m != null && m.toLowerCase().contains("cancel");
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void setState(TransferJob job, TransferJob.State state, String message) {
        job.setState(state);
        job.setMessage(message);
        fireStateChanged(job);
    }

    private void fireStateChanged(TransferJob job) {
        for (TransferListener l : listeners) {
            l.jobStateChanged(job);
        }
    }

    private void fireProgress(TransferJob job) {
        for (TransferListener l : listeners) {
            l.jobProgress(job, job.getTransferredBytes(), job.getTotalBytes());
        }
    }

    /**
     * Adapts a job to the net layer's {@link ProgressListener}: forwards byte
     * counts to the UI listeners and reports the job's cancel flag so the
     * underlying copy loop can stop.
     */
    private final class JobProgressListener implements ProgressListener {

        private final TransferJob job;

        JobProgressListener(TransferJob job) {
            this.job = job;
        }

        @Override
        public void onProgress(long bytesTransferred) {
            job.setTransferredBytes(bytesTransferred);
            fireProgress(job);
        }

        @Override
        public boolean isCancelled() {
            return job.isCancelRequested();
        }
    }
}
