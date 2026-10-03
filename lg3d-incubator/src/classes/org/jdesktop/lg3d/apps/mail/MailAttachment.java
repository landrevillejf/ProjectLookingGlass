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
package org.jdesktop.lg3d.apps.mail;

import java.io.File;

/**
 * One attachment on a {@link MailMessage}: its file name, MIME type and size,
 * plus (optionally) the bytes.
 *
 * <p>For an <em>incoming</em> message the metadata is filled while the envelope is
 * listed but {@link #getData()} stays {@code null} until the body is opened or the
 * attachment is explicitly downloaded, so a folder full of large attachments does
 * not blow up the list view. For an <em>outgoing</em> draft the bytes (or a
 * {@link File} to read them from) are set when the user adds the attachment.</p>
 */
public final class MailAttachment {

    private final String fileName;
    private final String mimeType;
    private long size;
    private byte[] data;
    private File source;

    public MailAttachment(String fileName, String mimeType, long size) {
        this.fileName = (fileName == null || fileName.isEmpty())
                ? "attachment" : fileName;
        this.mimeType = (mimeType == null || mimeType.isEmpty())
                ? "application/octet-stream" : mimeType;
        this.size = size;
    }

    /** Builds an outgoing attachment backed by a file on disk. */
    public static MailAttachment fromFile(File file) {
        MailAttachment a = new MailAttachment(file.getName(),
                guessMimeType(file.getName()), file.length());
        a.source = file;
        return a;
    }

    /** Builds an outgoing attachment from in-memory bytes. */
    public static MailAttachment fromBytes(String fileName, String mimeType,
            byte[] bytes) {
        MailAttachment a = new MailAttachment(fileName, mimeType,
                bytes == null ? 0 : bytes.length);
        a.data = bytes;
        return a;
    }

    /** A conservative, dependency-free extension -> MIME type guess. */
    static String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    /** The downloaded bytes, or {@code null} when not yet fetched. */
    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
        if (data != null) {
            this.size = data.length;
        }
    }

    public boolean hasData() {
        return data != null;
    }

    /** The local file an outgoing attachment reads its bytes from, if any. */
    public File getSource() {
        return source;
    }

    public void setSource(File source) {
        this.source = source;
    }

    /** A short human label: {@code name (12 KB)}. */
    public String describe() {
        return fileName + " (" + humanSize(size) + ")";
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public String toString() {
        return describe();
    }
}
