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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link P2pServer}'s socket plumbing, independent of the
 * P2P semantics that {@code P2pNodeTest} exercises end-to-end: the server binds an
 * ephemeral port, guards its constructor arguments, invokes the acceptor once per
 * inbound connection on its own thread, quietly drops a connection the acceptor
 * declines (returning null), reports an acceptor failure to the error sink without
 * killing the loop, and closes idempotently. Every wait is bounded so a regression
 * fails fast.
 */
class P2pServerTest {

    private static final long TIMEOUT_MS = 10_000;

    @Test
    @DisplayName("binds an ephemeral port and reports it as running")
    void bindsEphemeralPort() throws Exception {
        try (P2pServer server = new P2pServer(0, socket -> null, null, null)) {
            assertTrue(server.getPort() > 0, "an ephemeral port should be assigned");
            assertTrue(server.isRunning());
        }
    }

    @Test
    @DisplayName("the constructor guards a null acceptor and an out-of-range port")
    void constructorGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new P2pServer(0, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new P2pServer(-1, socket -> null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new P2pServer(0x10000, socket -> null, null, null));
    }

    @Test
    @DisplayName("the acceptor is handed the socket and a null result drops it with EOF")
    void acceptorInvokedAndNullDrops() throws Exception {
        AtomicReference<Socket> seen = new AtomicReference<>();
        CountDownLatch accepted = new CountDownLatch(1);
        P2pServer.ChannelAcceptor acceptor = socket -> {
            seen.set(socket);
            accepted.countDown();
            return null; // decline: the server closes the socket quietly
        };
        try (P2pServer server = new P2pServer(0, acceptor, channel -> {
        }, null)) {
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("localhost", server.getPort()), 5_000);
                client.setSoTimeout(5_000);
                assertTrue(accepted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                        "the acceptor should run for the inbound connection");
                assertNotNull(seen.get(), "the acceptor should be handed the accepted socket");
                // Because the acceptor returned null, the server closed our socket:
                // reading it yields EOF rather than hanging.
                InputStream in = client.getInputStream();
                assertEquals(-1, in.read(), "a declined connection is closed by the server");
            }
        }
    }

    @Test
    @DisplayName("an acceptor failure is reported and the accept loop survives")
    void acceptorErrorReported() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        P2pServer.ChannelAcceptor acceptor = socket -> {
            invocations.incrementAndGet();
            throw new IOException("nope");
        };
        try (P2pServer server = new P2pServer(0, acceptor, null, errors::add)) {
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("localhost", server.getPort()), 5_000);
                assertTrue(awaitTrue(() -> errors.stream()
                                .anyMatch(e -> e.contains("inbound handshake failed"))),
                        "the failure should reach the error sink");
                assertTrue(server.isRunning(), "one bad connection must not stop the server");
            }
            // The loop still accepts a second connection afterwards.
            try (Socket client2 = new Socket()) {
                client2.connect(new InetSocketAddress("localhost", server.getPort()), 5_000);
                assertTrue(awaitTrue(() -> invocations.get() >= 2),
                        "the accept loop should keep serving after a failure");
            }
        }
    }

    @Test
    @DisplayName("close() is idempotent and stops the server")
    void closeIsIdempotent() throws Exception {
        P2pServer server = new P2pServer(0, socket -> null, null, null);
        assertTrue(server.isRunning());
        server.close();
        server.close();
        assertFalse(server.isRunning(), "a closed server is not running");
    }

    private static boolean awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
