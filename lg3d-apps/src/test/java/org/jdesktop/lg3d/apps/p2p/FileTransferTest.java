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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless unit tests for the pure halves of the file-transfer feature: the
 * {@link FileTransfer} state machine and the {@link FileTransferManager} static
 * helpers (the DATA-payload codec, the received-name sanitiser and the Zip-Slip
 * path guard). The streaming driver itself is exercised end-to-end in
 * {@code FileTransferManagerTest}.
 */
class FileTransferTest {

    // ------------------------------------------------------------------
    // FileTransfer state machine
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a transfer walks OFFERED -> ACCEPTED -> IN_PROGRESS -> COMPLETED")
    void happyPathStates() {
        FileTransfer t = new FileTransfer("id", FileTransfer.Direction.RECEIVE, "a.txt", 100, "hash");
        assertEquals(FileTransfer.State.OFFERED, t.getState());
        assertFalse(t.isTerminal());

        assertTrue(t.accept());
        assertEquals(FileTransfer.State.ACCEPTED, t.getState());
        // A second accept is refused (not idempotent state churn).
        assertFalse(t.accept());

        assertTrue(t.start());
        assertEquals(FileTransfer.State.IN_PROGRESS, t.getState());

        t.addBytes(40);
        assertEquals(40, t.getBytesTransferred());
        assertEquals(0.4d, t.getProgress(), 1e-9);

        assertTrue(t.complete("hash"));
        assertEquals(FileTransfer.State.COMPLETED, t.getState());
        assertTrue(t.isTerminal());
        assertEquals(100, t.getBytesTransferred(), "completion snaps to the full size");
        assertEquals(1.0d, t.getProgress(), 1e-9);
    }

    @Test
    @DisplayName("terminal states reject further transitions")
    void terminalStatesAreFinal() {
        FileTransfer t = new FileTransfer("id", FileTransfer.Direction.SEND, "a", 10, "h");
        assertTrue(t.complete("h"));
        assertFalse(t.accept());
        assertFalse(t.start());
        assertFalse(t.cancel("late"));
        assertFalse(t.fail("late"));
        assertFalse(t.reject("late"));
        assertEquals(FileTransfer.State.COMPLETED, t.getState());
    }

    @Test
    @DisplayName("reject, cancel and fail record a reason and go terminal")
    void offRamps() {
        FileTransfer rejected = new FileTransfer("1", FileTransfer.Direction.RECEIVE, "a", 1, "h");
        assertTrue(rejected.reject("no thanks"));
        assertEquals(FileTransfer.State.REJECTED, rejected.getState());
        assertEquals("no thanks", rejected.getMessage());
        assertTrue(rejected.isTerminal());

        FileTransfer cancelled = new FileTransfer("2", FileTransfer.Direction.SEND, "b", 1, "h");
        assertTrue(cancelled.cancel("user aborted"));
        assertEquals(FileTransfer.State.CANCELLED, cancelled.getState());
        assertEquals("user aborted", cancelled.getMessage());

        FileTransfer failed = new FileTransfer("3", FileTransfer.Direction.RECEIVE, "c", 1, "h");
        failed.start();
        assertTrue(failed.fail("checksum mismatch"));
        assertEquals(FileTransfer.State.FAILED, failed.getState());
    }

    @Test
    @DisplayName("start() is allowed straight from OFFERED for the sending side")
    void startFromOffered() {
        FileTransfer t = new FileTransfer("id", FileTransfer.Direction.SEND, "a", 5, "h");
        assertTrue(t.start());
        assertEquals(FileTransfer.State.IN_PROGRESS, t.getState());
    }

    @Test
    @DisplayName("byte accounting clamps to the size and reconciles from the peer")
    void byteAccounting() {
        FileTransfer t = new FileTransfer("id", FileTransfer.Direction.RECEIVE, "a", 100, "h");
        t.addBytes(60);
        t.addBytes(60); // would overshoot
        assertEquals(100, t.getBytesTransferred(), "clamped at the file size");
        t.addBytes(0);
        t.addBytes(-5);
        assertEquals(100, t.getBytesTransferred());
        t.setBytesTransferred(42);
        assertEquals(42, t.getBytesTransferred());
        t.setBytesTransferred(9999);
        assertEquals(100, t.getBytesTransferred());
        t.setBytesTransferred(-3);
        assertEquals(0, t.getBytesTransferred());
    }

    @Test
    @DisplayName("progress is 0 for an unknown size and never exceeds 1")
    void progressBounds() {
        FileTransfer unknown = new FileTransfer("id", FileTransfer.Direction.RECEIVE, "a", 0, "h");
        assertEquals(0d, unknown.getProgress(), 1e-9);
        FileTransfer t = new FileTransfer("id", FileTransfer.Direction.RECEIVE, "a", 10, "h");
        t.addBytes(3);
        assertEquals(0.3d, t.getProgress(), 1e-9);
    }

