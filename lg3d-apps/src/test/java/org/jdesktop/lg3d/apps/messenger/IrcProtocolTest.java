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
package org.jdesktop.lg3d.apps.messenger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@link IrcProtocol}: the native, in-process IRC client.
 * They run it against an in-JVM {@link MockIrcServer} bound to an ephemeral
 * localhost port (no external server, no network access, so CI-safe), and drive
 * the real socket in both directions:
 *
 * <ul>
 *   <li>registration ({@code NICK}/{@code USER} &rarr; {@code 001} welcome) and
 *       the {@code onConnected} callback;</li>
 *   <li>outgoing {@code PRIVMSG}, long-message 512-octet splitting, {@code /me}
 *       CTCP framing, {@code JOIN}/{@code PART}/{@code NICK};</li>
 *   <li>incoming channel and private messages (with private routing to the
 *       sender), mIRC formatting stripping, {@code 353} roster replies with mode
 *       prefixes stripped, CTCP {@code ACTION}/{@code VERSION}, and server
 *       {@code PING} &rarr; {@code PONG};</li>
 *   <li>nickname-in-use ({@code 433}) recovery and a clean {@code QUIT} on
 *       disconnect.</li>
 * </ul>
 *
 * <p>Every wait uses a bounded latch/poll so a regression fails fast instead of
 * hanging the build.</p>
 */
class IrcProtocolTest {

    private static final long TIMEOUT_MS = 5_000;
    private static final String NICK = "lg3dtest";

