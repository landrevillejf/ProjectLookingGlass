/*
 * wonderland-server M3a test harness: the in-JVM loopback transport. It is the
 * single-JVM stand-in for M3b's WebSocket socket: it implements the CLIENT's
 * WonderlandSession interface (so a real wonderland-client CellClientSession
 * connects to it unchanged) but every message it "sends" is packed with the real
 * MessagePacker, routed through the real Spring ProtocolRouter + SessionRegistry
 * + WorldModel, and the replies/broadcasts are unpacked before being delivered to
 * the client connection. In other words the whole client<->server stack runs over
 * one JVM through the real M1 codec -- exactly the "single-JVM loopback" the M3
 * plan asks for before sockets arrive.
 *
 * The FrameSink it registers on its own ServerSession is what lets the SERVER push
 * unsolicited frames back (the world fan-out after SET_VIEW, and the cross-session
 * move broadcast when this loopback is the *other* session).
 *
 * Lives in test scope, not main: it imports wonderland-client, and the production
 * server must never depend on the client (docs/wonderland-migration.md module rule).
 */
package org.jdesktop.wonderland.server.loopback;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jdesktop.wonderland.client.comms.ClientConnection;
import org.jdesktop.wonderland.client.comms.ConnectionFailureException;
import org.jdesktop.wonderland.client.comms.SessionStatusListener;
import org.jdesktop.wonderland.client.comms.WonderlandServerInfo;
import org.jdesktop.wonderland.client.comms.WonderlandSession;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.jdesktop.wonderland.server.comms.ProtocolRouter;
import org.jdesktop.wonderland.server.comms.ServerSession;
import org.jdesktop.wonderland.server.comms.SessionRegistry;

public class LoopbackWonderlandSession implements WonderlandSession {

    private final ProtocolRouter router;
    private final SessionRegistry registry;
    private final ServerSession serverSession;
    private final Map<ConnectionType, ClientConnection> connections = new LinkedHashMap<>();
    private final List<SessionStatusListener> listeners = new ArrayList<>();
    private final WonderlandServerInfo serverInfo;
    private Status status = Status.DISCONNECTED;

    public LoopbackWonderlandSession(ProtocolRouter router, SessionRegistry registry,
            WonderlandIdentity user, String serverName) {
        this.router = router;
        this.registry = registry;
        this.serverSession = registry.create(user, this::deliverFrame);
        this.serverInfo = new WonderlandServerInfo(serverName, 0);
    }

    public ServerSession getServerSession() {
        return serverSession;
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
        return serverSession.getID();
    }

    @Override
    public WonderlandIdentity getUserID() {
        return serverSession.getUserID();
    }

    @Override
    public void connect(ClientConnection connection) throws ConnectionFailureException {
        connect(connection, new Properties());
    }

    @Override
    public void connect(ClientConnection connection, Properties properties)
            throws ConnectionFailureException {
        connections.put(connection.getConnectionType(), connection);
        router.attach(serverSession, connection.getConnectionType());
        connection.connected(this);
        setStatus(Status.CONNECTED);
    }

    @Override
    public void disconnect(ClientConnection connection) {
        router.detach(serverSession, connection.getConnectionType());
        connections.remove(connection.getConnectionType());
        connection.disconnected();
        if (connections.isEmpty()) {
            // the transport saw its last channel go: evict the server-side session
            // (the WebSocket handler's afterConnectionClosed does the same in M3b)
            registry.close(serverSession.getID());
            setStatus(Status.DISCONNECTED);
        }
    }

    @Override
    public ClientConnection getConnection(ConnectionType connectionType) {
        return connections.get(connectionType);
    }

    @Override
    public <T extends ClientConnection> T getConnection(ConnectionType connectionType,
            Class<T> clazz) {
        return clazz.cast(connections.get(connectionType));
    }

    @Override
    public Collection<ClientConnection> getConnections() {
        return connections.values();
    }

    @Override
    public void send(ClientConnection connection, Message message) {
        short clientID = serverSession.clientID(connection.getConnectionType());
        ByteBuffer packed = pack(message, clientID);
        for (ByteBuffer reply : router.routeInbound(
                serverSession, connection.getConnectionType(), packed)) {
            deliverFrame(serverSession, connection.getConnectionType(), reply);
        }
        // the router's post-reply frames (the view-cell chain) are NOT delivered
        // here: send() runs inside the synchronous request-reply (registerView's
        // sendAndWait), so the client has not yet stored its viewCellID -- a view
        // LOAD walked here would hit ViewCell.setStatus against a null id (the
        // 2006 async socket delivered them only after sendAndWait returned).
        // pump() delivers them at that safe point, the loopback's stand-in for the
        // reader thread that would hand them over after the handshake completed.
    }

    /**
     * Drain the server's post-reply frames to the cache connection -- the
     * deterministic reproduction of the async socket delivering the view-cell
     * chain once the client has recorded its viewCellID. Called by the test
     * harness after the connection handshake, exactly where a socket reader
     * thread would deliver them.
     */
    public void pump() {
        ByteBuffer pending;
        while ((pending = router.flush(serverSession)) != null) {
            deliverFrame(serverSession, CellCacheConnectionType.CLIENT_TYPE, pending);
        }
    }

    /** The server's outbound seam: unpack a frame and hand it to the local client. */
    private void deliverFrame(ServerSession target, ConnectionType type, ByteBuffer frame) {
        ClientConnection connection = connections.get(type);
        if (connection == null) {
            return;
        }
        connection.messageReceived(unpack(frame).getMessage());
    }

    @Override
    public void addSessionStatusListener(SessionStatusListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeSessionStatusListener(SessionStatusListener listener) {
        listeners.remove(listener);
    }

    private void setStatus(Status next) {
        status = next;
        for (SessionStatusListener l : new ArrayList<>(listeners)) {
            l.sessionStatusChanged(this, next);
        }
    }

    private ByteBuffer pack(Message message, short clientID) {
        try {
            return MessagePacker.pack(message, clientID);
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException("loopback failed to pack " + message, ex);
        }
    }

    private MessagePacker.ReceivedMessage unpack(ByteBuffer buffer) {
        try {
            MessagePacker.ReceivedMessage received = MessagePacker.unpack(buffer);
            if (received == null) {
                throw new MessagePacker.PackerException();
            }
            return received;
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException("loopback failed to unpack a reply", ex);
        }
    }
}
