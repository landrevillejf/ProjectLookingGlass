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
package org.jdesktop.lg3d.apps.ssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a single SSH connection lifecycle: connect, shell I/O, keepalive,
 * port forwarding, and graceful disconnect.
 *
 * <p>State machine: DISCONNECTED -> CONNECTING -> CONNECTED -> DISCONNECTING -> DISCONNECTED.
 * All blocking I/O runs on JDK 21 virtual threads; the keepalive pinger uses a
 * shared ScheduledExecutorService.</p>
 *
 * <p>Security: host keys are verified trust-on-first-use via
 * {@link KnownHostsManager} with StrictHostKeyChecking=yes. Passwords are passed
 * as byte[] to JSch to avoid retaining immutable Strings. Secrets are zeroed
 * after connect.</p>
 */
public final class SshSession implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SshSession.class.getName());
    private static final int READ_BUFFER_SIZE = 8192;

    /** Connection states. */
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    /** Listener for session events (output, state changes, errors). */
    public interface Listener {
        void onOutput(byte[] data, int offset, int length);
        void onStateChange(State newState, State oldState);
        void onError(String message, Throwable cause);
        void onDisconnected(String reason);
    }

    private final SshProfile profile;
    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private JSch jsch;
    private Session session;
    private ChannelShell channel;
    private OutputStream shellOut;
    private InputStream shellIn;
    private KnownHostsManager hostKeyManager;
    private Thread readerThread;
    private ScheduledExecutorService keepAliveScheduler;
    private ScheduledFuture<?> keepAliveTask;
    private final List<Integer> activeForwardedPorts = new ArrayList<>();

    private volatile long connectedAt;
    private volatile long bytesReceived;
    private volatile long bytesSent;

    public SshSession(SshProfile profile) {
        this.profile = profile;
    }

    // ------------------------------------------------------------------
    // Listener management
    // ------------------------------------------------------------------

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------
    // State accessors
    // ------------------------------------------------------------------

    public State getState() { return state.get(); }

    public boolean isConnected() {
        return state.get() == State.CONNECTED
                && session != null && session.isConnected()
                && channel != null && channel.isConnected();
    }

    public SshProfile getProfile() { return profile; }
    public long getConnectedAt() { return connectedAt; }
    public long getBytesReceived() { return bytesReceived; }
    public long getBytesSent() { return bytesSent; }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /** Initiates the SSH connection asynchronously on a virtual thread. */
    public void connect() {
        if (!state.compareAndSet(State.DISCONNECTED, State.CONNECTING)) {
            fireError("Already connected or connecting", null);
            return;
        }
        fireStateChange(State.CONNECTING);
        Thread.startVirtualThread(() -> {
            try {
                doConnect();
            } catch (Exception e) {
                cleanup();
                setState(State.DISCONNECTED);
                fireError("Connection failed: " + e.getMessage(), e);
                fireDisconnected(e.getMessage());
            }
        });
    }

    private void doConnect() throws JSchException, IOException {
        jsch = new JSch();

        // TOFU known hosts
        Path knownHosts = SshProfileStore.defaultConfigDir().resolve("known_hosts");
        hostKeyManager = new KnownHostsManager(knownHosts, jsch);
        hostKeyManager.setPassword(
                profile.getPassword() != null ? profile.getPassword().toCharArray() : null);
        hostKeyManager.setPassphrase(
                profile.getPassphrase() != null ? profile.getPassphrase().toCharArray() : null);

        // Key-based auth
        if (profile.getAuthMethod() == SshProfile.AuthMethod.PUBLIC_KEY) {
            String keyPath = profile.getPrivateKeyPath();
            if (keyPath == null || keyPath.isBlank()) {
                throw new IOException("No private key path configured");
            }
            Path key = Path.of(keyPath);
            if (!Files.isReadable(key)) {
                throw new IOException("Private key not readable: " + keyPath);
            }
            if (profile.getPassphrase() != null && !profile.getPassphrase().isEmpty()) {
                jsch.addIdentity(keyPath, profile.getPassphrase().getBytes(StandardCharsets.UTF_8));
            } else {
                jsch.addIdentity(keyPath);
            }
        }

        session = jsch.getSession(profile.getUsername(), profile.getHost(), profile.getPort());
        session.setUserInfo(hostKeyManager);

        Properties config = buildSessionConfig();
        session.setConfig(config);

        // Password auth (byte[] form avoids retaining an immutable String)
        if (profile.getAuthMethod() == SshProfile.AuthMethod.PASSWORD
                && profile.getPassword() != null) {
            session.setPassword(profile.getPassword().getBytes(StandardCharsets.UTF_8));
        }

        int timeoutMs = Math.max(1000, profile.getConnectTimeoutSeconds() * 1000);
        session.connect(timeoutMs);

        // Compression
        if (profile.isCompression()) {
            session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none");
            session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none");
            try {
                session.rekey();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Rekey for compression failed", e);
            }
        }

        // Port forwarding
        setupPortForwards();

        // Open interactive shell
        channel = (ChannelShell) session.openChannel("shell");
        channel.setPtyType(profile.getTerminalType(),
                profile.getColumns(), profile.getRows(), 0, 0);
        if (profile.isX11Forwarding()) {
            channel.setXForwarding(true);
        }

        shellOut = channel.getOutputStream();
        shellIn = channel.getInputStream();
        channel.connect(timeoutMs);

        // Startup command
        if (profile.getStartupCommand() != null && !profile.getStartupCommand().isBlank()) {
            sendCommand(profile.getStartupCommand());
        }

        connectedAt = System.currentTimeMillis();
        setState(State.CONNECTED);
        fireStateChange(State.CONNECTED);

        // Start output reader on a virtual thread
        readerThread = Thread.ofVirtual().name("ssh-reader-" + profile.getHost())
                .start(this::readLoop);

        // Start keepalive
        startKeepAlive();

        // Clear secrets from memory
        hostKeyManager.clearSecrets();
        LOG.info("Connected to " + profile.getHost() + ":" + profile.getPort());
    }

    private Properties buildSessionConfig() {
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "yes");
        config.put("server_host_key",
                "ssh-ed25519,ecdsa-sha2-nistp521,ecdsa-sha2-nistp384,"
                + "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa");
        config.put("kex",
                "curve25519-sha256,curve25519-sha256@libssh.org,"
                + "ecdh-sha2-nistp521,ecdh-sha2-nistp384,ecdh-sha2-nistp256,"
                + "diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,"
                + "diffie-hellman-group18-sha512,diffie-hellman-group14-sha256");
        config.put("cipher.s2c",
                "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,"
                + "aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr");
        config.put("cipher.c2s",
                "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,"
                + "aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr");
        config.put("mac.s2c",
                "hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com,"
                + "hmac-sha2-512,hmac-sha2-256");
        config.put("mac.c2s",
                "hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com,"
                + "hmac-sha2-512,hmac-sha2-256");
        if (profile.isAgentForwarding()) {
            config.put("ForwardAgent", "yes");
        }
        return config;
    }

    // ------------------------------------------------------------------
    // Port forwarding
    // ------------------------------------------------------------------

    private void setupPortForwards() {
        // Local forwards: "localPort:remoteHost:remotePort" per line
        String local = profile.getLocalForwards();
        if (local != null && !local.isBlank()) {
            for (String line : local.split("[\\n,;]")) {
                String entry = line.trim();
                if (entry.isEmpty()) continue;
                String[] parts = entry.split(":");
                if (parts.length == 3) {
                    try {
                        int lp = Integer.parseInt(parts[0].trim());
                        String rh = parts[1].trim();
                        int rp = Integer.parseInt(parts[2].trim());
                        int assigned = session.setPortForwardingL(lp, rh, rp);
                        activeForwardedPorts.add(assigned);
                        LOG.info("Local forward: " + lp + " -> " + rh + ":" + rp);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Local forward failed: " + entry, e);
                    }
                }
            }
        }
        // Remote forwards: "remotePort:localHost:localPort" per line
        String remote = profile.getRemoteForwards();
        if (remote != null && !remote.isBlank()) {
            for (String line : remote.split("[\\n,;]")) {
                String entry = line.trim();
                if (entry.isEmpty()) continue;
                String[] parts = entry.split(":");
                if (parts.length == 3) {
                    try {
                        int rp = Integer.parseInt(parts[0].trim());
                        String lh = parts[1].trim();
                        int lp = Integer.parseInt(parts[2].trim());
                        session.setPortForwardingR(rp, lh, lp);
                        LOG.info("Remote forward: " + rp + " -> " + lh + ":" + lp);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Remote forward failed: " + entry, e);
                    }
                }
            }
        }
        // Dynamic (SOCKS) forward
        String dynamic = profile.getDynamicForward();
        if (dynamic != null && !dynamic.isBlank()) {
            try {
                int dp = Integer.parseInt(dynamic.trim());
                int assigned = session.setPortForwardingL(dp, "socks5", 0);
                activeForwardedPorts.add(assigned);
                LOG.info("Dynamic SOCKS5 forward on port " + dp);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Dynamic forward failed: " + dynamic, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // I/O
    // ------------------------------------------------------------------

    /** Sends a command string followed by a newline to the remote shell. */
    public void sendCommand(String command) {
        if (shellOut == null || !isConnected()) {
            fireError("Not connected", null);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                byte[] data = (command + "\n").getBytes(StandardCharsets.UTF_8);
                shellOut.write(data);
                shellOut.flush();
                bytesSent += data.length;
            } catch (IOException e) {
                fireError("Send failed: " + e.getMessage(), e);
            }
        });
    }

    /** Sends raw bytes to the remote shell (for key-by-key input). */
    public void sendBytes(byte[] data) {
        if (shellOut == null || !isConnected()) return;
        Thread.startVirtualThread(() -> {
            try {
                shellOut.write(data);
                shellOut.flush();
                bytesSent += data.length;
            } catch (IOException e) {
                fireError("Send failed: " + e.getMessage(), e);
            }
        });
    }

    /** Resizes the remote PTY. */
    public void resizePty(int columns, int rows) {
        if (channel != null && channel.isConnected()) {
            channel.setPtySize(columns, rows, 0, 0);
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try {
            while (channel != null && channel.isConnected() && !Thread.currentThread().isInterrupted()) {
                int available = shellIn.available();
                if (available > 0) {
                    int read = shellIn.read(buffer, 0, Math.min(available, buffer.length));
                    if (read == -1) break;
                    bytesReceived += read;
                    fireOutput(buffer, 0, read);
                } else {
                    // Block on read when no data is buffered
                    int read = shellIn.read(buffer);
                    if (read == -1) break;
                    bytesReceived += read;
                    fireOutput(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            if (state.get() == State.CONNECTED) {
                fireError("Read error: " + e.getMessage(), e);
            }
        } finally {
            if (state.get() == State.CONNECTED) {
                String reason = "Remote shell closed";
                setState(State.DISCONNECTED);
                fireDisconnected(reason);
                cleanup();
            }
        }
    }

    // ------------------------------------------------------------------
    // Keepalive
    // ------------------------------------------------------------------

    private void startKeepAlive() {
        int seconds = profile.getKeepAliveSeconds();
        if (seconds <= 0) return;
        keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ssh-keepalive-" + profile.getHost());
            t.setDaemon(true);
            return t;
        });
        keepAliveTask = keepAliveScheduler.scheduleAtFixedRate(() -> {
            if (session != null && session.isConnected()) {
                try {
                    session.sendKeepAliveMsg();
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Keepalive failed", e);
                    fireError("Connection lost (keepalive failed)", e);
                    disconnect();
                }
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // Disconnect
    // ------------------------------------------------------------------

    /** Gracefully disconnects the session. */
    public void disconnect() {
        if (!state.compareAndSet(State.CONNECTED, State.DISCONNECTING)
                && !state.compareAndSet(State.CONNECTING, State.DISCONNECTING)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                cleanup();
            } finally {
                setState(State.DISCONNECTED);
                fireDisconnected("User disconnected");
            }
        });
    }

    private void cleanup() {
        // Stop keepalive
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
            keepAliveTask = null;
        }
        if (keepAliveScheduler != null) {
            keepAliveScheduler.shutdownNow();
            keepAliveScheduler = null;
        }
        // Interrupt reader
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        // Close port forwards
        for (int port : activeForwardedPorts) {
            try {
                if (session != null) session.delPortForwardingL(port);
            } catch (Exception ignored) { }
        }
        activeForwardedPorts.clear();
        // Close channel
        try {
            if (shellOut != null) shellOut.close();
        } catch (IOException ignored) { }
        try {
            if (shellIn != null) shellIn.close();
        } catch (IOException ignored) { }
        try {
            if (channel != null && channel.isConnected()) channel.disconnect();
        } catch (RuntimeException ignored) { }
        channel = null;
        shellOut = null;
        shellIn = null;
        // Close session
        try {
            if (session != null && session.isConnected()) session.disconnect();
        } catch (RuntimeException ignored) { }
        session = null;
        // Clear secrets
        if (hostKeyManager != null) {
            hostKeyManager.clearSecrets();
            hostKeyManager = null;
        }
        connectedAt = 0;
    }

    @Override
    public void close() {
        disconnect();
    }

    // ------------------------------------------------------------------
    // Event dispatch
    // ------------------------------------------------------------------

    private void setState(State newState) {
        state.set(newState);
    }

    private void fireOutput(byte[] data, int offset, int length) {
        for (Listener l : listeners) {
            try { l.onOutput(data, offset, length); }
            catch (RuntimeException e) { LOG.log(Level.FINE, "Listener error", e); }
        }
    }

    private void fireStateChange(State newState) {
        for (Listener l : listeners) {
            try { l.onStateChange(newState, state.get()); }
            catch (RuntimeException e) { LOG.log(Level.FINE, "Listener error", e); }
        }
    }

    private void fireError(String message, Throwable cause) {
        for (Listener l : listeners) {
            try { l.onError(message, cause); }
            catch (RuntimeException e) { LOG.log(Level.FINE, "Listener error", e); }
        }
    }

    private void fireDisconnected(String reason) {
        for (Listener l : listeners) {
            try { l.onDisconnected(reason); }
            catch (RuntimeException e) { LOG.log(Level.FINE, "Listener error", e); }
        }
    }
}
