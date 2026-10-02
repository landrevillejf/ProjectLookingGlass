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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A file the browser has fetched (or is fetching): the source URL, the local
 * file name it was saved under, the transfer size, a {@link Status} and the
 * timestamp.
 *
 * <p>JavaFX's {@code WebView} has no full download manager, so the browser
 * intercepts navigations that resolve to a non-renderable content type, saves the
 * bytes with the JDK {@code java.net.http} client and records the result here.
 * The record is honest about the outcome: a {@link Status#FAILED} entry keeps the
 * error rather than pretending the file arrived.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DownloadRecord {

    /** The lifecycle of a download. */
    public enum Status {
        /** Fetching bytes. */
        IN_PROGRESS,
        /** Saved to disk successfully. */
        COMPLETE,
        /** The transfer failed or was cancelled; {@code error} explains why. */
        FAILED,
        /** The user cancelled the transfer. */
        CANCELLED
    }

    private String url = "";
    private String fileName = "";
    /** Absolute path the file was saved to (empty until known/complete). */
    private String path = "";
    private long bytes;
    private Status status = Status.IN_PROGRESS;
    private String error = "";
    private long startedAt = System.currentTimeMillis();

    public DownloadRecord() {
    }

    /**
     * Creates an in-progress download record.
     *
     * @param url      the source URL (may be null)
     * @param fileName the target file name (may be null)
     */
    public DownloadRecord(String url, String fileName) {
        this.url = (url == null) ? "" : url;
        this.fileName = (fileName == null) ? "" : fileName;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = (url == null) ? "" : url; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = (fileName == null) ? "" : fileName; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = (path == null) ? "" : path; }

    public long getBytes() { return bytes; }
    public void setBytes(long bytes) { this.bytes = Math.max(0L, bytes); }

    public Status getStatus() { return (status == null) ? Status.IN_PROGRESS : status; }
    public void setStatus(Status status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = (error == null) ? "" : error; }

    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    /** Marks the record complete with a saved path and byte count. */
    public void markComplete(String savedPath, long size) {
        this.path = (savedPath == null) ? "" : savedPath;
        this.bytes = Math.max(0L, size);
        this.status = Status.COMPLETE;
        this.error = "";
    }

    /** Marks the record failed with a human-readable reason. */
    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.error = (reason == null) ? "" : reason;
    }

    /** @return an independent copy of this record. */
    public DownloadRecord copy() {
        DownloadRecord d = new DownloadRecord();
        d.url = this.url;
        d.fileName = this.fileName;
        d.path = this.path;
        d.bytes = this.bytes;
        d.status = this.status;
        d.error = this.error;
        d.startedAt = this.startedAt;
        return d;
    }

    @Override
    public String toString() {
        return (fileName == null || fileName.isEmpty()) ? url : fileName;
    }
}
