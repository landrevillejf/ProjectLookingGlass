/*
 * wonderland-server (M3b): the Spring WebSocket transport -- the piece that
 * replaces the GlassFish/MINA socket plumbing and puts the M3a router behind real
 * sockets. It is a BinaryWebSocketHandler: each WonderLAN channel is one socket,
 * its first binary frame is the AttachClientMessage handshake (SGS's login
 * surface, now served by the Spring session registry), and every later frame is
 * an M1 wire message the protocol router answers.
 *
 * The M3a scope note ("a socket owns its session") is lifted here. The faithful
 * SGS model is ONE authenticated session carrying the client's THREE cell
 * connections (cache / channel / edit) as separate channels. Over sockets those
 * are three separate TCP connections, so the handler must converge them onto one
 * ServerSession:
 *   - the FIRST attach for a login mints the session and answers with the
 *     SessionInitializationMessage carrying its BigInteger id;
 *   - each LATER attach echoes that id in its AttachClientMessage properties
 *     (the key below), so the handler reuses the existing ServerSession and
 *     attaches just the new connection type to it -- one registry entry per
 *     session, not per socket, which is what keeps the router's per-session
 *     semantics (echo suppression keyed on session id, broadcast scoping, the
 *     one view cell per session) intact;
 *   - the session's FrameSink is installed once, on the first socket, and
 *     DISPATCHES by ConnectionType through the live socket map, so a cache-
 *     targeted LOAD fan-out and a channel-targeted move broadcast each reach the
 *     right one of a session's sockets -- the exact behaviour the M3a loopback got
 *     for free because its single object held all three connections.
 *
 * Replies to a data frame are written straight back on the socket that asked;
 * the router's deferred post-reply frames (the view-cell chain) are delivered
 * through the session sink to the CACHE socket specifically, since that is the
 * connection they are packed for. A socket close detaches just its own channel;
 * the session (and its view cell) is torn down only when its last channel goes.
 */
package org.jdesktop.wonderland.server.ws;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.comms.messages.AttachClientMessage;
import org.jdesktop.wonderland.common.comms.messages.AttachedClientMessage;
import org.jdesktop.wonderland.common.comms.messages.SessionInitializationMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.jdesktop.wonderland.server.comms.FrameSink;
import org.jdesktop.wonderland.server.comms.ProtocolRouter;
import org.jdesktop.wonderland.server.comms.ServerSession;
import org.jdesktop.wonderland.server.comms.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

