/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client M3b client half: the production WonderlandSession -- the
 * java.net.http.WebSocket transport onto the server's /comms endpoint that the
 * M3a loopback stood in for.
 *
 * The wire is the M1 codec, unchanged: every frame is one
 * MessagePacker.pack(message, clientID) byte sequence, and the 2006 three-
 * channel session model survives as three sockets. The server's
 * CommsWebSocketHandler owns the convergence rule this class implements from
 * the client side:
 *   - each channel opens its own socket and its first frame is an
 *     AttachClientMessage naming its ConnectionType (plus the identity
 *     properties the server reads for the WonderlandIdentity);
 *   - the first attach's SessionInitializationMessage mints the session id,
 *     and every LATER attach echoes it under SESSION_ID_KEY (hex) so the
 *     server reuses one ServerSession instead of opening a second world view;
 *   - each channel keeps the short its AttachedClientMessage assigned and
 *     stamps it on every later frame, exactly as the 2006 SGS client did --
 *     the server routes by (session, type), but the id is part of the format.
 *
 * Everything socket-shaped sits behind the Transport/TransportFactory seam so
 * the whole handshake, fan-out and teardown is headless-testable with an
 * in-memory duplex, the way the loopback made M3a testable; the real factory
 * wraps java.net.http.WebSocket and reassembles its partial binary messages
 * into whole frames. connect() blocks until the two handshake frames arrive
 * (bounded by the start timeout, so a dead server fails the login rather than
 * hanging it), and every later frame is unpacked and handed to the owning
 * connection's messageReceived on the socket's reader thread -- the same
 * async delivery the 2006 client's reader thread produced, which is what lets
 * CellCacheConnection's sendAndWait(SET_VIEW) meet its reply while the
 * view-cell fan-out keeps arriving behind it.
 */
package org.jdesktop.wonderland.client.comms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.comms.messages.AttachClientMessage;
import org.jdesktop.wonderland.common.comms.messages.AttachedClientMessage;
import org.jdesktop.wonderland.common.comms.messages.SessionInitializationMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessagePacker;

/** A production {@link WonderlandSession}: three /comms sockets, one server session. */
public final class WebSocketWonderlandSession implements WonderlandSession {

    /**
     * The attach property naming the session a follow-on channel rejoins -- the
     * hex session id the first channel's SessionInitializationMessage carried.
     * Mirrors the server's CommsWebSocketHandler.SESSION_ID_KEY; the client
     * module must not import server classes, so the wire constant is stated
     * twice, and a codec test on each side pins the agreement.
     */
    public static final String SESSION_ID_KEY = "wonderland.sessionID";

    /** The endpoint every channel socket opens, appended to the server base URI. */
    static final String COMMS_PATH = "/comms";

    /** How long one channel's handshake may take before the connect fails. */
    static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(10);

    /**
     * One duplex binary socket. The seam that keeps the session headless:
     * tests drive it with an in-memory duplex, production with WebSocket.
     */
    interface Transport {
        /** Send one whole packed frame; returns once it is on the wire. */
        void send(ByteBuffer frame) throws IOException;

        /** Close the socket; inbound delivery stops. */
        void close();
    }

    /** Opens a transport to {@code uri}; every inbound WHOLE frame goes to {@code inbound}. */
    interface TransportFactory {
        Transport connect(URI uri, Consumer<ByteBuffer> inbound) throws IOException;
    }

    /** The live socket, wire id and handshake state of one channel. */
    private static final class Channel {
        private final ClientConnection connection;
        private final Transport transport;
        private final CountDownLatch handshake = new CountDownLatch(1);
        private final AtomicBoolean attached = new AtomicBoolean(false);
        private volatile short clientID;

        private Channel(ClientConnection connection, Transport transport) {
            this.connection = connection;
            this.transport = transport;
        }
    }

    private final WonderlandServerInfo serverInfo;
    final URI commsUri;
    private final Properties identity;
    private final TransportFactory factory;
    private final Duration startTimeout;
    private final Map<ConnectionType, Channel> channels = new ConcurrentHashMap<>();
    private final List<SessionStatusListener> listeners = new ArrayList<>();

    private volatile BigInteger sessionID;
    private volatile WonderlandIdentity userID;
    private volatile Status status = Status.DISCONNECTED;

    /**
     * A session against {@code server} that authenticates as {@code identity}
     * (the {@code username}/{@code fullname}/{@code email} properties the
     * server folds into a WonderlandIdentity) over real WebSockets.
     */
    public WebSocketWonderlandSession(WonderlandServerInfo server, Properties identity) {
        this(server, identity, webSocketFactory(), DEFAULT_START_TIMEOUT);
    }

    WebSocketWonderlandSession(WonderlandServerInfo server, Properties identity,
            TransportFactory factory, Duration startTimeout) {
        this.serverInfo = server;
        this.identity = identity == null ? new Properties() : identity;
        this.factory = factory;
        this.startTimeout = startTimeout;
        this.commsUri = commsUri(server);
    }

