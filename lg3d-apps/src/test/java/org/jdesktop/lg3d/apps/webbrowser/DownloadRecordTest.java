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
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link DownloadRecord} bean. */
class DownloadRecordTest {

    @Test
    @DisplayName("a new record starts in progress with empty url/name")
    void defaults() {
        DownloadRecord d = new DownloadRecord();
        assertEquals(DownloadRecord.Status.IN_PROGRESS, d.getStatus());
        assertEquals("", d.getUrl());
        assertEquals("", d.getFileName());
        assertEquals(0L, d.getBytes());
        assertEquals("", d.getError());
        assertTrue(d.getStartedAt() > 0L);
    }

    @Test
    @DisplayName("the two-arg constructor records url and file name")
    void constructor() {
        DownloadRecord d = new DownloadRecord("https://a.com/f.zip", "f.zip");
        assertEquals("https://a.com/f.zip", d.getUrl());
        assertEquals("f.zip", d.getFileName());
        assertEquals("f.zip", d.toString());
        DownloadRecord noName = new DownloadRecord("https://a.com/f.zip", null);
        assertEquals("https://a.com/f.zip", noName.toString(),
                "a blank name falls back to the URL");
    }

    @Test
    @DisplayName("setBytes never stores a negative size")
    void bytesClamped() {
        DownloadRecord d = new DownloadRecord();
        d.setBytes(-100L);
        assertEquals(0L, d.getBytes());
        d.setBytes(2048L);
        assertEquals(2048L, d.getBytes());
    }

    @Test
    @DisplayName("markComplete records the path and size and clears the error")
    void markComplete() {
        DownloadRecord d = new DownloadRecord("https://a.com/f.zip", "f.zip");
        d.setError("stale");
        d.markComplete("/home/user/Downloads/f.zip", 4096L);
        assertEquals(DownloadRecord.Status.COMPLETE, d.getStatus());
        assertEquals("/home/user/Downloads/f.zip", d.getPath());
        assertEquals(4096L, d.getBytes());
        assertEquals("", d.getError());
    }

    @Test
    @DisplayName("markFailed records the reason")
    void markFailed() {
        DownloadRecord d = new DownloadRecord("https://a.com/f.zip", "f.zip");
        d.markFailed("HTTP 404");
        assertEquals(DownloadRecord.Status.FAILED, d.getStatus());
        assertEquals("HTTP 404", d.getError());
        d.markFailed(null);
        assertEquals("", d.getError());
    }

    @Test
    @DisplayName("copy produces an independent equal-valued instance")
    void copyIsIndependent() {
        DownloadRecord d = new DownloadRecord("https://a.com/f.zip", "f.zip");
        d.markComplete("/tmp/f.zip", 10L);
        DownloadRecord copy = d.copy();
        assertNotSame(d, copy);
        assertEquals(d.getUrl(), copy.getUrl());
        assertEquals(d.getPath(), copy.getPath());
        assertEquals(d.getBytes(), copy.getBytes());
        assertEquals(d.getStatus(), copy.getStatus());
        copy.markFailed("changed");
        assertEquals(DownloadRecord.Status.COMPLETE, d.getStatus(), "the original is untouched");
    }

    @Test
    @DisplayName("updateProgress records bytes/total while in progress")
    void progress() {
        DownloadRecord d = new DownloadRecord("https://a.com/f.zip", "f.zip");
        d.updateProgress(1024L, 4096L);
        assertEquals(1024L, d.getBytes());
        assertEquals(4096L, d.getTotalBytes());
        assertEquals(DownloadRecord.Status.IN_PROGRESS, d.getStatus());
    }

    @Test
    @DisplayName("updateProgress clamps negatives and never resurrects a terminal state")
    void progressGuards() {
        DownloadRecord d = new DownloadRecord("u", "f");
        d.updateProgress(-5L, -9L);
        assertEquals(0L, d.getBytes());
        assertEquals(0L, d.getTotalBytes());
        d.markComplete("/tmp/f", 10L);
        d.updateProgress(1L, 2L);
        assertEquals(DownloadRecord.Status.COMPLETE, d.getStatus(),
                "a late progress callback cannot un-finish a download");
    }

    @Test
    @DisplayName("markCancelled sets CANCELLED and clears the error")
    void cancelled() {
        DownloadRecord d = new DownloadRecord("u", "f");
        d.setError("stale");
        d.markCancelled();
        assertEquals(DownloadRecord.Status.CANCELLED, d.getStatus());
        assertEquals("", d.getError());
    }

    @Test
    @DisplayName("setTotalBytes clamps negatives and copy carries it")
    void totalBytesCopy() {
        DownloadRecord d = new DownloadRecord("u", "f");
        d.setTotalBytes(-1L);
        assertEquals(0L, d.getTotalBytes());
        d.setTotalBytes(2048L);
        assertEquals(2048L, d.copy().getTotalBytes());
    }
}