public class CommsWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger logger =
            LoggerFactory.getLogger(CommsWebSocketHandler.class);
    private static final String ATTR_SESSION = "wonderland.server.session";
    private static final String ATTR_TYPE = "wonderland.client.connectionType";

    /**
     * The AttachClientMessage property a follow-on channel carries to rejoin the
     * session its first channel opened -- the BigInteger session id in hex, the
     * value the server handed back in the SessionInitializationMessage. SGS keyed
     * its channels to a session the same way; the id is opaque to the client, so
     * it just echoes it back on the next attach.
     */
    public static final String SESSION_ID_KEY = "wonderland.sessionID";

    private final ProtocolRouter router;
    private final SessionRegistry registry;

    /**
     * Live socket per (session, connection type): the dispatch table behind every
     * session's shared FrameSink. A session's three channels are three sockets;
     * the sink looks up which one a type's frame must leave on.
     */
    private final Map<BigInteger, Map<ConnectionType, WebSocketSession>> channels =
            new ConcurrentHashMap<>();

    public CommsWebSocketHandler(ProtocolRouter router, SessionRegistry registry) {
        this.router = router;
        this.registry = registry;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        ByteBuffer payload = message.getPayload().asReadOnlyBuffer();
        ReceivedFrame frame = unpack(payload);
        if (frame == null) {
            return;
        }
        Message msg = frame.message;
        if (msg instanceof AttachClientMessage) {
            onAttach(socket, (AttachClientMessage) msg);
        } else {
            onData(socket, msg);
        }
    }

    private void onAttach(WebSocketSession socket, AttachClientMessage attach) {
        ConnectionType clientType = attach.getClientType();
        ServerSession serverSession = resolveSession(attach, clientType);
        // register THIS socket for its type before attaching, so the session's
        // FrameSink can already route a frame of this type to a live socket
        channels.computeIfAbsent(serverSession.getID(),
                k -> new ConcurrentHashMap<>()).put(clientType, socket);
        short clientID = router.attach(serverSession, clientType);
        socket.getAttributes().put(ATTR_SESSION, serverSession);
        socket.getAttributes().put(ATTR_TYPE, clientType);
        // the login handshake the client interface dropped: Attached with the
        // fresh client id, then SessionInitialization with the shared session id
        // the next channel will echo back in its attach properties
        write(socket, pack(new AttachedClientMessage(attach.getMessageID(), clientID),
                clientID));
        write(socket, pack(new SessionInitializationMessage(serverSession.getID(),
                serverSession.getUserID()), clientID));
    }

    /**
     * Reuse the session a follow-on channel names, or mint a new one for a first
     * attach. A stale/unknown id (or none) simply opens a fresh session, matching
     * the single-socket behaviour the M3a unit tests pinned.
     */
    private ServerSession resolveSession(AttachClientMessage attach, ConnectionType clientType) {
        BigInteger sessionId = parseSessionId(attach.getProperties());
        if (sessionId != null) {
            ServerSession existing = registry.get(sessionId);
            if (existing != null) {
                return existing;
            }
            logger.warn("attach of channel {} names unknown session {}; opening a new one",
                    clientType, sessionId);
        }
        FrameSink sink = (session, type, packed) -> writeToChannel(session.getID(), type, packed);
        return registry.create(identityFrom(attach.getProperties()), sink);
    }

    private void onData(WebSocketSession socket, Message msg) {
        ServerSession serverSession =
                (ServerSession) socket.getAttributes().get(ATTR_SESSION);
        ConnectionType clientType =
                (ConnectionType) socket.getAttributes().get(ATTR_TYPE);
        if (serverSession == null || clientType == null) {
            logger.warn("data frame on an un-attached socket {}", socket.getId());
            return;
        }
        // replies are packed for the acting connection, so they leave on that socket
        for (ByteBuffer reply : router.routeMessage(serverSession, clientType, msg)) {
            write(socket, reply);
        }
        // the router's post-reply frames are the view-cell chain packed for the
        // CACHE connection; deliver them through the session sink so they reach
        // the session's cache socket even when the frame that triggered the flush
        // arrived on another channel (matching the async ordering the client's
        // view handshake depends on -- see ProtocolRouter.flush)
        ByteBuffer pending;
        while ((pending = router.flush(serverSession)) != null) {
            serverSession.deliver(CellCacheConnectionType.CLIENT_TYPE, pending);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        ServerSession serverSession =
                (ServerSession) socket.getAttributes().get(ATTR_SESSION);
        ConnectionType clientType =
                (ConnectionType) socket.getAttributes().get(ATTR_TYPE);
        if (serverSession == null || clientType == null) {
            return;
        }
        // drop this channel's socket, then detach just this connection type: a
        // cache detach tears down the view cell and broadcasts its UNLOAD
        Map<ConnectionType, WebSocketSession> byType = channels.get(serverSession.getID());
        if (byType != null) {
            byType.remove(clientType);
            if (byType.isEmpty()) {
                channels.remove(serverSession.getID());
            }
        }
        router.detach(serverSession, clientType);
        // the session itself survives until its last channel is gone
        if (serverSession.attachedTypes().isEmpty()) {
            registry.close(serverSession.getID());
        }
    }

    /** Write one already-packed frame to the live socket registered for a type. */
    private void writeToChannel(BigInteger sessionId, ConnectionType type, ByteBuffer packed) {
        Map<ConnectionType, WebSocketSession> byType = channels.get(sessionId);
        if (byType == null) {
            return;
        }
        WebSocketSession socket = byType.get(type);
        if (socket != null) {
            write(socket, packed);
        }
    }

    private BigInteger parseSessionId(Properties properties) {
        if (properties == null) {
            return null;
        }
        String raw = properties.getProperty(SESSION_ID_KEY);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new BigInteger(raw, 16);
        } catch (NumberFormatException ex) {
            logger.warn("malformed {} attach property '{}'", SESSION_ID_KEY, raw);
            return null;
        }
    }

    private WonderlandIdentity identityFrom(Properties properties) {
        Properties props = properties == null ? new Properties() : properties;
        String username = props.getProperty("username", "anonymous");
        return new WonderlandIdentity(username,
                props.getProperty("fullname", username),
                props.getProperty("email", username + "@wonderland.invalid"));
    }

    private void write(WebSocketSession socket, ByteBuffer packed) {
        try {
            synchronized (socket) {
                if (socket.isOpen()) {
                    socket.sendMessage(new BinaryMessage(packed));
                }
            }
        } catch (IOException ex) {
            logger.warn("failed to write frame to socket {}", socket.getId(), ex);
        }
    }

    private ByteBuffer pack(Message message, short clientID) {
        try {
            return MessagePacker.pack(message, clientID);
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException("failed to pack " + message.getClass(), ex);
        }
    }

    private ReceivedFrame unpack(ByteBuffer buffer) {
        try {
            MessagePacker.ReceivedMessage received = MessagePacker.unpack(buffer);
            return received == null ? null
                    : new ReceivedFrame(received.getMessage(), received.getClientID());
        } catch (MessagePacker.PackerException ex) {
            logger.warn("failed to unpack inbound frame", ex);
            return null;
        }
    }

    /** The socket currently serving one connection type of a session, or null. */
    public WebSocketSession socketFor(BigInteger sessionId, ConnectionType type) {
        Map<ConnectionType, WebSocketSession> byType = channels.get(sessionId);
        return byType == null ? null : byType.get(type);
    }

    /** A decoded inbound frame: the message plus the short the wire stamped. */
    private static final class ReceivedFrame {
        private final Message message;
        private final short clientID;

        private ReceivedFrame(Message message, short clientID) {
            this.message = message;
            this.clientID = clientID;
        }
    }
}