    @Test
    @DisplayName("isVerified requires COMPLETED and a matching hash")
    void verification() {
        FileTransfer ok = new FileTransfer("id", FileTransfer.Direction.RECEIVE, "a", 4, "ABC123");
        assertFalse(ok.isVerified());
        ok.complete("abc123"); // case-insensitive hex match
        assertTrue(ok.isVerified());

        FileTransfer bad = new FileTransfer("id", FileTransfer.Direction.RECEIVE, "a", 4, "ABC123");
        bad.complete("ffffff");
        assertFalse(bad.isVerified());
    }

    @Test
    @DisplayName("the constructor null-guards its inputs")
    void constructorGuards() {
        FileTransfer t = new FileTransfer(null, null, null, -5, null);
        assertEquals("", t.getId());
        assertEquals(FileTransfer.Direction.RECEIVE, t.getDirection());
        assertEquals("", t.getFileName());
        assertEquals(0, t.getFileSize());
        assertTrue(t.getCreatedAtMs() > 0);
        assertTrue(t.toString().contains("RECEIVE"));
    }

    // ------------------------------------------------------------------
    // DATA payload codec
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the chunk payload codec round-trips id and bytes")
    void chunkCodecRoundTrip() throws IOException {
        byte[] chunk = new byte[]{1, 2, 3, 4, 5};
        byte[] encoded = FileTransferManager.encodeChunk("tx-42", chunk);
        FileTransferManager.ChunkPayload cp = FileTransferManager.decodeChunk(encoded);
        assertEquals("tx-42", cp.transferId());
        assertArrayEquals(chunk, cp.chunk());

        // An empty chunk and a unicode id both survive.
        FileTransferManager.ChunkPayload empty =
                FileTransferManager.decodeChunk(FileTransferManager.encodeChunk("id", new byte[0]));
        assertEquals("id", empty.transferId());
        assertEquals(0, empty.chunk().length);
        FileTransferManager.ChunkPayload uni = FileTransferManager.decodeChunk(
                FileTransferManager.encodeChunk("tr\u00e4nsfer", chunk));
        assertEquals("tr\u00e4nsfer", uni.transferId());
    }

    @Test
    @DisplayName("a malformed chunk payload is rejected")
    void chunkCodecRejectsMalformed() {
        assertThrows(IOException.class, () -> FileTransferManager.decodeChunk(null));
        assertThrows(IOException.class, () -> FileTransferManager.decodeChunk(new byte[]{0}));
        // An id length that runs past the end of the payload.
        assertThrows(IOException.class,
                () -> FileTransferManager.decodeChunk(new byte[]{(byte) 0xFF, (byte) 0xFF, 1}));
    }

    // ------------------------------------------------------------------
    // Name sanitising and Zip-Slip guard
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sanitizeName strips directories, traversal and control chars")
    void sanitizesNames() {
        assertEquals("report.pdf", FileTransferManager.sanitizeName("report.pdf"));
        assertEquals("evil.txt", FileTransferManager.sanitizeName("../../etc/evil.txt"));
        assertEquals("evil.txt", FileTransferManager.sanitizeName("..\\..\\windows\\evil.txt"));
        assertEquals("evil.txt", FileTransferManager.sanitizeName("/absolute/evil.txt"));
        assertEquals("download", FileTransferManager.sanitizeName(null));
        assertEquals("download", FileTransferManager.sanitizeName("   "));
        assertEquals("download", FileTransferManager.sanitizeName(".."));
        assertEquals("download", FileTransferManager.sanitizeName("."));
        assertEquals("download", FileTransferManager.sanitizeName("/"));
        // Control characters are scrubbed.
        assertEquals("a_b", FileTransferManager.sanitizeName("a\u0000b"));
    }

    @Test
    @DisplayName("safeResolve keeps a normal name inside and rejects traversal")
    void safeResolveGuards(@TempDir Path dir) throws IOException {
        Path safe = FileTransferManager.safeResolve(dir, "notes.txt");
        assertTrue(safe.startsWith(dir.toAbsolutePath().normalize()));
        assertEquals("notes.txt", safe.getFileName().toString());

        // A traversal name is reduced to its base component and stays inside.
        Path sneaky = FileTransferManager.safeResolve(dir, "../../../etc/passwd");
        assertTrue(sneaky.startsWith(dir.toAbsolutePath().normalize()),
                "the resolved path must not escape the download dir: " + sneaky);
        assertEquals("passwd", sneaky.getFileName().toString());
    }

    @Test
    @DisplayName("uniqueTarget returns a numbered sibling when the name is taken")
    void uniqueTargetAvoidsClobber(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("movie.mkv");
        // A free name is returned unchanged.
        assertEquals(target, FileTransferManager.uniqueTarget(target));

        Files.writeString(target, "existing");
        Path alt = FileTransferManager.uniqueTarget(target);
        assertEquals("movie-1.mkv", alt.getFileName().toString());
        assertFalse(Files.exists(alt));

        Files.writeString(alt, "also existing");
        Path alt2 = FileTransferManager.uniqueTarget(target);
        assertEquals("movie-2.mkv", alt2.getFileName().toString());

        // A name with no extension still gets a numeric suffix.
        Path plain = dir.resolve("README");
        Files.writeString(plain, "x");
        assertEquals("README-1", FileTransferManager.uniqueTarget(plain).getFileName().toString());
    }
}
