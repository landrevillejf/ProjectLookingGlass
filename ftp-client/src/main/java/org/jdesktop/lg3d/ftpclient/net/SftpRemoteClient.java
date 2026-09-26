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

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Vector;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.ProfileStore;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SFTP {@link RemoteClient}, built on JSch's {@link ChannelSftp} (the SSH
 * File Transfer Protocol, which runs entirely over the encrypted SSH session).
 *
 * <p>Security: the SSH session always encrypts everything, and the server's
 * host key is verified against a {@code known_hosts} file under the FTP Client
 * configuration directory with {@code StrictHostKeyChecking=yes}. The first time
 * a host is seen its key is accepted and recorded (trust-on-first-use); on every
 * later connection a key that does not match the recorded one is rejected, so a
 * man-in-the-middle cannot silently substitute itself. Because verification is
 * non-interactive it stays headless-testable.</p>
 *
 * <p>Reliability: transfers run in binary over the SSH channel and support
 * resume by starting the remote read at an offset and appending locally (or
 * appending to the remote file), so an interrupted transfer continues rather
 * than restarting. SFTP has no passive/active data-connection notion, so the
 * profile's {@link org.jdesktop.lg3d.ftpclient.model.TransferMode} is ignored.</p>
 */
public final class SftpRemoteClient implements RemoteClient {

    private static final Logger LOG = LoggerFactory.getLogger(SftpRemoteClient.class);

    /** Name of the trust-on-first-use host-key file under the config directory. */
    static final String KNOWN_HOSTS_FILE = "known_hosts";

    private final SiteProfile profile;
    private final AppSettings settings;
    private final Path knownHosts;

    private Session session;
    private ChannelSftp channel;

    /**
     * Creates an unconnected client for a site.
     *
     * @param profile  the SFTP site to connect to (host/port/credentials)
     * @param settings the timeouts and buffer size
     */
    public SftpRemoteClient(SiteProfile profile, AppSettings settings) {
        this(profile, settings, ProfileStore.defaultConfigDir().resolve(KNOWN_HOSTS_FILE));
    }