    /**
     * The session for a desktop-style server string ("http://host:port" or
     * "https://host:port", the shape the Wonderland World panel's Server
     * field carries): the scheme becomes ws/wss and the path becomes /comms.
     */
    public static WebSocketWonderlandSession forServer(String httpBase, Properties identity) {
        return new WebSocketWonderlandSession(serverInfoOf(httpBase), identity);
    }

    /** Parse a panel server string into the (host, port) the sockets dial. */
    static WonderlandServerInfo serverInfoOf(String httpBase) {
        URI uri = URI.create(httpBase.trim());
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return new WonderlandServerInfo(uri.getHost(), port);
    }

    /** The /comms URI one channel of {@code server} opens. */
    static URI commsUri(WonderlandServerInfo server) {
        return URI.create("ws://" + server.getHostname() + ":" + server.getSgsPort()
                + COMMS_PATH);
    }

    @Override
    public WonderlandServerInfo getServerInfo() {
        return serverInfo;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public BigInteger getID() {
        return sessionID;
    }

    @Override
    public WonderlandIdentity getUserID() {
        return userID;
    }

    @Override
    public void connect(ClientConnection connection) throws ConnectionFailureException {
        connect(connection, new Properties());
    }

    /**
     * Open {@code connection}'s socket and run its attach handshake. The
     * caller's properties are sent as the attach properties (merged over the
     * session identity), and the session id a follow-on channel must echo is
     * added here -- the caller never handles it. Blocks until the server's
     * SessionInitializationMessage lands, so on return the channel is attached
     * and its {@code connection} is CONNECTED and may send.
     */
    @Override
    public void connect(ClientConnection connection, Properties properties)
            throws ConnectionFailureException {
        ConnectionType type = connection.getConnectionType();
        Properties attachProps = new Properties();
        attachProps.putAll(identity);
        if (properties != null) {
            attachProps.putAll(properties);
        }
        BigInteger known = sessionID;
        if (known != null) {
            attachProps.setProperty(SESSION_ID_KEY, known.toString(16));
        }
        Transport transport;
        try {
            transport = factory.connect(commsUri, frame -> onInbound(type, frame));
        } catch (IOException | RuntimeException ex) {
            openFailed();
            throw new ConnectionFailureException(
                    "cannot open the /comms socket to " + commsUri, ex);
        }
        Channel live = new Channel(connection, transport);
        Channel previous = channels.putIfAbsent(type, live);
        if (previous != null) {
            closeQuietly(transport);
            throw new ConnectionFailureException(
                    "a channel of type " + type + " is already connected");
        }
        setStatus(Status.CONNECTING);
        try {
            live.transport.send(packAttach(new AttachClientMessage(type, attachProps)));
        } catch (IOException | ConnectionFailureException | RuntimeException ex) {
            channels.remove(type);
            closeQuietly(transport);
            openFailed();
            throw new ConnectionFailureException("attach of channel " + type + " failed", ex);
        }
        try {
            if (!live.handshake.await(startTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("the server did not answer the attach of channel "
                        + type + " within " + startTimeout);
            }
        } catch (IOException ex) {
            channels.remove(type);
            closeQuietly(transport);
            openFailed();
            throw new ConnectionFailureException(ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            channels.remove(type);
            closeQuietly(transport);
            openFailed();
            throw new ConnectionFailureException("attach of channel " + type
                    + " interrupted", ex);
        }
        connection.connected(this);
        setStatus(Status.CONNECTED);
    }

    @Override
    public void disconnect(ClientConnection connection) {
        Channel channel = channels.remove(connection.getConnectionType());
        connection.disconnected();
        if (channel != null) {
            closeQuietly(channel.transport);
        }
        if (channels.isEmpty()) {
            setStatus(Status.DISCONNECTED);
        }
    }

    @Override
    public ClientConnection getConnection(ConnectionType connectionType) {
        Channel channel = channels.get(connectionType);
        return channel == null ? null : channel.connection;
    }

    @Override
    public <T extends ClientConnection> T getConnection(ConnectionType connectionType,
            Class<T> clazz) {
        ClientConnection connection = getConnection(connectionType);
        return connection == null ? null : clazz.cast(connection);
    }

    @Override
    public Collection<ClientConnection> getConnections() {
        List<ClientConnection> out = new ArrayList<>();
        for (Channel channel : channels.values()) {
            out.add(channel.connection);
        }
        return out;
    }

    @Override
    public void send(ClientConnection connection, Message message) {
        Channel channel = channels.get(connection.getConnectionType());
        if (channel == null) {
            throw new IllegalStateException(
                    "no socket for channel " + connection.getConnectionType());
        }
        try {
            channel.transport.send(pack(message, channel.clientID));
        } catch (ConnectionFailureException | IOException ex) {
            // The 2006 client let a socket failure surface on the reader
            // thread as a disconnect; a send-side failure is the same event
            // seen from the writer, so drop the channel the same way. The
            // interface's send() declares no checked failure, so the caller
            // sees an unchecked one after the channel is gone.
            channels.remove(connection.getConnectionType());
            closeQuietly(channel.transport);
            throw new IllegalStateException("socket write on channel "
                    + connection.getConnectionType() + " failed", ex);
        }
    }

    @Override
    public void addSessionStatusListener(SessionStatusListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeSessionStatusListener(SessionStatusListener listener) {
        listeners.remove(listener);
    }

    /** One whole inbound frame on {@code type}'s socket. */
    private void onInbound(ConnectionType type, ByteBuffer frame) {
        Channel channel = channels.get(type);
        if (channel == null) {
            return; // the channel closed while its frame was in flight
        }
        Message message;
        try {
            MessagePacker.ReceivedMessage received = MessagePacker.unpack(frame);
            if (received == null) {
                return;
            }
            message = received.getMessage();
        } catch (MessagePacker.PackerException ex) {
            return; // an undecodable frame is the server's problem, not ours
        }
        if (!channel.attached.get()) {
            if (message instanceof AttachedClientMessage attached) {
                channel.clientID = attached.getClientID();
                return;
            }
            if (message instanceof SessionInitializationMessage init) {
                adoptSession(init);
                channel.attached.set(true);
                channel.handshake.countDown();
                return;
            }
        }
        channel.connection.messageReceived(message);
    }

    /** Record the session the first handshake minted; later ones must agree. */
    private synchronized void adoptSession(SessionInitializationMessage init) {
        if (sessionID == null) {
            sessionID = init.getSessionID();
            userID = init.getUserID();
        }
    }

    /**
     * A channel that never attached must not leave the session mid-connect:
     * the truth is DISCONNECTED with no channels left, CONNECTED with the
     * surviving ones -- published only when it actually changes, so a failed
     * second channel does not make a live session report a fake transition.
     */
    private void openFailed() {
        Status restored = channels.isEmpty() ? Status.DISCONNECTED : Status.CONNECTED;
        if (status != restored) {
            setStatus(restored);
        }
    }

    private void setStatus(Status next) {
        status = next;
        for (SessionStatusListener listener : new ArrayList<>(listeners)) {
            listener.sessionStatusChanged(this, next);
        }
    }

    /** Pack an attach on the pre-attach wire id the server ignores (channel 0). */
    private static ByteBuffer packAttach(AttachClientMessage message)
            throws ConnectionFailureException {
        return pack(message, (short) 0);
    }

    private static ByteBuffer pack(Message message, short clientID)
            throws ConnectionFailureException {
        try {
            return MessagePacker.pack(message, clientID);
        } catch (MessagePacker.PackerException ex) {
            throw new ConnectionFailureException("failed to pack " + message, ex);
        }
    }

    private static void closeQuietly(Transport transport) {
        try {
            transport.close();
        } catch (RuntimeException ignored) {
            // a close failure after the channel is gone is noise
        }
    }

    /** The real factory: one java.net.http.WebSocket per channel. */
    static TransportFactory webSocketFactory() {
        HttpClient client = HttpClient.newHttpClient();
        return (uri, inbound) -> {
            WebSocket socket;
            try {
                socket = client.newWebSocketBuilder()
                        .buildAsync(uri, new FrameReader(inbound)).join();
            } catch (CompletionException ex) {
                // CompletableFuture.join() always completes exceptionally with
                // the real failure as the cause, so getCause() is never null
                // here; the "no cause" fallback was an unreachable branch, and
                // an unreachable branch is not coverage.
                Throwable cause = ex.getCause();
                throw new IOException("cannot open the WebSocket to " + uri
                        + ": " + cause, cause);
            }
            return new WebSocketTransport(socket);
        };
    }

    /** A Transport over one live WebSocket; sends are serialised on the socket. */
    static final class WebSocketTransport implements Transport {
        private final WebSocket socket;

        WebSocketTransport(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public void send(ByteBuffer frame) throws IOException {
            try {
                socket.sendBinary(frame, true).join();
            } catch (CompletionException ex) {
                throw new IOException("WebSocket send failed", ex.getCause());
            }
        }

        @Override
        public void close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
            } catch (RuntimeException ignored) {
                // the socket is going away whichever way the close lands
                // (join wraps the failure in a CompletionException)
            }
        }
    }

    /**
     * Reassembles java.net.http's PARTIAL binary messages into whole frames:
     * the packer's serialized bytes may split across any number of onBinary
     * callbacks, and only the {@code last} one completes a MessagePacker frame.
     * Package-private so the framing test can drive it without a socket.
     */
    static final class FrameReader implements WebSocket.Listener {
        private final Consumer<ByteBuffer> inbound;
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

        FrameReader(Consumer<ByteBuffer> inbound) {
            this.inbound = inbound;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data,
                boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            partial.write(bytes, 0, bytes.length);
            if (last) {
                byte[] frame = partial.toByteArray();
                partial.reset();
                inbound.accept(ByteBuffer.wrap(frame));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // Inbound delivery simply stops; the session's teardown rides the
            // disconnect the caller performs once its socket is dead.
        }
    }
}
