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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.net.ProgressListener;
import org.jdesktop.lg3d.ftpclient.net.RemoteClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the {@link TransferManager} reliability policy against a mocked
 * {@link RemoteClient} and a zero retry backoff, so it runs synchronously and
 * fast: queue order, success/failure, retry-with-backoff, resume offsets for
 * both directions, progress forwarding, and cooperative cancellation.
 */
class TransferManagerTest {

    @TempDir
    Path dir;

    private RemoteClient client;
    private AppSettings settings;
    private TransferManager transfers;

    @BeforeEach
    void setUp() {
        client = mock(RemoteClient.class);
        when(client.isConnected()).thenReturn(true);
        settings = new AppSettings();
        settings.setRetryBackoffMillis(0); // keep retry loops instantaneous
        settings.setRetryCount(0);
        // The manager holds this same settings instance, so tests can retune it live.
        transfers = new TransferManager(() -> client, settings);
    }

    // ------------------------------------------------------------------
    // queueing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the queue preserves insertion order and direction")
    void queueOrder() throws IOException {
        Path a = dir.resolve("a.txt");
        Path b = newLocalFile("b.txt", "bbbb");
        Path c = dir.resolve("c.txt");
        transfers.enqueueDownload("/remote/a", a);
        transfers.enqueueUpload(b, "/remote/b");
        transfers.enqueueDownload("/remote/c", c);

        List<TransferJob> q = transfers.getQueue();
        assertThat(q).hasSize(3);
        assertThat(q).extracting(TransferJob::getDirection).containsExactly(
                TransferJob.Direction.DOWNLOAD, TransferJob.Direction.UPLOAD,
                TransferJob.Direction.DOWNLOAD);
        assertThat(q).extracting(TransferJob::getRemotePath)
                .containsExactly("/remote/a", "/remote/b", "/remote/c");
        // An upload seeds its total from the local file size for an immediate percentage.
        assertThat(q.get(1).getTotalBytes()).isEqualTo(4L);
        assertThat(q.get(0).getState()).isEqualTo(TransferJob.State.QUEUED);
    }