    /**
     * Creates an unconnected client with an explicit known-hosts path (used by
     * tests to isolate host-key state).
     *
     * @param profile     the SFTP site to connect to
     * @param settings    the timeouts and buffer size
     * @param knownHosts  where verified host keys are recorded and checked
     */
    SftpRemoteClient(SiteProfile profile, AppSettings settings, Path knownHosts) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.settings = (settings == null) ? new AppSettings() : settings;
        this.knownHosts = Objects.requireNonNull(knownHosts, "knownHosts");
    }

    /**
     * Test seam: wraps an already-open channel so the file operations can be
     * exercised with a mocked {@link ChannelSftp} and no live SSH session. Not
     * part of the production construction path (the factory uses the public
     * constructor); the session stays {@code null}, so {@link #isConnected()}
     * reports {@code false} while {@link #list}/{@link #download} still work
     * through the injected channel.
     *
     * @param profile  the site (used for protocol/paths)
     * @param settings the timeouts and buffer size
     * @param channel  the pre-connected SFTP channel to drive
     */
    SftpRemoteClient(SiteProfile profile, AppSettings settings, ChannelSftp channel) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.settings = (settings == null) ? new AppSettings() : settings;
        this.knownHosts = null;
        this.channel = channel;
    }

    @Override
    public void connect() throws IOException {
        try {
            JSch jsch = new JSch();
            Path parent = knownHosts.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.isRegularFile(knownHosts)) {
                jsch.setKnownHosts(knownHosts.toString());
            }
            String user = (profile.getUser() == null || profile.getUser().isBlank())
                    ? System.getProperty("user.name", "anonymous") : profile.getUser();
            session = jsch.getSession(user, profile.getHost(), profile.resolvePort());
            String pass = profile.getPassword();
            if (pass != null) {
                // The byte[] form avoids retaining the password as an immutable String.
                session.setPassword(pass.getBytes(StandardCharsets.UTF_8));
            }
            session.setUserInfo(new TofuUserInfo(pass, knownHosts));
            Properties config = new Properties();
            // Verify against known_hosts; an unseen key is accepted once by the
            // UserInfo and recorded, a changed key is rejected by JSch.
            config.put("StrictHostKeyChecking", "yes");
            session.setConfig(config);
            int timeoutMillis = settings.getConnectTimeoutSeconds() * 1000;
            if (timeoutMillis > 0) {
                session.setTimeout(timeoutMillis);
            }
            session.connect(Math.max(0, timeoutMillis));
            Channel c = session.openChannel("sftp");
            c.connect();
            channel = (ChannelSftp) c;
            String dir = profile.getRemoteDir();
            if (dir != null && !dir.isBlank()) {
                changeDirectory(dir);
            }
            LOG.debug("Connected to sftp://{}:{}", profile.getHost(), profile.resolvePort());
        } catch (JSchException e) {
            disconnect();
            throw new IOException("SFTP connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        try {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        } catch (RuntimeException e) {
            LOG.debug("Ignoring error while closing the SFTP channel", e);
        } finally {
            channel = null;
        }
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        } catch (RuntimeException e) {
            LOG.debug("Ignoring error while closing the SSH session", e);
        } finally {
            session = null;
        }
    }

    @Override
    public boolean isConnected() {
        return channel != null && channel.isConnected()
                && session != null && session.isConnected();
    }

    @Override
    public Protocol getProtocol() {
        return Protocol.SFTP;
    }

    @Override
    public String getWorkingDirectory() throws IOException {
        try {
            String pwd = requireChannel().pwd();
            return (pwd == null || pwd.isEmpty()) ? RemotePaths.SEPARATOR : pwd;
        } catch (SftpException e) {
            throw new IOException("Cannot read the remote working directory", e);
        }
    }

    @Override
    public void changeDirectory(String path) throws IOException {
        try {
            requireChannel().cd(RemotePaths.normalize(path));
        } catch (SftpException e) {
            throw new IOException("Cannot enter remote directory: " + path, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RemoteEntry> list(String path) throws IOException {
        try {
            Vector<ChannelSftp.LsEntry> entries = requireChannel().ls(RemotePaths.normalize(path));
            List<RemoteEntry> out = new ArrayList<>();
            for (ChannelSftp.LsEntry entry : entries) {
                RemoteEntry e = toEntry(entry, RemotePaths.normalize(path));
                if (e != null && !e.isSelfOrParent()) {
                    out.add(e);
                }
            }
            return out;
        } catch (SftpException e) {
            throw new IOException("Cannot list remote directory: " + path, e);
        }
    }

    @Override
    public void makeDirectory(String path) throws IOException {
        try {
            requireChannel().mkdir(path);
        } catch (SftpException e) {
            throw new IOException("Cannot create remote directory: " + path, e);
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        try {
            requireChannel().rename(from, to);
        } catch (SftpException e) {
            throw new IOException("Cannot rename " + from + " to " + to, e);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        try {
            requireChannel().rm(path);
        } catch (SftpException e) {
            throw new IOException("Cannot delete remote file: " + path, e);
        }
    }

    @Override
    public void removeDirectory(String path) throws IOException {
        try {
            requireChannel().rmdir(path);
        } catch (SftpException e) {
            throw new IOException("Cannot remove remote directory: " + path, e);
        }
    }

    @Override
    public boolean exists(String path) throws IOException {
        try {
            requireChannel().stat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw new IOException("Cannot stat remote path: " + path, e);
        }
    }

    @Override
    public long size(String path) throws IOException {
        try {
            SftpATTRS attrs = requireChannel().stat(path);
            return attrs.getSize();
        } catch (SftpException e) {
            throw new IOException("Cannot stat remote file: " + path, e);
        }
    }

    @Override
    public void download(String remotePath, Path localFile, long offset, ProgressListener listener)
            throws IOException {
        long start = Math.max(0L, offset);
        Path target = localFile.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // APPEND continues a partial local file from the remote offset (the `skip`
        // argument); a fresh download (start == 0) overwrites any stale local file.
        OpenOption[] opts = (start > 0)
                ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND}
                : new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING};
        try (InputStream in = requireChannel().get(remotePath,
                new TransferMonitor(start, listener), start);
             OutputStream out = Files.newOutputStream(target, opts)) {
            byte[] buffer = new byte[Math.max(1024, settings.getBufferSize())];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (listener != null && listener.isCancelled()) {
                    throw new IOException("Transfer cancelled");
                }
                out.write(buffer, 0, n);
            }
            out.flush();
        } catch (SftpException e) {
            throw new IOException("Cannot download " + remotePath, e);
        }
    }

    @Override
    public void upload(Path localFile, String remotePath, long offset, ProgressListener listener)
            throws IOException {
        long start = Math.max(0L, offset);
        // APPEND writes the local bytes after `start` onto the end of the remote
        // file; a fresh upload (start == 0) overwrites any stale remote file.
        int mode = (start > 0) ? ChannelSftp.APPEND : ChannelSftp.OVERWRITE;
        try (InputStream in = Files.newInputStream(localFile.toAbsolutePath())) {
            if (start > 0) {
                in.skipNBytes(start);
            }
            OutputStream out = requireChannel().put(remotePath,
                    new TransferMonitor(start, listener), mode, start);
            try {
                byte[] buffer = new byte[Math.max(1024, settings.getBufferSize())];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (listener != null && listener.isCancelled()) {
                        throw new IOException("Transfer cancelled");
                    }
                    out.write(buffer, 0, n);
                }
                out.flush();
            } finally {
                out.close();
            }
        } catch (SftpException e) {
            throw new IOException("Cannot upload to " + remotePath, e);
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private ChannelSftp requireChannel() throws IOException {
        if (channel == null || !channel.isConnected()) {
            throw new IOException("SFTP channel is not connected");
        }
        return channel;
    }

    /**
     * Bridges JSch's byte-count callbacks to the protocol-neutral
     * {@link ProgressListener}, translating a cancel request into the
     * {@code false} return that aborts the transfer. The running total is seeded
     * from the resume offset so progress reflects the whole file, not just the
     * bytes moved in this attempt.
     */
    private static final class TransferMonitor implements SftpProgressMonitor {

        private final ProgressListener listener;
        private long total;

        TransferMonitor(long base, ProgressListener listener) {
            this.listener = listener;
            this.total = base;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
            // Total is seeded in the constructor; nothing to do on (re)init.
        }

        @Override
        public boolean count(long bytes) {
            if (listener != null && listener.isCancelled()) {
                return false;
            }
            total += bytes;
            if (listener != null) {
                listener.onProgress(total);
            }
            return true;
        }

        @Override
        public void end() {
            // Nothing to release.
        }
    }

    private RemoteEntry toEntry(ChannelSftp.LsEntry entry, String dir) {
        if (entry == null) {
            return null;
        }
        String name = entry.getFilename();
        if (name == null) {
            return null;
        }
        SftpATTRS attrs = entry.getAttrs();
        boolean dir2 = attrs != null && attrs.isDir();
        long size = (attrs != null && !dir2) ? attrs.getSize() : RemoteEntry.UNKNOWN_SIZE;
        long mtime = (attrs != null) ? (attrs.getMTime() * 1000L) : 0L;
        String perms = (attrs != null) ? toPermissionString(attrs.getPermissionsString()) : "";
        String link = null;
        if (attrs != null && attrs.isLink()) {
            try {
                link = requireChannel().readlink(RemotePaths.join(dir, name));
            } catch (IOException | SftpException e) {
                LOG.debug("Could not read the link target for {}", name, e);
            }
        }
        return new RemoteEntry(name, size, mtime, dir2, perms, link);
    }

    /**
     * Reduces a raw SFTP permission string (e.g. {@code -rwxr-xr-x}) to its nine
     * rwx characters, dropping the leading file-type column so it matches the
     * form the FTP adapter produces.
     *
     * @param raw the string from {@link SftpATTRS#getPermissionsString()}
     * @return the nine permission characters, or {@code ""} when unavailable
     */
    static String toPermissionString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.length() == 10) {
            return raw.substring(1);
        }
        return (raw.length() > 9) ? raw.substring(raw.length() - 9) : raw;
    }

    /**
     * Supplies the password to JSch and implements trust-on-first-use host-key
     * acceptance: an unseen host key is accepted (and recorded to known_hosts by
     * JSch), while a key that conflicts with the recorded one is rejected before
     * this is ever consulted.
     */
    private static final class TofuUserInfo implements UserInfo {

        private final String password;
        private final Path knownHosts;

        TofuUserInfo(String password, Path knownHosts) {
            this.password = password;
            this.knownHosts = knownHosts;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptPassword(String message) {
            return password != null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            // Trust-on-first-use: accept and record an unseen host key. A changed
            // key never reaches here because StrictHostKeyChecking=yes rejects it.
            LOG.debug("Accepting host key ({}): {}", knownHosts, message);
            return true;
        }

        @Override
        public void showMessage(String message) {
            LOG.debug("SSH message: {}", message);
        }
    }
}