    private MockIrcServer server;
    private RecordingListener listener;
    private IrcProtocol protocol;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockIrcServer();
        listener = new RecordingListener();
    }

    @AfterEach
    void tearDown() {
        if (protocol != null) {
            protocol.disconnect();
        }
        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------
    // Registration and connection lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("connect registers with NICK/USER and fires onConnected on 001")
    void registersAndConnects() throws Exception {
        protocol = connectAndAwaitRegistered();

        assertTrue(protocol.isConnected());
        assertEquals(NICK, protocol.getCurrentNick());
        assertTrue(server.awaitLine(l -> l.equals("NICK " + NICK), TIMEOUT_MS),
                "the server should see the NICK line: " + server.snapshot());
        assertTrue(server.awaitLine(l -> l.startsWith("USER "), TIMEOUT_MS),
                "the server should see the USER line: " + server.snapshot());
        assertEquals(0, listener.connected.getCount(), "onConnected should have fired");
    }

    @Test
    @DisplayName("a 433 nickname-in-use retries with an underscore suffix")
    void nickInUseTriggersUnderscoreRetry() throws Exception {
        server.setNickInUse(true);
        protocol = new IrcProtocol();
        protocol.connect(accountFor(server), listener);

        // No 001 is ever sent, so the client stays unregistered but retries.
        assertTrue(server.awaitLine(l -> l.equals("NICK " + NICK + "_"), TIMEOUT_MS),
                "the client should retry with an underscore: " + server.snapshot());
        assertFalse(protocol.isConnected());
    }

    @Test
    @DisplayName("disconnect sends QUIT and drops the connection")
    void disconnectQuitsAndCloses() throws Exception {
        protocol = connectAndAwaitRegistered();
        assertTrue(protocol.isConnected());

        protocol.disconnect();

        assertFalse(protocol.isConnected());
        assertTrue(server.awaitLine(l -> l.equals("QUIT :Leaving"), TIMEOUT_MS),
                "the server should see the QUIT line: " + server.snapshot());
    }

    // ------------------------------------------------------------------
    // Outgoing traffic
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sendMessage writes a single PRIVMSG for a short body")
    void sendsPrivmsgToChannel() throws Exception {
        protocol = connectAndAwaitRegistered();

        protocol.sendMessage("#lg3d", "hello world");

        assertTrue(server.awaitLine(l -> l.equals("PRIVMSG #lg3d :hello world"), TIMEOUT_MS),
                "the server should see the PRIVMSG line: " + server.snapshot());
    }

    @Test
    @DisplayName("sendMessage splits a long body across 512-octet wire lines")
    void splitsLongMessages() throws Exception {
        protocol = connectAndAwaitRegistered();
        String body = "a".repeat(1200);

        protocol.sendMessage("#lg3d", body);

        // head "PRIVMSG #lg3d :" is 15 bytes; usable 510 -> 495 body bytes/line.
        assertTrue(server.awaitCount(l -> l.startsWith("PRIVMSG #lg3d :"), 3, TIMEOUT_MS),
                "expected the body to be split: " + server.snapshot());
        assertEquals(3, server.countLines(l -> l.startsWith("PRIVMSG #lg3d :")));
        // Every emitted line must respect the 512-octet cap.
        for (String line : server.snapshot()) {
            if (line.startsWith("PRIVMSG #lg3d :")) {
                assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= IrcCodec.MAX_LINE_OCTETS,
                        "line exceeds the RFC cap: " + line.length());
            }
        }
    }

    @Test
    @DisplayName("sendAction frames the body as a CTCP ACTION")
    void sendActionWrapsCtcp() throws Exception {
        protocol = connectAndAwaitRegistered();

        protocol.sendAction("#lg3d", "waves");

        assertTrue(server.awaitLine(l -> l.startsWith("PRIVMSG #lg3d :")
                && l.contains("ACTION waves"), TIMEOUT_MS),
                "the server should see a CTCP ACTION: " + server.snapshot());
    }

    @Test
    @DisplayName("joinChannel prepends '#' only when missing")
    void joinChannelPrependsHash() throws Exception {
        protocol = connectAndAwaitRegistered();

        protocol.joinChannel("lg3d");
        protocol.joinChannel("#java");

        assertTrue(server.awaitLine(l -> l.equals("JOIN #lg3d"), TIMEOUT_MS), server.snapshot().toString());
        assertTrue(server.awaitLine(l -> l.equals("JOIN #java"), TIMEOUT_MS), server.snapshot().toString());
    }

    @Test
    @DisplayName("partChannel sends PART with and without a reason")
    void partChannelSendsReason() throws Exception {
        protocol = connectAndAwaitRegistered();

        protocol.partChannel("#lg3d", "bye");
        protocol.partChannel("#plain", null);

        assertTrue(server.awaitLine(l -> l.equals("PART #lg3d :bye"), TIMEOUT_MS), server.snapshot().toString());
        assertTrue(server.awaitLine(l -> l.equals("PART #plain"), TIMEOUT_MS), server.snapshot().toString());
    }

    @Test
    @DisplayName("changeNick sends a NICK line")
    void changeNickSendsNick() throws Exception {
        protocol = connectAndAwaitRegistered();

        protocol.changeNick("renamed");

        assertTrue(server.awaitLine(l -> l.equals("NICK renamed"), TIMEOUT_MS), server.snapshot().toString());
    }

    // ------------------------------------------------------------------
    // Incoming traffic
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a channel PRIVMSG is delivered to the channel conversation")
    void receivesChannelMessage() throws Exception {
        protocol = connectAndAwaitRegistered();

        server.sendRaw(":alice!user@host PRIVMSG #lg3d :hi there");

        ChatMessage m = awaitMessage(x -> x.getKind() == ChatMessage.Kind.PRIVMSG);
        assertNotNull(m, "no PRIVMSG delivered: " + listener.messages);
        assertEquals("alice", m.getFrom());
        assertEquals("#lg3d", m.getTarget());
        assertEquals("hi there", m.getText());
    }

    @Test
    @DisplayName("a private message routes to the sender's conversation")
    void receivesPrivateMessageRoutesToSender() throws Exception {
        protocol = connectAndAwaitRegistered();

        server.sendRaw(":bob!u@h PRIVMSG " + NICK + " :psst");

        ChatMessage m = awaitMessage(x -> x.getText().equals("psst"));
        assertNotNull(m, "no private message delivered: " + listener.messages);
        assertEquals("bob", m.getFrom());
        assertEquals("bob", m.getTarget(), "a private message belongs to the peer conversation");
    }

    @Test
    @DisplayName("incoming mIRC formatting codes are stripped")
    void stripsFormattingFromIncoming() throws Exception {
        protocol = connectAndAwaitRegistered();

        server.sendRaw(":alice!u@h PRIVMSG #lg3d :\u0002bold\u0002 and \u00034red\u0003");

        ChatMessage m = awaitMessage(x -> x.getKind() == ChatMessage.Kind.PRIVMSG);
        assertNotNull(m, "no message delivered: " + listener.messages);
        assertEquals("bold and red", m.getText());
    }

    @Test
    @DisplayName("a 353 name reply strips mode prefixes into the roster")
    void rosterUpdateStripsModePrefixes() throws Exception {
        protocol = connectAndAwaitRegistered();

        server.sendRaw(":mock.server 353 " + NICK + " = #lg3d :@op +voice regular");

        assertTrue(awaitRoster("#lg3d", TIMEOUT_MS), "no roster update: " + listener.rosters);
        assertEquals(List.of("op", "voice", "regular"), listener.rosters.get("#lg3d"));
    }

    @Test
    @DisplayName("a CTCP ACTION arrives as an ACTION event")
    void ctcpActionBecomesActionKind() throws Exception {
        protocol = connectAndAwaitRegistered();

        server.sendRaw(":bob!u@h PRIVMSG #lg3d :\u0001ACTION waves\u0001");

        ChatMessage m = awaitMessage(x -> x.getKind() == ChatMessage.Kind.ACTION);
        assertNotNull(m, "no ACTION delivered: " + listener.messages);
        assertEquals("bob", m.getFrom());
        assertEquals("waves", m.getText());
    }

    @Test
    @DisplayName("a CTCP VERSION query is answered with a NOTICE")
    void ctcpVersionIsAnswered() throws Exception {
        protocol = connectAndAwaitRegistered();

        server.sendRaw(":bob!u@h PRIVMSG " + NICK + " :\u0001VERSION\u0001");

        assertTrue(server.awaitLine(l -> l.startsWith("NOTICE bob :") && l.contains("VERSION"),
                TIMEOUT_MS), "the client should answer the CTCP VERSION: " + server.snapshot());
    }

    @Test
    @DisplayName("a server PING is answered with a PONG")
    void serverPingGetsPong() throws Exception {
        protocol = connectAndAwaitRegistered();

        server.sendRaw("PING :mock-123");

        assertTrue(server.awaitLine(l -> l.equals("PONG :mock-123"), TIMEOUT_MS),
                "the client should answer the PING: " + server.snapshot());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AccountConfig accountFor(MockIrcServer s) {
        AccountConfig a = new AccountConfig("Mock", "irc", "localhost", NICK);
        a.setPort(s.getPort());
        a.setUseTls(false);
        return a;
    }

    private IrcProtocol connectAndAwaitRegistered() throws Exception {
        IrcProtocol p = new IrcProtocol();
        p.connect(accountFor(server), listener);
        assertTrue(listener.connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "the client did not register in time: " + server.snapshot());
        return p;
    }

    private ChatMessage awaitMessage(Predicate<ChatMessage> p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (ChatMessage m : listener.messagesSnapshot()) {
                if (p.test(m)) {
                    return m;
                }
            }
            Thread.sleep(15);
        }
        return null;
    }

    private boolean awaitRoster(String channel, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (listener.rosters.containsKey(channel)) {
                return true;
            }
            Thread.sleep(15);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    /** Captures the client's callbacks so the mock exchange can be asserted on. */
    private static final class RecordingListener implements ProtocolListener {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());
        final List<String> status = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        final Map<String, List<String>> rosters =
                Collections.synchronizedMap(new LinkedHashMap<>());

        List<ChatMessage> messagesSnapshot() {
            synchronized (messages) {
                return new ArrayList<>(messages);
            }
        }

        @Override public void onConnected(AccountConfig account) { connected.countDown(); }
        @Override public void onDisconnected(AccountConfig account, String reason) {
            disconnected.countDown();
        }
        @Override public void onMessage(AccountConfig account, ChatMessage message) {
            messages.add(message);
        }
        @Override public void onRosterUpdate(AccountConfig account, String channel, List<String> members) {
            rosters.put(channel, members);
        }
        @Override public void onStatus(AccountConfig account, String s) { status.add(s); }
        @Override public void onError(AccountConfig account, String error) { errors.add(error); }
    }

    /**
     * A minimal, single-connection IRC server on an ephemeral localhost port. It
     * records every line the client sends, replies to {@code USER} with a
     * {@code 001} welcome (or a {@code 433} when {@link #setNickInUse(boolean)}
     * is set), and lets the test push arbitrary server lines via
     * {@link #sendRaw(String)}. All waits are bounded so a regression fails fast.
     */
    private static final class MockIrcServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final List<String> received = Collections.synchronizedList(new ArrayList<>());

        private volatile boolean running = true;
        private volatile boolean nickInUse;
        private volatile String lastNick = "guest";
        private volatile Socket clientSocket;
        private volatile BufferedWriter out;

        MockIrcServer() throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            acceptThread = new Thread(this::acceptLoop, "mock-irc-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int getPort() { return serverSocket.getLocalPort(); }

        void setNickInUse(boolean nickInUse) { this.nickInUse = nickInUse; }

        private void acceptLoop() {
            try {
                Socket s = serverSocket.accept();
                clientSocket = s;
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(
                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) {
                    handle(line);
                }
            } catch (IOException ignored) {
                // The socket was closed by close(); stop quietly.
            }
        }

        private void handle(String line) throws IOException {
            received.add(line);
            if (line.startsWith("NICK ")) {
                lastNick = line.substring(5).trim();
            } else if (line.startsWith("USER ")) {
                if (nickInUse) {
                    sendRaw(":mock.server 433 * " + lastNick + " :Nickname is already in use.");
                } else {
                    sendRaw(":mock.server 001 " + lastNick + " :Welcome to the Mock IRC network");
                    sendRaw(":mock.server 376 " + lastNick + " :End of /MOTD command.");
                }
            }
        }

        void sendRaw(String line) throws IOException {
            BufferedWriter w = out;
            if (w == null) {
                return;
            }
            synchronized (this) {
                w.write(line);
                w.write("\r\n");
                w.flush();
            }
        }

        List<String> snapshot() {
            synchronized (received) {
                return new ArrayList<>(received);
            }
        }

        int countLines(Predicate<String> p) {
            int n = 0;
            for (String line : snapshot()) {
                if (p.test(line)) {
                    n++;
                }
            }
            return n;
        }

        boolean awaitLine(Predicate<String> p, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                for (String line : snapshot()) {
                    if (p.test(line)) {
                        return true;
                    }
                }
                Thread.sleep(15);
            }
            return false;
        }

        boolean awaitCount(Predicate<String> p, int expected, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (countLines(p) >= expected) {
                    return true;
                }
                Thread.sleep(15);
            }
            return false;
        }

        @Override public void close() {
            running = false;
            Socket c = clientSocket;
            if (c != null) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