    @Test
    @DisplayName("getQueue is an immutable snapshot")
    void queueSnapshot() {
        transfers.enqueueDownload("/remote/a", dir.resolve("a"));
        List<TransferJob> q = transfers.getQueue();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, q::clear);
    }

    // ------------------------------------------------------------------
    // success / failure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a download that succeeds completes and calls the client at offset 0")
    void runCompletesDownload() throws IOException {
        TransferJob job = transfers.enqueueDownload("/remote/f.txt", dir.resolve("f.txt"));
        transfers.run(job);
        assertThat(job.getState()).isEqualTo(TransferJob.State.COMPLETED);
        assertThat(job.getMessage()).isEqualTo("Done");
        verify(client).download(eq("/remote/f.txt"), eq(dir.resolve("f.txt")), eq(0L), any());
    }

    @Test
    @DisplayName("processQueue runs only the queued jobs")
    void processQueue() throws IOException {
        TransferJob job = transfers.enqueueDownload("/remote/f.txt", dir.resolve("f.txt"));
        transfers.processQueue();
        assertThat(job.getState()).isEqualTo(TransferJob.State.COMPLETED);
        verify(client, times(1)).download(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("run fails immediately when there is no connected client")
    void runFailsWhenNotConnected() {
        TransferManager noClient = new TransferManager(() -> null, settings);
        TransferJob job = noClient.enqueueDownload("/remote/f", dir.resolve("f"));
        noClient.run(job);
        assertThat(job.getState()).isEqualTo(TransferJob.State.FAILED);
        assertThat(job.getMessage()).isEqualTo("Not connected");
    }

    @Test
    @DisplayName("run fails when the client reports a dropped connection")
    void runFailsWhenDisconnected() {
        when(client.isConnected()).thenReturn(false);
        TransferJob job = transfers.enqueueDownload("/remote/f", dir.resolve("f"));
        transfers.run(job);
        assertThat(job.getState()).isEqualTo(TransferJob.State.FAILED);
    }

    // ------------------------------------------------------------------
    // retry with backoff
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a transient failure is retried and then completes")
    void retryThenSucceeds() throws IOException {
        settings.setRetryCount(2); // three attempts total
        doThrow(new IOException("transient"))
                .doThrow(new IOException("transient"))
                .doNothing()
                .when(client).download(anyString(), any(Path.class), anyLong(), any());

        TransferJob job = transfers.enqueueDownload("/remote/f", dir.resolve("f"));
        transfers.run(job);
        assertThat(job.getState()).isEqualTo(TransferJob.State.COMPLETED);
        verify(client, times(3)).download(anyString(), any(Path.class), anyLong(), any());
    }

    @Test
    @DisplayName("exhausting the retries fails with the last error message")
    void retryExhaustedFails() throws IOException {
        settings.setRetryCount(1); // two attempts total
        doThrow(new IOException("boom"))
                .when(client).download(anyString(), any(Path.class), anyLong(), any());

        TransferJob job = transfers.enqueueDownload("/remote/f", dir.resolve("f"));
        transfers.run(job);
        assertThat(job.getState()).isEqualTo(TransferJob.State.FAILED);
        assertThat(job.getMessage()).isEqualTo("boom");
        verify(client, times(2)).download(anyString(), any(Path.class), anyLong(), any());
    }

    // ------------------------------------------------------------------
    // resume
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a download resumes from the existing local file size")
    void downloadResumesFromLocalSize() throws IOException {
        Path partial = newLocalFile("partial.bin", "12345"); // 5 bytes already present
        TransferJob job = transfers.enqueueDownload("/remote/f", partial);
        transfers.run(job);
        verify(client).download(eq("/remote/f"), eq(partial), eq(5L), any());
        assertThat(job.getState()).isEqualTo(TransferJob.State.COMPLETED);
    }

    @Test
    @DisplayName("an upload resumes from the remote file size")
    void uploadResumesFromRemoteSize() throws IOException {
        Path local = newLocalFile("whole.bin", "0123456789"); // 10 bytes
        when(client.exists("/remote/f")).thenReturn(true);
        when(client.size("/remote/f")).thenReturn(7L); // 7 already uploaded

        TransferJob job = transfers.enqueueUpload(local, "/remote/f");
        transfers.run(job);
        verify(client).upload(eq(local), eq("/remote/f"), eq(7L), any());
        assertThat(job.getState()).isEqualTo(TransferJob.State.COMPLETED);
    }

    // ------------------------------------------------------------------
    // progress
    // ------------------------------------------------------------------

    @Test
    @DisplayName("byte progress is forwarded to listeners and recorded on the job")
    void progressCallbacks() throws IOException {
        doAnswer(inv -> {
            ProgressListener pl = inv.getArgument(3);
            pl.onProgress(40L);
            pl.onProgress(100L);
            return null;
        }).when(client).download(anyString(), any(Path.class), anyLong(), any());

        List<Long> seen = new ArrayList<>();
        TransferListener listener = new TransferListener() {
            @Override
            public void jobStateChanged(TransferJob job) {
                // Not exercised here.
            }

            @Override
            public void jobProgress(TransferJob job, long transferredBytes, long totalBytes) {
                seen.add(transferredBytes);
            }
        };
        transfers.addListener(listener);

        TransferJob job = transfers.enqueueDownload("/remote/f", dir.resolve("f"));
        transfers.run(job);
        assertThat(seen).containsExactly(40L, 100L);
        assertThat(job.getTransferredBytes()).isEqualTo(100L);
    }

    @Test
    @DisplayName("a null listener is ignored and removeListener detaches")
    void listenerLifecycle() throws IOException {
        transfers.addListener(null); // must not throw
        List<TransferJob> states = new ArrayList<>();
        TransferListener listener = new RecordingListener(states);
        transfers.addListener(listener);
        transfers.removeListener(listener);

        TransferJob job = transfers.enqueueDownload("/remote/f", dir.resolve("f"));
        // enqueue fires one state event; the removed listener should not see the rest.
        assertThat(states).isEmpty();
        transfers.run(job);
        assertThat(states).isEmpty();
    }

    // ------------------------------------------------------------------
    // cancellation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancelling a queued job marks it cancelled without running")
    void cancelQueuedJob() {
        TransferJob job = transfers.enqueueDownload("/remote/f", dir.resolve("f"));
        assertThat(transfers.cancel(job.getId())).isTrue();
        assertThat(job.getState()).isEqualTo(TransferJob.State.CANCELLED);
        assertThat(transfers.cancel("not-a-real-id")).isFalse();
    }

    @Test
    @DisplayName("a job cancelled before run never touches the client")
    void cancelBeforeRun() throws IOException {
        TransferJob job = transfers.enqueueDownload("/remote/f", dir.resolve("f"));
        job.requestCancel();
        transfers.run(job);
        assertThat(job.getState()).isEqualTo(TransferJob.State.CANCELLED);
        verify(client, never()).download(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("a cancellation error from the client maps to CANCELLED, not a retry")
    void cancelDuringTransfer() throws IOException {
        settings.setRetryCount(3);
        doThrow(new IOException("Transfer cancelled"))
                .when(client).download(anyString(), any(Path.class), anyLong(), any());

        TransferJob job = transfers.enqueueDownload("/remote/f", dir.resolve("f"));
        transfers.run(job);
        assertThat(job.getState()).isEqualTo(TransferJob.State.CANCELLED);
        verify(client, times(1)).download(anyString(), any(Path.class), anyLong(), any());
    }

    // ------------------------------------------------------------------
    // housekeeping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearCompleted drops terminal jobs and keeps active ones")
    void clearCompleted() throws IOException {
        TransferJob done = transfers.enqueueDownload("/remote/a", dir.resolve("a"));
        TransferJob pending = transfers.enqueueDownload("/remote/b", dir.resolve("b"));
        transfers.run(done);
        assertThat(done.getState()).isEqualTo(TransferJob.State.COMPLETED);

        transfers.clearCompleted();
        assertThat(transfers.getQueue()).containsExactly(pending);
        assertThat(pending.getState()).isEqualTo(TransferJob.State.QUEUED);
    }

    @Test
    @DisplayName("setSettings retunes the live policy and null resets to defaults")
    void setSettings() {
        AppSettings s = new AppSettings();
        s.setRetryCount(9);
        transfers.setSettings(s);
        assertThat(transfers.getSettings()).isSameAs(s);
        transfers.setSettings(null);
        assertThat(transfers.getSettings()).isNotSameAs(s);
        assertThat(transfers.getSettings().getRetryCount()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Path newLocalFile(String name, String contents) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, contents, StandardCharsets.UTF_8);
        return p;
    }

    /** Records every state change so listener wiring can be asserted. */
    private static final class RecordingListener implements TransferListener {
        private final List<TransferJob> states;

        RecordingListener(List<TransferJob> states) {
            this.states = states;
        }

        @Override
        public void jobStateChanged(TransferJob job) {
            states.add(job);
        }

        @Override
        public void jobProgress(TransferJob job, long transferredBytes, long totalBytes) {
            // Not recorded.
        }
    }
}
