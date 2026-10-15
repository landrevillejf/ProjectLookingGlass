/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client M3b client half: the WebSocket session, headless.
 *
 * Every frame here rides the real M1 MessagePacker codec -- the test fake is a
 * duplex, not a mock of the wire -- so these cases pin exactly what the
 * server's CommsWebSocketHandler expects of a client: the attach opens a
 * channel, the first SessionInitializationMessage mints the session the later
 * attaches echo under SESSION_ID_KEY, and every data frame carries the short
 * the AttachedClientMessage assigned. The handshake, fan-out, teardown and
 * every failure path run in-JVM; the java.net.http pieces (the partial-frame
 * reassembly, the send/close plumbing, the refused-socket error mapping) are
 * driven against a stub WebSocket, and the live socket path lands with the
 * desktop probe.
 */
package org.jdesktop.wonderland.client.comms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.jdesktop.wonderland.common.cell.CellEditConnectionType;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.comms.messages.AttachClientMessage;
import org.jdesktop.wonderland.common.comms.messages.AttachedClientMessage;
import org.jdesktop.wonderland.common.comms.messages.SessionInitializationMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketWonderlandSessionTest {

    private static final WonderlandIdentity ALICE =
            new WonderlandIdentity("alice", "Alice A", "alice@example.org");
    private static final BigInteger SESSION = BigInteger.valueOf(42);
    private static final short WIRE_ID = 7;

    /** One recorded socket: what it was sent, and how to push back at it. */
    private static final class FakeTransport implements WebSocketWonderlandSession.Transport {
        final Consumer<ByteBuffer> inbound;
        final List<ByteBuffer> sent = new ArrayList<>();
        boolean closed;
        boolean answerAttach = true;
        IOException throwOnSend;

        FakeTransport(Consumer<ByteBuffer> inbound) {
            this.inbound = inbound;
        }

        @Override
        public void send(ByteBuffer frame) throws IOException {
            if (throwOnSend != null) {
                throw throwOnSend;
            }
            sent.add(frame);
            if (answerAttach && sent.size() == 1) {
                AttachClientMessage attach =
                        (AttachClientMessage) unpack(sent.get(0)).getMessage();
                inbound.accept(pack(new AttachedClientMessage(
                        attach.getMessageID(), WIRE_ID), (short) 0));
                inbound.accept(pack(new SessionInitializationMessage(
                        SESSION, ALICE), (short) 0));
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        /** Push one unsolicited frame at the session, as the reader thread would. */
        void push(Message message) {
            inbound.accept(pack(message, WIRE_ID));
        }
    }

    /** A factory of fakes: records the URIs dialed, hands out the sockets. */
    private static final class FakeFactory
            implements WebSocketWonderlandSession.TransportFactory {
        final List<URI> uris = new ArrayList<>();
        final List<FakeTransport> transports = new ArrayList<>();
        IOException throwOnOpen;
        boolean answerAttach = true;

        @Override
        public WebSocketWonderlandSession.Transport connect(URI uri,
                Consumer<ByteBuffer> inbound) throws IOException {
            if (throwOnOpen != null) {
                throw throwOnOpen;
            }
            FakeTransport transport = new FakeTransport(inbound);
            transport.answerAttach = answerAttach;
            uris.add(uri);
            transports.add(transport);
            return transport;
        }

        FakeTransport last() {
            return transports.get(transports.size() - 1);
        }
    }

    /** The connection the port's own classes would be: records, and answers. */
    private static final class TestConnection implements ClientConnection {
        private final ConnectionType type;
        final List<Message> received = new ArrayList<>();
        private WonderlandSession session;
        private Status status = Status.DISCONNECTED;

        TestConnection(ConnectionType type) {
            this.type = type;
        }

        @Override
        public ConnectionType getConnectionType() {
            return type;
        }

        @Override
        public WonderlandSession getSession() {
            return session;
        }

        @Override
        public synchronized Status getStatus() {
            return status;
        }

        @Override
        public synchronized void connected(WonderlandSession session) {
            this.session = session;
            this.status = Status.CONNECTED;
        }

        @Override
        public synchronized void disconnected() {
            this.status = Status.DISCONNECTED;
        }

        @Override
        public void messageReceived(Message message) {
            received.add(message);
        }
    }

    private static Properties identity() {
        Properties props = new Properties();
        props.setProperty("username", ALICE.getUsername());
        props.setProperty("fullname", ALICE.getFullName());
        props.setProperty("email", ALICE.getEmail());
        return props;
    }

    private static WebSocketWonderlandSession session(FakeFactory factory) {
        return new WebSocketWonderlandSession(new WonderlandServerInfo("localhost", 8080),
                identity(), factory, Duration.ofSeconds(5));
    }

    private static ByteBuffer pack(Message message, short clientID) {
        try {
            return MessagePacker.pack(message, clientID);
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static MessagePacker.ReceivedMessage unpack(ByteBuffer frame) {
        try {
            MessagePacker.ReceivedMessage received =
                    MessagePacker.unpack(frame.duplicate());
            assertNotNull(received, "frame unpacked to nothing");
            return received;
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static AttachClientMessage attachOf(FakeTransport transport) {
        return (AttachClientMessage) unpack(transport.sent.get(0)).getMessage();
    }

    @Test
    void theAttachHandshakeOpensTheChannelAndMintsTheSession() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);

        session.connect(cache, new Properties());

        // the socket dialed /comms on the server's host and port
        assertEquals(URI.create("ws://localhost:8080/comms"), factory.uris.get(0));
        // the first frame is the attach naming the channel and the identity
        AttachClientMessage attach = attachOf(factory.last());
        assertEquals(CellCacheConnectionType.CLIENT_TYPE, attach.getClientType());
        assertEquals("alice", attach.getProperties().getProperty("username"));
        // the handshake frames were consumed by the session, not delivered
        assertThat(cache.received).isEmpty();
        assertEquals(ClientConnection.Status.CONNECTED, cache.getStatus());
        assertEquals(SESSION, session.getID());
        assertEquals(ALICE.getUsername(), session.getUserID().getUsername());
        assertEquals(WonderlandSession.Status.CONNECTED, session.getStatus());
    }

    @Test
    void theSecondChannelEchoesTheSessionIdTheFirstWasGiven() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        session.connect(new TestConnection(CellCacheConnectionType.CLIENT_TYPE),
                new Properties());

        session.connect(new TestConnection(CellChannelConnectionType.CLIENT_TYPE),
                new Properties());

        AttachClientMessage second = attachOf(factory.last());
        assertEquals(CellChannelConnectionType.CLIENT_TYPE, second.getClientType());
        assertEquals(SESSION.toString(16),
                second.getProperties().getProperty(
                        WebSocketWonderlandSession.SESSION_ID_KEY));
        // one session id for the whole fan-out: the server reuses, not re-mints
        assertEquals(SESSION, session.getID());
    }

    @Test
    void dataFramesCarryTheWireIdTheAttachReplyAssigned() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());

        Message view = CellHierarchyMessage.newSetAvatarMessage("view-1");
        session.send(cache, view);

        MessagePacker.ReceivedMessage frame = unpack(factory.last().sent.get(1));
        assertEquals(WIRE_ID, frame.getClientID());
        assertEquals(view.getClass(), frame.getMessage().getClass());
    }

    @Test
    void framesAfterTheHandshakeReachTheOwningConnection() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        TestConnection edit = new TestConnection(CellEditConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());
        session.connect(edit, new Properties());

        // a server push lands on its own channel only -- the dispatch table is
        // per-socket, so a hierarchy LOAD cannot walk into the edit connection
        factory.transports.get(0).push(CellHierarchyMessage.newSetAvatarMessage("v"));

        assertThat(cache.received).hasSize(1);
        assertThat(edit.received).isEmpty();
    }

    @Test
    void aSilentServerFailsTheConnectAndClosesTheSocket() {
        FakeFactory factory = new FakeFactory();
        factory.answerAttach = false;
        WebSocketWonderlandSession session = new WebSocketWonderlandSession(
                new WonderlandServerInfo("localhost", 8080), identity(),
                factory, Duration.ofMillis(150));
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);

        assertThrows(ConnectionFailureException.class,
                () -> session.connect(cache, new Properties()));

        assertTrue(factory.last().closed, "the unanswered socket was reaped");
        assertEquals(ClientConnection.Status.DISCONNECTED, cache.getStatus());
        assertEquals(WonderlandSession.Status.DISCONNECTED, session.getStatus());
    }

    @Test
    void aRefusedSocketFailsTheConnect() {
        FakeFactory factory = new FakeFactory();
        factory.throwOnOpen = new IOException("no route to host");
        WebSocketWonderlandSession session = session(factory);

        assertThatThrownBy(() -> session.connect(
                new TestConnection(CellCacheConnectionType.CLIENT_TYPE), new Properties()))
                .isInstanceOf(ConnectionFailureException.class)
                .hasMessageContaining("/comms");
        assertEquals(WonderlandSession.Status.DISCONNECTED, session.getStatus());
    }

    @Test
    void aSecondChannelOfTheSameTypeIsRefused() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        session.connect(new TestConnection(CellCacheConnectionType.CLIENT_TYPE),
                new Properties());
        int socketsBefore = factory.transports.size();

        assertThatThrownBy(() -> session.connect(
                new TestConnection(CellCacheConnectionType.CLIENT_TYPE), new Properties()))
                .isInstanceOf(ConnectionFailureException.class)
                .hasMessageContaining("already connected");

        // the refused duplicate reaped its own socket and left the first alive
        assertEquals(socketsBefore + 1, factory.transports.size());
        assertTrue(factory.last().closed);
        assertFalse(factory.transports.get(0).closed);
    }

    @Test
    void disconnectingTheLastChannelEndsTheSession() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());

        session.disconnect(cache);

        assertTrue(factory.transports.get(0).closed);
        assertEquals(ClientConnection.Status.DISCONNECTED, cache.getStatus());
        assertEquals(WonderlandSession.Status.DISCONNECTED, session.getStatus());
        assertThat(session.getConnections()).isEmpty();
        assertThat(session.getConnection(CellCacheConnectionType.CLIENT_TYPE)).isNull();
    }

    @Test
    void aWriteFailureDropsTheChannelItCouldNotReach() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());
        factory.last().throwOnSend = new IOException("broken pipe");

        assertThatThrownBy(() -> session.send(cache,
                CellHierarchyMessage.newSetAvatarMessage("v")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed");

        assertTrue(factory.last().closed);
        assertThat(session.getConnections()).isEmpty();
    }

    @Test
    void theSessionReportsItsConnectionsAndServerInfo() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());

        assertThat(session.getServerInfo().getHostname()).isEqualTo("localhost");
        assertEquals(8080, session.getServerInfo().getSgsPort());
        assertThat(session.getConnections()).containsExactly(cache);
        assertEquals(cache, session.getConnection(
                CellCacheConnectionType.CLIENT_TYPE, TestConnection.class));
    }

    /** A message that refuses to serialise, to pin the pack-failure path. */
    private static final class BadMessage extends Message {
        private final Object notSerializable = new Object();
    }

    @Test
    void connectWithoutPropertiesOrIdentityStillAttaches() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = new WebSocketWonderlandSession(
                new WonderlandServerInfo("localhost", 8080), null,
                factory, Duration.ofSeconds(5));
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        TestConnection channel = new TestConnection(CellChannelConnectionType.CLIENT_TYPE);

        session.connect(cache);            // the one-argument overload
        session.connect(channel, null);    // and a null caller-props bag

        assertEquals(2, factory.transports.size());
        assertEquals(ClientConnection.Status.CONNECTED, cache.getStatus());
        assertEquals(ClientConnection.Status.CONNECTED, channel.getStatus());
    }

    @Test
    void anInterruptedHandshakeFailsTheConnectAndRestoresTheFlag() throws Exception {
        FakeFactory factory = new FakeFactory();
        factory.answerAttach = false;
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        Thread.currentThread().interrupt();
        try {
            assertThrows(ConnectionFailureException.class,
                    () -> session.connect(cache, new Properties()));
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt was re-raised for the caller");
        }
        assertTrue(factory.last().closed);
        assertEquals(WonderlandSession.Status.DISCONNECTED, session.getStatus());
    }

    @Test
    void anAttachThatCannotBeWrittenClosesTheSocketItOpened() {
        boolean[] closed = new boolean[1];
        // the socket opens, but the very first write fails
        WebSocketWonderlandSession session = new WebSocketWonderlandSession(
                new WonderlandServerInfo("localhost", 8080), identity(),
                (uri, inbound) -> new WebSocketWonderlandSession.Transport() {
                    @Override
                    public void send(ByteBuffer frame) throws IOException {
                        throw new IOException("network gone");
                    }

                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                },
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> session.connect(
                new TestConnection(CellCacheConnectionType.CLIENT_TYPE), new Properties()))
                .isInstanceOf(ConnectionFailureException.class)
                .hasMessageContaining("attach of channel");
        assertTrue(closed[0]);
        assertThat(session.getConnections()).isEmpty();
    }

    @Test
    void aFailedSecondChannelLeavesTheFirstConnected() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = new WebSocketWonderlandSession(
                new WonderlandServerInfo("localhost", 8080), identity(),
                factory, Duration.ofMillis(150));
        session.connect(new TestConnection(CellCacheConnectionType.CLIENT_TYPE),
                new Properties());
        factory.answerAttach = false; // the second socket never gets an answer

        assertThrows(ConnectionFailureException.class,
                () -> session.connect(
                        new TestConnection(CellChannelConnectionType.CLIENT_TYPE),
                        new Properties()));

        // the live cache channel keeps the session CONNECTED through the failure
        assertEquals(WonderlandSession.Status.CONNECTED, session.getStatus());
        assertThat(session.getConnections()).hasSize(1);
    }

    @Test
    void sendAndDisconnectWithoutAChannelFailFastAndQuiet() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);

        assertThatThrownBy(() -> session.send(cache,
                CellHierarchyMessage.newSetAvatarMessage("v")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no socket");
        session.disconnect(cache); // an unknown channel: no throw, no socket to close
        assertThat(session.getConnections()).isEmpty();
    }

    @Test
    void undecodableFramesAreDroppedAndTheConnectionSurvives() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());

        // bytes that are not a serialisation stream at all
        factory.last().inbound.accept(ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}));
        // a stream header that opens and then runs out mid-object
        factory.last().inbound.accept(ByteBuffer.wrap(
                new byte[] {(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x00, 0x00}));
        // and a frame for a channel that is already gone: dropped silently
        session.disconnect(cache);
        factory.last().inbound.accept(ByteBuffer.wrap(new byte[] {9, 9, 9}));

        assertThat(cache.received).isEmpty();
    }

    @Test
    void handshakeRepliesAfterTheHandshakeAreDeliveredLikeAnyFrame() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());

        // a server that re-sends its ids mid-session is odd but not fatal: the
        // session stops claiming the handshake frames and delivers them
        factory.last().push(new SessionInitializationMessage(SESSION, ALICE));

        assertThat(cache.received).hasSize(1);
    }

    @Test
    void aCloseThatThrowsIsNoiseTheCallerNeverHears() throws Exception {
        WebSocketWonderlandSession session = new WebSocketWonderlandSession(
                new WonderlandServerInfo("localhost", 8080), identity(),
                (uri, inbound) -> new WebSocketWonderlandSession.Transport() {
                    @Override
                    public void send(ByteBuffer frame) throws IOException {
                        AttachClientMessage attach =
                                (AttachClientMessage) unpack(frame).getMessage();
                        inbound.accept(pack(new AttachedClientMessage(
                                attach.getMessageID(), WIRE_ID), (short) 0));
                        inbound.accept(pack(new SessionInitializationMessage(
                                SESSION, ALICE), (short) 0));
                    }

                    @Override
                    public void close() {
                        throw new IllegalStateException("socket already reaped");
                    }
                },
                Duration.ofSeconds(5));
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());

        session.disconnect(cache); // the closeQuietly catch swallows the failure
    }

    @Test
    void aMessageThatWillNotSerialiseFailsTheSendAndDropsTheChannel() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        TestConnection cache = new TestConnection(CellCacheConnectionType.CLIENT_TYPE);
        session.connect(cache, new Properties());

        assertThatThrownBy(() -> session.send(cache, new BadMessage()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed");
        assertThat(session.getConnections()).isEmpty();
    }

    @Test
    void statusListenersSeeEachTransitionOnce() throws Exception {
        FakeFactory factory = new FakeFactory();
        WebSocketWonderlandSession session = session(factory);
        List<WonderlandSession.Status> seen = new ArrayList<>();
        session.addSessionStatusListener((s, status) -> seen.add(status));

        session.connect(new TestConnection(CellCacheConnectionType.CLIENT_TYPE),
                new Properties());
        session.removeSessionStatusListener(
                (s, status) -> seen.add(status)); // a listener never added: no-op

        assertThat(seen).containsExactly(WonderlandSession.Status.CONNECTING,
                WonderlandSession.Status.CONNECTED);
    }

    @Test
    void thePanelServerStringBecomesTheSocketAddress() {
        WonderlandServerInfo plain =
                WebSocketWonderlandSession.serverInfoOf("http://localhost:8080");
        assertEquals("localhost", plain.getHostname());
        assertEquals(8080, plain.getSgsPort());
        assertEquals(URI.create("ws://lanhost:9090/comms"),
                WebSocketWonderlandSession.commsUri(
                        WebSocketWonderlandSession.serverInfoOf("http://lanhost:9090")));
        // an address with no port means the scheme's own default
        assertEquals(443, WebSocketWonderlandSession
                .serverInfoOf("https://wonderland.example").getSgsPort());
        assertEquals(80, WebSocketWonderlandSession
                .serverInfoOf("http://wonderland.example").getSgsPort());
    }

    @Test
    void forServerBuildsARealSessionFromThePanelString() {
        WebSocketWonderlandSession session =
                WebSocketWonderlandSession.forServer("http://localhost:8080", identity());
        assertEquals(URI.create("ws://localhost:8080/comms"),
                session.commsUri);
        assertEquals(WonderlandSession.Status.DISCONNECTED, session.getStatus());
    }

    // ---- the java.net.http half, driven without a live socket ----------------

    /** A WebSocket stub: records sends, answers requests, delivers nothing. */
    private static final class StubSocket implements WebSocket {
        final List<ByteBuffer> binaries = new ArrayList<>();
        int requests;
        int closeCode = -1;
        boolean failNext;

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean isLast) {
            if (failNext) {
                return CompletableFuture.failedFuture(new IOException("socket closed"));
            }
            binaries.add(data);
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeCode = statusCode;
            if (failNext) {
                return CompletableFuture.failedFuture(new IOException("socket closed"));
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
            requests += (int) n;
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean isLast) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getSubprotocol() {
            return null;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public void abort() {
            // nothing to tear down in a stub
        }
    }

    @Test
    void theTransportWritesWholeBinaryFramesAndClosesOnce() throws Exception {
        StubSocket socket = new StubSocket();
        WebSocketWonderlandSession.Transport transport =
                new WebSocketWonderlandSession.WebSocketTransport(socket);

        transport.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        transport.close();

        assertThat(socket.binaries).hasSize(1);
        assertEquals(WebSocket.NORMAL_CLOSURE, socket.closeCode);
    }

    @Test
    void theTransportMapsAFailedSendToAnIoFailureAndACloseFailureToSilence() {
        StubSocket socket = new StubSocket();
        socket.failNext = true;
        WebSocketWonderlandSession.Transport transport =
                new WebSocketWonderlandSession.WebSocketTransport(socket);

        assertThrows(IOException.class,
                () -> transport.send(ByteBuffer.wrap(new byte[] {1})));
        transport.close(); // the close failure is swallowed by design
        assertEquals(WebSocket.NORMAL_CLOSURE, socket.closeCode);
    }

    @Test
    void partialBinaryMessagesReassembleIntoOneFrame() {
        List<ByteBuffer> frames = new ArrayList<>();
        WebSocketWonderlandSession.FrameReader reader =
                new WebSocketWonderlandSession.FrameReader(frames::add);
        StubSocket socket = new StubSocket();

        byte[] packed = pack(CellHierarchyMessage.newSetAvatarMessage("v"),
                WIRE_ID).array();
        // split the one frame across three callbacks, the way the socket may
        reader.onBinary(socket, ByteBuffer.wrap(packed, 0, 5), false);
        reader.onBinary(socket, ByteBuffer.wrap(packed, 5, packed.length - 10), false);
        reader.onBinary(socket, ByteBuffer.wrap(packed, packed.length - 5, 5), true);
        reader.onOpen(socket);
        reader.onError(socket, new IOException("gone"));

        assertThat(frames).hasSize(1);
        assertEquals(CellHierarchyMessage.newSetAvatarMessage("v").getClass(),
                unpack(frames.get(0)).getMessage().getClass());
        assertTrue(socket.requests >= 4); // every callback asked for more
    }

    @Test
    void theRealFactoryReportsARefusedServerAsAnIoFailure() {
        WebSocketWonderlandSession.TransportFactory factory =
                WebSocketWonderlandSession.webSocketFactory();
        // port 1 on loopback refuses fast, and the mapping is what is pinned
        AtomicInteger delivered = new AtomicInteger();
        assertThrows(IOException.class, () -> factory.connect(
                URI.create("ws://127.0.0.1:1/comms"), f -> delivered.incrementAndGet()));
        assertEquals(0, delivered.get());
    }

    /**
     * A minimal RFC 6455 server on a raw ServerSocket: enough of the handshake
     * and framing to prove the java.net.http half end to end -- the factory
     * connects, the transport's send lands as a real masked client frame, the
     * FrameReader reassembles a real server frame, and close sends a close
     * frame. The wonderland-server /comms test does the same over Spring's own
     * WebSocket implementation; this one needs nothing but the JDK.
     */
    @Test
    @Timeout(30)
    void theRealFactorySpeaksRealWebSocketFrames() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<byte[]> clientFrame = new CompletableFuture<>();
            CompletableFuture<byte[]> echo = new CompletableFuture<>();
            Thread acceptor = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = socket.getInputStream();
                    readHandshake(in, socket.getOutputStream());
                    byte[] request = readFrame(in);
                    clientFrame.complete(request);
                    writeFrame(socket.getOutputStream(), request); // echo back
                    readFrame(in);                                 // the close frame
                } catch (IOException | RuntimeException gone) {
                    // the test's own asserts report the real failure
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            WebSocketWonderlandSession.TransportFactory factory =
                    WebSocketWonderlandSession.webSocketFactory();
            WebSocketWonderlandSession.Transport transport = factory.connect(
                    URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/comms"),
                    frame -> {
                        byte[] bytes = new byte[frame.remaining()];
                        frame.get(bytes);
                        echo.complete(bytes);
                    });

            byte[] packed = pack(CellHierarchyMessage.newSetAvatarMessage("v"),
                    WIRE_ID).array();
            transport.send(ByteBuffer.wrap(packed.clone()));
            assertArrayEquals(packed, clientFrame.get(10, TimeUnit.SECONDS));
            assertArrayEquals(packed, echo.get(10, TimeUnit.SECONDS));
            transport.close();
        }
    }

    /** Answer the client's opening handshake, byte-wise so no frame byte is eaten. */
    private static void readHandshake(InputStream in, OutputStream out)
            throws IOException {
        StringBuilder headers = new StringBuilder();
        int current;
        while ((current = in.read()) >= 0) {
            headers.append((char) current);
            // the request ends at the blank line: the second CRLF in a row
            if (headers.length() >= 4
                    && headers.charAt(headers.length() - 4) == '\r'
                    && headers.charAt(headers.length() - 3) == '\n'
                    && headers.charAt(headers.length() - 2) == '\r'
                    && headers.charAt(headers.length() - 1) == '\n') {
                break;
            }
        }
        String key = null;
        for (String line : headers.toString().split("\\r?\\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.substring(18).trim();
            }
        }
        assertNotNull(key, "the client sent no Sec-WebSocket-Key");
        String accept;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                            .getBytes(StandardCharsets.US_ASCII));
            accept = Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Read one client frame (masked, per RFC 6455) and return its payload. */
    private static byte[] readFrame(InputStream in) throws IOException {
        int first = in.read();
        int second = in.read();
        if (second < 0) {
            throw new IOException("stream ended between frames");
        }
        int opcode = first & 0x0F;
        if (opcode == 0x8) { // close
            throw new IOException("close frame");
        }
        int length = second & 0x7F;
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            throw new IOException("frame too large for this test");
        }
        boolean masked = (second & 0x80) != 0;
        byte[] mask = new byte[4];
        if (masked) {
            readFully(in, mask);
        }
        byte[] payload = new byte[length];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        return payload;
    }

    /** Write one unmasked binary server frame. */
    private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        out.write(0x82); // FIN | binary
        if (payload.length < 126) {
            out.write(payload.length);
        } else {
            out.write(126);
            out.write(payload.length >> 8);
            out.write(payload.length & 0xFF);
        }
        out.write(payload);
        out.flush();
    }

    private static void readFully(InputStream in, byte[] out) throws IOException {
        int read = 0;
        while (read < out.length) {
            int step = in.read(out, read, out.length - read);
            if (step < 0) {
                throw new IOException("stream ended mid-frame");
            }
            read += step;
        }
    }
}
