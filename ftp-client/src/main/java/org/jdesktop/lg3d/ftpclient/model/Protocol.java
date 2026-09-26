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

/**
 * The wire protocols the client can speak to a remote site.
 *
 * <p>{@link #FTP} is the plaintext original and is retained only for legacy
 * servers: it sends the password and every byte in the clear, so the UI flags it
 * insecure. {@link #FTPS} wraps the same protocol in TLS (Apache Commons Net's
 * {@code FTPSClient}) and {@link #SFTP} is the SSH File Transfer Protocol (the
 * JSch fork's {@code ChannelSftp}) &mdash; note SFTP is a different protocol
 * from FTP that happens to share a name, running over an encrypted SSH channel
 * with server host-key verification.</p>
 */
public enum Protocol {

    /** Plaintext FTP (RFC 959). Insecure: credentials and data are not encrypted. */
    FTP("FTP", 21, false),

    /** FTP over TLS (explicit {@code AUTH TLS} by default). Secure. */
    FTPS("FTPS (FTP over TLS)", 21, true),

    /** SSH File Transfer Protocol. Secure; runs over an encrypted SSH channel. */
    SFTP("SFTP (SSH File Transfer)", 22, true);

    private final String displayName;
    private final int defaultPort;
    private final boolean secure;

    Protocol(String displayName, int defaultPort, boolean secure) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.secure = secure;
    }

    /** @return the human-readable label shown in the site dialog's protocol combo. */
    public String getDisplayName() {
        return displayName;
    }

    /** @return the conventional port for this protocol when a profile omits one. */
    public int getDefaultPort() {
        return defaultPort;
    }

    /** @return {@code true} when the protocol encrypts the session (FTPS, SFTP). */
    public boolean isSecure() {
        return secure;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Resolves a protocol from a stored or user-typed name, tolerating case and
     * the display labels.
     *
     * @param name a protocol name ({@code ftp}, {@code FTPS}, {@code sftp}, ...)
     * @return the matching {@link Protocol}, or {@code null} when unrecognized
     */
    public static Protocol fromName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        for (Protocol p : values()) {
            if (p.name().equalsIgnoreCase(trimmed)
                    || p.displayName.equalsIgnoreCase(trimmed)) {
                return p;
            }
        }
        return null;
    }
}
