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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.jdesktop.lg3d.ftpclient.model.TransferMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The FTP / FTPS {@link RemoteClient}, built on Apache Commons Net's
 * {@link FTPClient} (plaintext) and {@link FTPSClient} (TLS).
 *
 * <p>Security: an FTPS site negotiates TLS on the control channel
 * ({@code AUTH TLS} for the explicit form on port 21, or an implicit handshake
 * on port 990) and then requires {@code PROT P} so the data channel is also
 * encrypted; if the server will not protect the data channel the connection is
 * refused rather than silently downgraded. A plaintext FTP site is allowed for
 * legacy servers but the UI flags it insecure.</p>
 *
 * <p>Reliability: transfers set a data-connection timeout, run in binary mode,
 * and support resume via {@code REST} (the {@code offset} argument), so an
 * interrupted download/upload continues instead of restarting. Listing falls
 * back to {@link ListParser} for any line Commons Net's own parsers reject.</p>
 */
public final class FtpRemoteClient implements RemoteClient {

    private static final Logger LOG = LoggerFactory.getLogger(FtpRemoteClient.class);

    private final SiteProfile profile;
    private final AppSettings settings;
    private final boolean secure;
    private final FTPClient client;

    /**
     * Creates an unconnected client for a site.
     *
     * @param profile  the site to connect to (host/port/protocol/credentials)
     * @param settings the timeouts, buffer size and default transfer mode
     */
    public FtpRemoteClient(SiteProfile profile, AppSettings settings) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.settings = (settings == null) ? new AppSettings() : settings;
        this.secure = profile.getProtocol() == Protocol.FTPS;
        // Port 990 is the conventional implicit-FTPS port; anything else (21) is
        // explicit AUTH TLS.
        boolean implicit = secure && profile.resolvePort() == 990;
        this.client = secure ? new FTPSClient(implicit) : new FTPClient();
    }

    @Override
    public void connect() throws IOException {
        if (settings.getConnectTimeoutSeconds() > 0) {
            client.setConnectTimeout(settings.getConnectTimeoutSeconds() * 1000);
        }
        String enc = profile.getEncoding();
        if (enc != null && !enc.isBlank()) {
            client.setControlEncoding(enc);
        }
        client.connect(profile.getHost(), profile.resolvePort());
        int reply = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            client.disconnect();
            throw new IOException("FTP server refused the connection (reply " + reply + ")");
        }
        // For an explicit-FTPS site, FTPSClient.connect() has already issued AUTH
        // TLS and negotiated the control-channel handshake; an implicit site (port
        // 990) handshook during connect() too. Nothing to do here.
        String user = (profile.getUser() == null || profile.getUser().isBlank())
                ? "anonymous" : profile.getUser();
        String pass = (profile.getPassword() == null) ? "anonymous@" : profile.getPassword();
        if (!client.login(user, pass)) {
            client.disconnect();
            throw new IOException("Login failed for user '" + user + "'");
        }
        if (secure && client instanceof FTPSClient ftps) {
            ftps.execPBSZ(0);
            try {
                // execPROT throws when the server will not protect the data channel.
                ftps.execPROT("P");
            } catch (IOException e) {
                try {
                    client.logout();
                } catch (IOException ignore) {
                    // Best effort before dropping the socket.
                }
                client.disconnect();
                throw new IOException("Server would not protect the data channel (PROT P)", e);
            }
        }
        client.setFileType(FTP.BINARY_FILE_TYPE);
        client.setBufferSize(settings.getBufferSize());
        if (settings.getDataTimeoutSeconds() > 0) {
            client.setDataTimeout(Duration.ofSeconds(settings.getDataTimeoutSeconds()));
        }
        applyTransferMode();
        String dir = profile.getRemoteDir();
        if (dir != null && !dir.isBlank()) {
            changeDirectory(dir);
        }
        LOG.debug("Connected to {}://{}:{}", getProtocol(), profile.getHost(), profile.resolvePort());
    }

    @Override
    public void disconnect() {
        try {
            if (client.isConnected()) {
                try {
                    client.logout();
                } catch (IOException ignore) {
                    // A failed QUIT must not stop us from dropping the socket.
                }
                client.disconnect();
            }
        } catch (IOException e) {
            LOG.debug("Ignoring error while disconnecting", e);
        }
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public Protocol getProtocol() {
        return profile.getProtocol();
    }

    @Override
    public String getWorkingDirectory() throws IOException {
        String dir = client.printWorkingDirectory();
        return (dir == null || dir.isEmpty()) ? RemotePaths.SEPARATOR : dir;
    }

    @Override
    public void changeDirectory(String path) throws IOException {
        if (!client.changeWorkingDirectory(RemotePaths.normalize(path))) {
            throw new IOException("Cannot enter remote directory: " + path
                    + " (" + client.getReplyString() + ")");
        }
    }

    @Override
    public List<RemoteEntry> list(String path) throws IOException {
        applyTransferMode();
        FTPFile[] files = client.listFiles(RemotePaths.normalize(path));
        List<RemoteEntry> out = new ArrayList<>();
        for (FTPFile f : files) {
            RemoteEntry e = toEntry(f);
            if (e != null && !e.isSelfOrParent()) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public void makeDirectory(String path) throws IOException {
        if (!client.makeDirectory(path)) {
            throw new IOException("Cannot create remote directory: " + path
                    + " (" + client.getReplyString() + ")");
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        if (!client.rename(from, to)) {
            throw new IOException("Cannot rename " + from + " to " + to
                    + " (" + client.getReplyString() + ")");
        }
    }

    @Override
    public void delete(String path) throws IOException {
        if (!client.deleteFile(path)) {
            throw new IOException("Cannot delete remote file: " + path
                    + " (" + client.getReplyString() + ")");
        }
    }

    @Override
    public void removeDirectory(String path) throws IOException {
        if (!client.removeDirectory(path)) {
            throw new IOException("Cannot remove remote directory: " + path
                    + " (" + client.getReplyString() + ")");
        }
    }

    @Override
    public boolean exists(String path) throws IOException {
        String base = RemotePaths.baseName(path);
        if (base.isEmpty()) {
            return true;
        }
        applyTransferMode();
        for (FTPFile f : client.listFiles(RemotePaths.parent(path))) {
            if (base.equals(f.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public long size(String path) throws IOException {
        String base = RemotePaths.baseName(path);
        applyTransferMode();
        for (FTPFile f : client.listFiles(RemotePaths.parent(path))) {
            if (base.equals(f.getName())) {
                return f.isDirectory() ? RemoteEntry.UNKNOWN_SIZE : f.getSize();
            }
        }
        return RemoteEntry.UNKNOWN_SIZE;
    }

    @Override
    public void download(String remotePath, Path localFile, long offset, ProgressListener listener)
            throws IOException {
        long start = Math.max(0L, offset);
        applyTransferMode();
        client.setRestartOffset(start);
        try {
            InputStream in = client.retrieveFileStream(remotePath);
            if (in == null) {
                throw new IOException("Server refused RETR " + remotePath
                        + " (" + client.getReplyString() + ")");
            }
            OpenOption[] opts = (start > 0)
                    ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND}
                    : new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING};
            Path parent = localFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(localFile, opts)) {
                copy(in, out, start, listener);
            } finally {
                in.close();
            }
            if (!client.completePendingCommand()) {
                throw new IOException("Download did not complete: " + client.getReplyString());
            }
        } finally {
            client.setRestartOffset(0);
        }
    }

    @Override
    public void upload(Path localFile, String remotePath, long offset, ProgressListener listener)
            throws IOException {
        long start = Math.max(0L, offset);
        applyTransferMode();
        client.setRestartOffset(start);
        try (InputStream in = Files.newInputStream(localFile)) {
            if (start > 0) {
                in.skipNBytes(start);
            }
            OutputStream out = client.storeFileStream(remotePath);
            if (out == null) {
                throw new IOException("Server refused STOR " + remotePath
                        + " (" + client.getReplyString() + ")");
            }
            try {
                copy(in, out, start, listener);
            } finally {
                out.close();
            }
            if (!client.completePendingCommand()) {
                throw new IOException("Upload did not complete: " + client.getReplyString());
            }
        } finally {
            client.setRestartOffset(0);
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void applyTransferMode() {
        if (profile.getTransferMode() == TransferMode.PASSIVE) {
            client.enterLocalPassiveMode();
        } else {
            client.enterLocalActiveMode();
        }
    }

    private void copy(InputStream in, OutputStream out, long startOffset, ProgressListener listener)
            throws IOException {
        byte[] buffer = new byte[Math.max(1024, settings.getBufferSize())];
        long total = startOffset;
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (listener != null && listener.isCancelled()) {
                throw new IOException("Transfer cancelled");
            }
            out.write(buffer, 0, n);
            total += n;
            if (listener != null) {
                listener.onProgress(total);
            }
        }
        out.flush();
    }

    private RemoteEntry toEntry(FTPFile f) {
        if (f == null) {
            return null;
        }
        String name = f.getName();
        if (name == null) {
            // Commons Net could not parse this line; retry with our own parser on
            // the untouched raw listing before giving up on the row.
            String raw = f.getRawListing();
            return (raw == null) ? null : ListParser.parse(raw);
        }
        boolean dir = f.isDirectory();
        long size = dir ? RemoteEntry.UNKNOWN_SIZE : f.getSize();
        long mtime = (f.getTimestamp() != null) ? f.getTimestamp().getTimeInMillis() : 0L;
        String link = f.isSymbolicLink() ? f.getLink() : null;
        return new RemoteEntry(name, size, mtime, dir, permissions(f), link);
    }

    private static String permissions(FTPFile f) {
        char[] p = new char[9];
        int[] access = {FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS};
        int i = 0;
        for (int a : access) {
            p[i++] = f.hasPermission(a, FTPFile.READ_PERMISSION) ? 'r' : '-';
            p[i++] = f.hasPermission(a, FTPFile.WRITE_PERMISSION) ? 'w' : '-';
            p[i++] = f.hasPermission(a, FTPFile.EXECUTE_PERMISSION) ? 'x' : '-';
        }
        return new String(p);
    }
}
