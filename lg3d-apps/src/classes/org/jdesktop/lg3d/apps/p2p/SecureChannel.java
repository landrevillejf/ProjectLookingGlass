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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;

/**
 * A single encrypted, authenticated, reliable peer-to-peer link over one TCP
 * socket. A channel first runs a {@link NoiseXXHandshake} to mutually authenticate
 * the two long-term identities and derive forward-secret transport keys, then
 * exchanges length-prefixed {@link SecureFrame}s sealed with AES-256-GCM under
 * those keys. It owns the socket, a daemon reader thread, a synchronised writer,
 * and a keep-alive that pings the peer and drops a silent link.
 *
 * <p>The structure deliberately mirrors the Messenger's {@code IrcProtocol}: all
 * I/O is off the Swing EDT, listener callbacks fire on the reader thread (so the
 * caller marshals to the EDT before touching any widget), the writer is
 * thread-safe, and every thread is a daemon so the JVM can always exit. Unlike
 * IRC, though, there is no reconnect: a P2P link is a direct connection to one
 * identified peer, and re-establishing it is the {@link P2pNode}'s job.</p>
 *
 * <p><strong>Nonce safety.</strong> Each direction has its own key and its own
 * monotonic frame counter used as the GCM nonce, so a nonce is never reused. The
 * counters start at zero for the transport keys because those keys are freshly
 * derived by the handshake's split and are never used for anything else.</p>
 *
 * <p>A channel is created through {@link #connect} (dial out, play the initiator)
 * or {@link #accept} (adopt an accepted socket, play the responder); both perform
 * the handshake synchronously and only return once the link is secure, so a
 * caller either gets an authenticated channel or an exception - never a
 * half-open, unencrypted socket.</p>
 */
public final class SecureChannel implements AutoCloseable {

    /** The default TCP connect timeout for an outbound channel, in milliseconds. */
    public static final int CONNECT_TIMEOUT_MS = 15_000;

    /** Handshake messages are tiny (a few keys and tags); cap them well below a frame. */
    static final int MAX_HANDSHAKE_MESSAGE = 64 * 1024;

    private static final int DEFAULT_KEEP_ALIVE_SECONDS = 30;
    private static final int DEFAULT_DEAD_LINK_SECONDS = 90;

    /** Receives decoded frames and lifecycle events, on the reader thread. */
    public interface Listener {
        /** A CONTROL frame arrived (chat, presence, file control, invite, bye). */
        void onControl(SecureChannel channel, P2pMessage message);

        /** A DATA frame arrived (a raw file-chunk payload). */
        void onData(SecureChannel channel, byte[] payload);

        /** The link closed; {@code reason} is a human-readable cause. */
        void onClosed(SecureChannel channel, String reason);

