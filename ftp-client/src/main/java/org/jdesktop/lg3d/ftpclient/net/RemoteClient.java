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
package org.jdesktop.lg3d.ftpclient.net;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.ftpclient.model.Protocol;

/**
 * The protocol-neutral remote-file operations the session and UI layers use, so
 * the same browser, transfer queue and resume logic work over FTP, FTPS and SFTP.
 *
 * <p>An implementation is built by {@link RemoteClientFactory} from a
 * {@code SiteProfile} and is <strong>not</strong> thread-safe: a single session
 * owns one client and the UI serializes access through a {@code SwingWorker}.
 * Every method that touches the network throws {@link IOException} on failure so
 * the caller can retry, resume or surface the error; none of them may block the
 * EDT (that is the caller's responsibility).</p>
 *
 * <p>Path arguments are absolute remote paths using {@code /} separators. The
 * {@code offset} on {@link #download} / {@link #upload} is the number of leading
 * bytes already present at the destination, so a resumed transfer continues from
 * there (FTP {@code REST} / SFTP skip-or-append) instead of restarting.</p>
 */
public interface RemoteClient extends AutoCloseable {

    /**
     * Connects and authenticates using the profile this client was built with.
     *
     * @throws IOException when the server is unreachable or the login is rejected
     */
    void connect() throws IOException;

    /** Logs out and releases the connection; safe to call when not connected. */
    void disconnect();

    /** @return {@code true} while the control connection is established. */
    boolean isConnected();

    /** @return the protocol this client speaks. */
    Protocol getProtocol();

    /**
     * Resolves the current remote working directory.
     *
     * @return the absolute path of the working directory
     * @throws IOException on a protocol error
     */
    String getWorkingDirectory() throws IOException;

    /**
     * Changes the remote working directory.
     *
     * @param path the absolute directory to enter
     * @throws IOException when the directory does not exist or is not accessible
     */
    void changeDirectory(String path) throws IOException;

    /**
     * Lists a remote directory.
     *
     * @param path the absolute directory to list
     * @return the entries (excluding {@code .} and {@code ..}), never {@code null}
     * @throws IOException on a protocol error
     */
    List<RemoteEntry> list(String path) throws IOException;

    /**
     * Creates a remote directory.
     *
     * @param path the absolute directory to create
     * @throws IOException when it cannot be created
     */
    void makeDirectory(String path) throws IOException;

    /**
     * Renames or moves a remote path.
     *
     * @param from the current absolute path
     * @param to   the new absolute path
     * @throws IOException on a protocol error
     */
    void rename(String from, String to) throws IOException;

    /**
     * Deletes a remote file.
     *
     * @param path the absolute file path
     * @throws IOException on a protocol error
     */
    void delete(String path) throws IOException;

    /**
     * Removes a remote directory.
     *
     * @param path the absolute directory path
     * @throws IOException on a protocol error
     */
    void removeDirectory(String path) throws IOException;

    /**
     * Reports whether a remote path exists.
     *
     * @param path the absolute path
     * @return {@code true} when the server reports the path
     * @throws IOException on a protocol error other than "not found"
     */
    boolean exists(String path) throws IOException;

    /**
     * Reports the size of a remote file.
     *
     * @param path the absolute file path
     * @return the size in bytes, or {@link RemoteEntry#UNKNOWN_SIZE}
     * @throws IOException on a protocol error
     */
    long size(String path) throws IOException;

    /**
     * Downloads a remote file to a local path.
     *
     * @param remotePath the absolute remote file
     * @param localFile  the local destination
     * @param offset     leading bytes already present locally, to resume from
     * @param listener   progress/cancel callback, or {@code null}
     * @throws IOException on a protocol or local I/O error
     */
    void download(String remotePath, Path localFile, long offset, ProgressListener listener)
            throws IOException;

    /**
     * Uploads a local file to a remote path.
     *
     * @param localFile  the local source
     * @param remotePath the absolute remote destination
     * @param offset     leading bytes already present remotely, to resume from
     * @param listener   progress/cancel callback, or {@code null}
     * @throws IOException on a protocol or local I/O error
     */
    void upload(Path localFile, String remotePath, long offset, ProgressListener listener)
            throws IOException;

    /**
     * Releases the connection. Overrides {@link AutoCloseable#close()} to drop
     * the checked exception so clients can be closed in a {@code finally}.
     */
    @Override
    void close();
}
