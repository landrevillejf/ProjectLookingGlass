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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The inbound half of a P2P node: a {@link ServerSocket} plus a daemon accept
 * loop. Each accepted socket is handed to a {@link ChannelAcceptor} on its own
 * daemon thread, so a slow or hostile inbound handshake can never stall the loop
 * (and one connection cannot deny service to the next). Established channels are
 * surfaced to an {@code onConnected} sink; failures to an {@code onError} sink.
 *
 * <p>This class is deliberately dumb about P2P semantics: it knows nothing about
 * identities, handshakes or peers. The {@link P2pNode} supplies the acceptor that
 * runs the {@link NoiseXXHandshake} and adopts the resulting {@link SecureChannel},
 * which keeps the socket plumbing independently testable. The server binds to the
 * wildcard address so LAN peers can reach it; port {@code 0} selects an ephemeral
 * port (read it back with {@link #getPort()}).</p>
 *
 * <p>All threads are daemons, so a running server never pins the JVM open. The
 * server is headless-safe: it touches no AWT/Swing class.</p>
 */
public final class P2pServer implements AutoCloseable {

    /** The listen backlog: enough for a small mesh, small enough to shed a flood. */
    public static final int DEFAULT_BACKLOG = 16;

    /**
     * Turns an accepted socket into a live, authenticated {@link SecureChannel}.
     * Implemented by {@link P2pNode} to run the handshake with its own identity.
     */
    public interface ChannelAcceptor {
        /**
         * Adopts an accepted socket.
         *
         * @param socket the freshly accepted socket (never null)
         * @return the established channel, or null to drop the connection quietly
         * @throws IOException              if the socket cannot be used
         * @throws GeneralSecurityException if the handshake fails
         */
        SecureChannel accept(Socket socket) throws IOException, GeneralSecurityException;
    }

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final ChannelAcceptor acceptor;
    private final Consumer<SecureChannel> onConnected;
    private final Consumer<String> onError;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Binds and starts accepting immediately.
     *
     * @param port        the TCP port to listen on ({@code 0} for an ephemeral port)
     * @param acceptor    turns each accepted socket into a channel (never null)
     * @param onConnected receives each established channel (may be null)
     * @param onError     receives human-readable failure notes (may be null)
     * @throws IOException if the port cannot be bound
     */
    public P2pServer(int port, ChannelAcceptor acceptor, Consumer<SecureChannel> onConnected,
                     Consumer<String> onError) throws IOException {
        if (acceptor == null) {
            throw new IllegalArgumentException("acceptor is required");
        }
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.acceptor = acceptor;
        this.onConnected = onConnected;
        this.onError = onError;
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port), DEFAULT_BACKLOG);
        this.acceptThread = new Thread(this::acceptLoop, "p2p-server-" + getPort());
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    /** @return the port this server is actually listening on (useful for ephemeral). */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** @return true while the server socket is bound, open and not yet closed. */
    public boolean isRunning() {
        return !closed.get() && serverSocket.isBound() && !serverSocket.isClosed();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException ex) {
                // A close() wakes accept() with a SocketException; that is expected.
                if (!closed.get()) {
                    fireError("accept failed: " + ex.getMessage());
                }
                return;
            }
            Thread t = new Thread(() -> handshake(socket),
                    "p2p-inbound-" + socket.getRemoteSocketAddress());
            t.setDaemon(true);
            t.start();
        }
    }

    private void handshake(Socket socket) {
        try {
            SecureChannel channel = acceptor.accept(socket);
            if (channel == null) {
                closeQuietly(socket);
                return;
            }
            if (onConnected != null) {
                onConnected.accept(channel);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            closeQuietly(socket);
            fireError("inbound handshake failed: " + ex.getMessage());
        }
    }

    private void fireError(String message) {
        if (onError != null) {
            onError.accept(message);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Stops accepting and releases the port. Idempotent and never throws. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // best-effort
            }
            acceptThread.interrupt();
        }
    }
}