        /** A recoverable problem that did not (yet) drop the link. */
        default void onError(SecureChannel channel, String error) {
            // optional
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final NoiseXXHandshake.Role role;
    private final Listener listener;
    private final String remoteAddress;

    private final SecretKey sendKey;
    private final SecretKey recvKey;
    private final PublicKey remoteStatic;
    private final String remoteFingerprint;
    private final byte[] handshakeHash;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean closeReported = new AtomicBoolean(false);

    private long sendCounter;         // guarded by the write lock
    private long recvCounter;         // touched only by the reader thread
    private volatile long lastActivityMs;
    private volatile String closeReason;

    private Thread readerThread;
    private ScheduledExecutorService keepAlive;

    private int keepAliveSeconds = DEFAULT_KEEP_ALIVE_SECONDS;
    private int deadLinkSeconds = DEFAULT_DEAD_LINK_SECONDS;

    private SecureChannel(Socket socket, NoiseXXHandshake.Role role, KeyPair localStatic,
                          byte[] prologue, Listener listener, SecureRandom random)
            throws IOException, GeneralSecurityException {
        this.socket = socket;
        this.role = role;
        this.listener = listener;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
        this.remoteAddress = describe(socket);
        socket.setSoTimeout(0); // the reader blocks; the keep-alive detects a silent peer
        socket.setTcpNoDelay(true);
        try {
            NoiseXXHandshake hs = handshake(role, localStatic, prologue, random);
            this.sendKey = hs.cipherKeys().sendKey();
            this.recvKey = hs.cipherKeys().recvKey();
            this.remoteStatic = hs.cipherKeys().remoteStatic();
            this.remoteFingerprint = hs.cipherKeys().remoteFingerprint();
            this.handshakeHash = hs.cipherKeys().handshakeHash();
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            closeSocket();
            throw ex;
        }
        this.lastActivityMs = System.currentTimeMillis();
        startReader();
        startKeepAlive();
    }

    private static String describe(Socket s) {
        if (s.getInetAddress() == null) {
            return "unknown";
        }
        return s.getInetAddress().getHostAddress() + ":" + s.getPort();
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /**
     * Dials a peer and establishes a secure channel as the handshake initiator.
     *
     * @param host        the peer host
     * @param port        the peer port
     * @param localStatic our long-term static identity keypair
     * @param prologue    the shared handshake context (may be null)
     * @param listener    the frame/lifecycle sink (may be null)
     * @param random      the randomness source for the ephemeral handshake key
     * @return an authenticated, ready channel
     * @throws IOException              if the socket cannot be connected
     * @throws GeneralSecurityException if the handshake fails (authenticity, tamper)
     */
    public static SecureChannel connect(String host, int port, KeyPair localStatic,
                                        byte[] prologue, Listener listener, SecureRandom random)
            throws IOException, GeneralSecurityException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        return new SecureChannel(s, NoiseXXHandshake.Role.INITIATOR, localStatic, prologue,
                listener, random);
    }

    /**
     * Adopts an already-accepted socket and establishes a secure channel as the
     * handshake responder.
     *
     * @param socket      the accepted socket (never null)
     * @param localStatic our long-term static identity keypair
     * @param prologue    the shared handshake context (may be null)
     * @param listener    the frame/lifecycle sink (may be null)
     * @param random      the randomness source for the ephemeral handshake key
     * @return an authenticated, ready channel
     * @throws IOException              if the socket streams cannot be opened
     * @throws GeneralSecurityException if the handshake fails
     */
    public static SecureChannel accept(Socket socket, KeyPair localStatic, byte[] prologue,
                                       Listener listener, SecureRandom random)
            throws IOException, GeneralSecurityException {
        if (socket == null) {
            throw new IllegalArgumentException("socket is required");
        }
        return new SecureChannel(socket, NoiseXXHandshake.Role.RESPONDER, localStatic, prologue,
                listener, random);
    }

    // ------------------------------------------------------------------
    // Handshake I/O (length-prefixed blobs, distinct from data frames)
    // ------------------------------------------------------------------

    private NoiseXXHandshake handshake(NoiseXXHandshake.Role role, KeyPair localStatic,
                                       byte[] prologue, SecureRandom random)
            throws IOException, GeneralSecurityException {
        NoiseXXHandshake hs = new NoiseXXHandshake(role, localStatic, prologue, random);
        byte[] empty = new byte[0];
        if (role == NoiseXXHandshake.Role.INITIATOR) {
            writeHandshake(hs.writeMessage(empty));   // -> e
            hs.readMessage(readHandshake());          // <- e, ee, s, es
            writeHandshake(hs.writeMessage(empty));   // -> s, se
        } else {
            hs.readMessage(readHandshake());          // -> e
            writeHandshake(hs.writeMessage(empty));   // <- e, ee, s, es
            hs.readMessage(readHandshake());          // -> s, se
        }
        if (!hs.isComplete()) {
            throw new IOException("handshake did not complete");
        }
        return hs;
    }

    private void writeHandshake(byte[] message) throws IOException {
        out.writeInt(message.length);
        out.write(message);
        out.flush();
    }

    private byte[] readHandshake() throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_HANDSHAKE_MESSAGE) {
            throw new IOException("bad handshake message length: " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Sends a CONTROL message. Thread-safe; a no-op once the channel is closed.
     *
     * @param message the message (null is ignored)
     */
    public void sendControl(P2pMessage message) {
        if (message == null) {
            return;
        }
        sendFrame(SecureFrame.control(message));
    }

    /**
     * Sends a DATA frame (a raw file-chunk payload). Thread-safe; a no-op once the
     * channel is closed.
     *
     * @param chunk the bytes (null/empty is ignored)
     */
    public void sendData(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        sendFrame(SecureFrame.data(chunk));
    }

    private void sendFrame(SecureFrame frame) {
        if (closed.get()) {
            return;
        }
        synchronized (this) {
            if (closed.get()) {
                return;
            }
            try {
                byte[] wire = FrameCodec.encode(frame, sendKey, sendCounter++);
                out.write(wire);
                out.flush();
            } catch (IOException ex) {
                // The reader thread will observe the broken socket and close.
                fireError("write failed: " + ex.getMessage());
            } catch (GeneralSecurityException ex) {
                fireError("frame seal failed: " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Reader thread
    // ------------------------------------------------------------------

    private void startReader() {
        readerThread = new Thread(this::readLoop, "p2p-reader-" + remoteAddress);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        String reason = "Connection closed";
        try {
            byte[] prefix = new byte[FrameCodec.LENGTH_PREFIX_BYTES];
            while (!closed.get()) {
                in.readFully(prefix);
                int len = FrameCodec.readLengthPrefix(prefix);
                FrameCodec.checkLength(len);
                byte[] body = new byte[len];
                in.readFully(body);
                SecureFrame frame = FrameCodec.decode(body, recvKey, recvCounter++);
                lastActivityMs = System.currentTimeMillis();
                dispatch(frame);
            }
            reason = "Disconnected";
        } catch (EOFException ex) {
            reason = "Peer closed the connection";
        } catch (IOException ex) {
            reason = (ex.getMessage() == null) ? ex.toString() : ex.getMessage();
        } catch (GeneralSecurityException ex) {
            // A frame that fails authentication is fatal: drop the link loudly.
            reason = "Secure frame verification failed: " + ex.getMessage();
        } catch (RuntimeException ex) {
            reason = (ex.getMessage() == null) ? ex.toString() : ex.getMessage();
        } finally {
            if (closeReason != null) {
                reason = closeReason;
            }
            closeSocket();
            stopKeepAlive();
            closed.set(true);
            fireClosed(reason);
        }
    }

    private void dispatch(SecureFrame frame) {
        if (frame.getType() == SecureFrame.Type.DATA) {
            fireData(frame.getPayload());
            return;
        }
        P2pMessage msg = frame.toMessage();
        if (msg == null) {
            fireError("ignoring a malformed CONTROL frame");
            return;
        }
        switch (msg.getKind()) {
            case PING -> sendControl(P2pMessage.pong());
            case PONG -> {
                // Activity was already recorded; nothing to surface.
            }
            default -> fireControl(msg);
        }
    }

    // ------------------------------------------------------------------
    // Keep-alive and dead-link detection
    // ------------------------------------------------------------------

    /** Sets the ping interval; also scales the dead-link timeout. For tests/tuning. */
    public void setKeepAliveSeconds(int seconds) {
        this.keepAliveSeconds = Math.max(1, seconds);
        // Reschedule a running keep-alive so a new interval takes effect immediately.
        if (!closed.get() && keepAlive != null) {
            startKeepAlive();
        }
    }

    /** Sets how long a totally silent peer is tolerated before the link is dropped. */
    public void setDeadLinkSeconds(int seconds) {
        this.deadLinkSeconds = Math.max(1, seconds);
    }

    private void startKeepAlive() {
        stopKeepAlive();
        keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "p2p-keepalive-" + remoteAddress);
            t.setDaemon(true);
            return t;
        });
        keepAlive.scheduleAtFixedRate(this::keepAliveTick, keepAliveSeconds, keepAliveSeconds,
                TimeUnit.SECONDS);
    }

    private void keepAliveTick() {
        if (closed.get()) {
            return;
        }
        long idleMs = System.currentTimeMillis() - lastActivityMs;
        if (idleMs > deadLinkSeconds * 1000L) {
            close("Peer unresponsive");
            return;
        }
        sendControl(P2pMessage.ping());
    }

    private void stopKeepAlive() {
        ScheduledExecutorService ka = this.keepAlive;
        if (ka != null) {
            ka.shutdownNow();
            this.keepAlive = null;
        }
    }

    // ------------------------------------------------------------------
    // State and teardown
    // ------------------------------------------------------------------

    /** @return true while the socket is open and the link is live. */
    public boolean isOpen() {
        return !closed.get() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** @return the role this side played in the handshake. */
    public NoiseXXHandshake.Role getRole() {
        return role;
    }

    /** @return the peer's static public key, or null if the handshake did not yield one. */
    public PublicKey getRemoteStatic() {
        return remoteStatic;
    }

    /** @return the SHA-256 fingerprint of the peer's static key - its identity. */
    public String getRemoteFingerprint() {
        return remoteFingerprint;
    }

    /** @return the handshake transcript hash binding this session. */
    public byte[] getHandshakeHash() {
        return (handshakeHash == null) ? new byte[0] : handshakeHash.clone();
    }

    /** @return a {@code host:port} description of the peer, for display. */
    public String getRemoteAddress() {
        return remoteAddress;
    }

    /** @return our local listening port for this socket (useful on the accepted side). */
    public int getLocalPort() {
        return (socket == null) ? -1 : socket.getLocalPort();
    }

    /** Closes the channel quietly. Idempotent and never throws. */
    @Override
    public void close() {
        close(null);
    }

    /**
     * Closes the channel, recording {@code reason} for the {@link Listener#onClosed}
     * callback. Idempotent and never throws; closing the socket wakes the blocked
     * reader thread, which performs the final teardown and fires the callback once.
     *
     * @param reason a human-readable cause (may be null)
     */
    public void close(String reason) {
        if (reason != null) {
            this.closeReason = reason;
        }
        if (closed.compareAndSet(false, true)) {
            stopKeepAlive();
            closeSocket();
            // If the reader thread had already exited without reporting (rare),
            // make sure the listener still learns the link is gone.
            Thread rt = this.readerThread;
            if (rt == null || !rt.isAlive()) {
                fireClosed(this.closeReason == null ? "Disconnected" : this.closeReason);
            }
        }
    }

    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    // ------------------------------------------------------------------
    // Listener fan-out (null-safe)
    // ------------------------------------------------------------------

    private void fireControl(P2pMessage msg) {
        if (listener != null) {
            listener.onControl(this, msg);
        }
    }

    private void fireData(byte[] payload) {
        if (listener != null) {
            listener.onData(this, payload);
        }
    }

    private void fireError(String error) {
        if (listener != null) {
            listener.onError(this, error);
        }
    }

    private void fireClosed(String reason) {
        if (closeReported.compareAndSet(false, true) && listener != null) {
            listener.onClosed(this, reason);
        }
    }
}
