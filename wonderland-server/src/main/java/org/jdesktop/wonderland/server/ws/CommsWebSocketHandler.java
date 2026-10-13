/*
 * wonderland-server (M3): the Spring WebSocket transport -- the piece that
 * replaces the GlassFish/MINA socket plumbing. It is a BinaryWebSocketHandler:
 * each WonderLAN channel is one socket, its first binary frame is the
 * AttachClientMessage handshake (SGS's login surface, now served by the Spring
 * session registry), and every later frame is an M1 wire message the protocol
 * router answers. Replies and cross-session broadcasts are pushed back through
 * the session's FrameSink, which for a socket is simply sendMessage on that
 * socket -- so the codec path is identical to the in-JVM loopback M3a proves.
 *
 * M3a scope note: the handler compiles and its request/reply path is unit-tested
 * by driving handleBinaryMessage with packed frames against a stub WebSocketSession
 * (no port bound). Full multi-socket fan-out -- several sockets sharing one
 * authenticated session -- is a M3b concern; here a socket owns its session.
 */
package org.jdesktop.wonderland.server.ws;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Properties;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
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

    private final ProtocolRouter router;
    private final SessionRegistry registry;

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
        FrameSink sink = (session, type, packed) -> write(socket, packed);
        WonderlandIdentity user = identityFrom(attach.getProperties());
        ServerSession serverSession = registry.create(user, sink);
        short clientID = router.attach(serverSession, clientType);
        socket.getAttributes().put(ATTR_SESSION, serverSession);
        socket.getAttributes().put(ATTR_TYPE, clientType);
        // the login handshake the client interface dropped: Attached, then the
        // SessionInitialization carrying the server-minted id and identity
        write(socket, pack(new AttachedClientMessage(attach.getMessageID(), clientID),
                clientID));
        write(socket, pack(new SessionInitializationMessage(serverSession.getID(),
                serverSession.getUserID()), clientID));
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
        for (ByteBuffer reply : router.routeMessage(serverSession, clientType, msg)) {
            write(socket, reply);
        }
        // the router's post-reply frames (the view-cell chain, see ProtocolRouter
        // .flush): delivered after the reply list, matching the async ordering
        // the client's view handshake depends on
        ByteBuffer pending;
        while ((pending = router.flush(serverSession)) != null) {
            write(socket, pending);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        ServerSession serverSession =
                (ServerSession) socket.getAttributes().get(ATTR_SESSION);
        ConnectionType clientType =
                (ConnectionType) socket.getAttributes().get(ATTR_TYPE);
        if (serverSession != null && clientType != null) {
            router.detach(serverSession, clientType);
            registry.close(serverSession.getID());
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
