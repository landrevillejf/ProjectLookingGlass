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
package org.jdesktop.lg3d.ftpclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Application-wide preferences for the FTP Client, persisted as JSON by
 * {@link ProfileStore}.
 *
 * <p>Every field has a sane default so a fresh install (no settings file yet)
 * works out of the box and is secure by default: passwords are not saved, and a
 * transfer that fails is retried a few times with an exponential backoff before
 * it is surfaced as an error.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    /** Minimum accepted socket buffer size, in bytes. */
    public static final int MIN_BUFFER_SIZE = 4 * 1024;
    /** Maximum accepted socket buffer size, in bytes. */
    public static final int MAX_BUFFER_SIZE = 4 * 1024 * 1024;

    /** Control-channel connect/login timeout, in seconds; {@code 0} = OS default. */
    private int connectTimeoutSeconds = 15;
    /** Data-connection socket timeout, in seconds; {@code 0} = no timeout. */
    private int dataTimeoutSeconds = 30;
    /** Automatic retries attempted for a failed transfer before it is surfaced. */
    private int retryCount = 3;
    /** Base delay between retries, in milliseconds; doubles on each attempt. */
    private long retryBackoffMillis = 500L;
    /** Size of the buffered copy loop and the socket send/receive buffers. */
    private int bufferSize = 64 * 1024;
    /** Whether new FTP sites default to passive data connections. */
    private boolean passiveDefault = true;
    /** Ask before overwriting an existing file at the transfer destination. */
    private boolean confirmOverwrite = true;
    /** Whether profiles are allowed to persist (obfuscated) passwords. */
    private boolean allowSavePasswords = false;
    /** Show dot-files in the remote and local browsers. */
    private boolean showHiddenFiles = false;

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = Math.max(0, connectTimeoutSeconds);
    }

    public int getDataTimeoutSeconds() {
        return dataTimeoutSeconds;
    }

    public void setDataTimeoutSeconds(int dataTimeoutSeconds) {
        this.dataTimeoutSeconds = Math.max(0, dataTimeoutSeconds);
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = Math.max(0, retryCount);
    }

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = Math.max(0L, retryBackoffMillis);
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = Math.min(MAX_BUFFER_SIZE, Math.max(MIN_BUFFER_SIZE, bufferSize));
    }

    public boolean isPassiveDefault() {
        return passiveDefault;
    }

    public void setPassiveDefault(boolean passiveDefault) {
        this.passiveDefault = passiveDefault;
    }

    public boolean isConfirmOverwrite() {
        return confirmOverwrite;
    }

    public void setConfirmOverwrite(boolean confirmOverwrite) {
        this.confirmOverwrite = confirmOverwrite;
    }

    public boolean isAllowSavePasswords() {
        return allowSavePasswords;
    }

    public void setAllowSavePasswords(boolean allowSavePasswords) {
        this.allowSavePasswords = allowSavePasswords;
    }

    public boolean isShowHiddenFiles() {
        return showHiddenFiles;
    }

    public void setShowHiddenFiles(boolean showHiddenFiles) {
        this.showHiddenFiles = showHiddenFiles;
    }

    /**
     * The backoff delay before a given retry attempt: the base delay doubled
     * {@code attempt} times (attempt 0 is the first retry).
     *
     * @param attempt the zero-based retry index
     * @return the delay in milliseconds, never negative
     */
    public long backoffFor(int attempt) {
        int n = Math.max(0, attempt);
        // Cap the shift so a large retryCount cannot overflow into a negative.
        int shift = Math.min(n, 20);
        return retryBackoffMillis << shift;
    }
}
