/*
 * wonderland-server M3b integration test: the whole server over REAL sockets on
 * an ephemeral port. @SpringBootTest(RANDOM_PORT) boots the one lifecycle that
 * replaces the retired GlassFish deployment descriptor -- embedded Tomcat serving
 * both the /comms WebSocket and the /api/world REST surface -- and a real
 * StandardWebSocketClient (the jakarta container spring-boot-starter-web brings)
 * drives the wire. Where M3a's loopback proved the client <-> router stack in one
 * JVM, this proves the transport M3b adds, frame for frame through the M1 codec:
 *   - the login handshake over a socket: an AttachClientMessage is answered
 *     Attached + SessionInitialization on the real pipe;
 *   - the multi-socket fan-out onto ONE session: a client's cache and channel
 *     connections are two distinct sockets that echo the minted session id and
 *     converge on a single SessionRegistry entry, and the shared FrameSink
 *     dispatches each ConnectionType's frame to the right one of those sockets;
 *   - a cross-session move over sockets: one session's channel move answers the
 *     mover MOVE_MODIFIED and reaches ANOTHER session's channel socket as a
 *     MOVED broadcast stamped with the mover's id (echo suppression survives);
 *   - the REST world surface on the same real port sees the cells the socket
 *     path created, so the wire view and the REST view cannot drift.
 * Handshake frames are read back through the codec, never as server objects.
 */
package org.jdesktop.wonderland.server.ws;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.jdesktop.wonderland.common.cell.CellEditConnectionType;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessageResponse;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.comms.messages.AttachClientMessage;
import org.jdesktop.wonderland.common.comms.messages.AttachedClientMessage;
import org.jdesktop.wonderland.common.comms.messages.SessionInitializationMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.jdesktop.wonderland.server.comms.ServerSession;
import org.jdesktop.wonderland.server.comms.SessionRegistry;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CommsSocketIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10L;

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRegistry registry;

    @Autowired
    private WorldModel world;

    @Autowired
    private TestRestTemplate rest;

    private final StandardWebSocketClient client = new StandardWebSocketClient();

    /** A live client socket plus the frames it has received, decoded via the codec. */
    private static final class SocketChannel {
        final WebSocketSession session;
        final BlockingQueue<Message> inbound;

        SocketChannel(WebSocketSession session, BlockingQueue<Message> inbound) {
            this.session = session;
            this.inbound = inbound;
        }
    }

    private SocketChannel openChannel(ConnectionType type, String username,
            BigInteger sessionId) throws Exception {
        BlockingQueue<Message> inbound = new LinkedBlockingQueue<>();
        WebSocketSession session = client.execute(
                new CollectingHandler(inbound),
                new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/comms")).get();

        Properties properties = new Properties();
        properties.setProperty("username", username);
        properties.setProperty("fullname", username + " Test");
        properties.setProperty("email", username + "@example.org");
        if (sessionId != null) {
            properties.setProperty(CommsWebSocketHandler.SESSION_ID_KEY,
                    sessionId.toString(16));
        }
        session.sendMessage(new BinaryMessage(pack(
                new AttachClientMessage(type, properties))));
        return new SocketChannel(session, inbound);
    }

    private static Message awaitNext(SocketChannel channel) throws InterruptedException {
        Message message = channel.inbound.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(message).as("expected an inbound frame on the socket").isNotNull();
        return message;
    }

    /** Poll a socket until a frame of the wanted type arrives (order-agnostic). */
    private static Message awaitType(SocketChannel channel, Class<?> wanted)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Message message = channel.inbound.poll(50, TimeUnit.MILLISECONDS);
            if (message != null && wanted.isInstance(message)) {
                return message;
            }
        }
        throw new AssertionError("no " + wanted.getSimpleName()
                + " arrived on the socket within " + TIMEOUT_SECONDS + "s");
    }

    private static void send(SocketChannel channel, Message message) throws Exception {
        channel.session.sendMessage(new BinaryMessage(pack(message)));
    }

    /**
     * Consume a socket's login handshake (Attached then SessionInitialization,
     * in the order the handler writes them on the pipe) and return the session id
     * it carries. Every socket of one client -- the cache and its follow-on
     * channels -- is answered this same pair, so each needs this drain before any
     * protocol frame can be read off it.
     */
    private static BigInteger readHandshake(SocketChannel channel)
            throws InterruptedException {
        assertThat(awaitNext(channel)).isInstanceOf(AttachedClientMessage.class);
        return ((SessionInitializationMessage) awaitNext(channel)).getSessionID();
    }

    private static ByteBuffer pack(Message message) {
        try {
            return MessagePacker.pack(message, (short) 1);
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Reads each binary frame back through the codec onto a queue for the test. */
    private static final class CollectingHandler extends AbstractWebSocketHandler {
        private final BlockingQueue<Message> inbound;

        CollectingHandler(BlockingQueue<Message> inbound) {
            this.inbound = inbound;
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            ByteBuffer payload = message.getPayload();
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            try {
                inbound.add(MessagePacker.unpack(ByteBuffer.wrap(bytes)).getMessage());
            } catch (MessagePacker.PackerException ex) {
                throw new IllegalStateException("test socket failed to unpack a frame", ex);
            }
        }
    }

    @Test
    void theLoginHandshakeRidesARealSocket() throws Exception {
        SocketChannel cache = openChannel(
                CellCacheConnectionType.CLIENT_TYPE, "carol", null);

        assertThat(awaitNext(cache)).isInstanceOf(AttachedClientMessage.class);
        SessionInitializationMessage init =
                (SessionInitializationMessage) awaitNext(cache);
        assertThat(init.getSessionID().longValue()).isPositive();
        assertThat(init.getUserID().getUsername()).isEqualTo("carol");
        assertThat(registry.get(init.getSessionID())).isNotNull();

        cache.session.close(CloseStatus.NORMAL);
    }

    @Test
    void threeSocketsOfOneClientConvergeOnASingleSession() throws Exception {
        SocketChannel cache = openChannel(
                CellCacheConnectionType.CLIENT_TYPE, "dave", null);
        BigInteger sessionId = readHandshake(cache);

        SocketChannel channel = openChannel(
                CellChannelConnectionType.CLIENT_TYPE, "dave", sessionId);
        SocketChannel edit = openChannel(
                CellEditConnectionType.CLIENT_TYPE, "dave", sessionId);

        // the two follow-on sockets echo the minted id and are answered their own
        // handshake, yet every one names the SAME session id
        assertThat(readHandshake(channel)).isEqualTo(sessionId);
        assertThat(readHandshake(edit)).isEqualTo(sessionId);

        // one registry entry for the three sockets -- not one per socket
        assertThat(registry.count()).isEqualTo(1);
        ServerSession shared = registry.get(sessionId);
        assertThat(shared.attachedTypes()).containsExactlyInAnyOrder(
                CellCacheConnectionType.CLIENT_TYPE,
                CellChannelConnectionType.CLIENT_TYPE,
                CellEditConnectionType.CLIENT_TYPE);

        cache.session.close(CloseStatus.NORMAL);
        channel.session.close(CloseStatus.NORMAL);
        edit.session.close(CloseStatus.NORMAL);
    }

    @Test
    void aViewHandshakeOverASocketFansOutThroughTheCodec() throws Exception {
        SocketChannel cache = openChannel(
                CellCacheConnectionType.CLIENT_TYPE, "erin", null);
        readHandshake(cache);

        send(cache, CellHierarchyMessage.newSetAvatarMessage("erin-avatar"));

        // the synchronous reply list opens with ViewCreateResponse for a fresh
        // view cell that the authoritative world now hosts
        ViewCreateResponseMessage created =
                (ViewCreateResponseMessage) awaitNext(cache);
        CellID viewCellID = created.getViewCellID();
        assertThat(world.find(viewCellID)).isNotNull();
        // the whole cache payload then lands: the environment LOAD of the sync
        // fan-out and the deferred view-cell LOAD the transport flushes AFTER the
        // reply list (the 2006 async-socket ordering the client's ViewCell needs,
        // pinned frame-for-frame by CommsWebSocketHandlerTest.dataFramesReachTheRouter)
        List<Message> loads = new ArrayList<>();
        loads.add(awaitNext(cache));
        loads.add(awaitNext(cache));
        assertThat(loads).allMatch(CellHierarchyMessage.class::isInstance);
        assertThat(((CellHierarchyMessage) loads.get(0)).getActionType())
                .isEqualTo(CellHierarchyMessage.ActionType.LOAD_CELL);
        assertThat(((CellHierarchyMessage) loads.get(1)).getActionType())
                .isEqualTo(CellHierarchyMessage.ActionType.LOAD_CELL);
        List<CellID> loaded = List.of(
                ((CellHierarchyMessage) loads.get(0)).getCellID(),
                ((CellHierarchyMessage) loads.get(1)).getCellID());
        assertThat(loaded).containsExactlyInAnyOrder(
                CellID.getEnvironmentCellID(), viewCellID);

        cache.session.close(CloseStatus.NORMAL);
    }

    @Test
    void aCrossSessionMoveOverSocketsReachesTheOtherSessionMoved() throws Exception {
        CellID cellID = world.newCellID();
        world.addCell(cellID, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());

        SocketChannel aliceCache = openChannel(
                CellCacheConnectionType.CLIENT_TYPE, "alice", null);
        BigInteger aliceSession = readHandshake(aliceCache);
        SocketChannel aliceChannel = openChannel(
                CellChannelConnectionType.CLIENT_TYPE, "alice", aliceSession);
        readHandshake(aliceChannel);

        SocketChannel bobCache = openChannel(
                CellCacheConnectionType.CLIENT_TYPE, "bob", null);
        BigInteger bobSession = readHandshake(bobCache);
        SocketChannel bobChannel = openChannel(
                CellChannelConnectionType.CLIENT_TYPE, "bob", bobSession);
        readHandshake(bobChannel);

        // bob moves the shared cell on his channel socket
        send(bobChannel, MovableMessage.newMoveRequestMessage(
                cellID, new Vector3f(1f, 2f, 3f), new Quaternion(), 2f));

        // the mover is answered MOVE_MODIFIED on its own socket
        assertThat(awaitType(bobChannel, MovableMessageResponse.class))
                .isInstanceOf(MovableMessageResponse.class);
        // and the accepted transform reaches the other session as a MOVED broadcast
        // stamped with bob's id -- the key alice's client compares to its own to
        // drop its own echoes while applying everyone else's
        MovableMessage moved = (MovableMessage) awaitType(
                aliceChannel, MovableMessage.class);
        assertThat(moved.getActionType()).isEqualTo(MovableMessage.ActionType.MOVED);
        assertThat(moved.getCellID()).isEqualTo(cellID);
        assertThat(moved.getSenderID()).isEqualTo(bobSession);
        // the world mutated authoritatively for the REST view too
        assertThat(world.find(cellID).getTransform().getTranslation(null))
                .isEqualTo(new Vector3f(1f, 2f, 3f));

        for (SocketChannel c : List.of(aliceCache, aliceChannel, bobCache, bobChannel)) {
            c.session.close(CloseStatus.NORMAL);
        }
    }

    @Test
    void restSeesTheWorldTheSocketPathCreatedOnTheSamePort() throws Exception {
        SocketChannel cache = openChannel(
                CellCacheConnectionType.CLIENT_TYPE, "frank", null);
        awaitNext(cache);
        SessionInitializationMessage init =
                (SessionInitializationMessage) awaitNext(cache);
        send(cache, CellHierarchyMessage.newSetAvatarMessage("frank-avatar"));
        ViewCreateResponseMessage created =
                (ViewCreateResponseMessage) awaitType(cache, ViewCreateResponseMessage.class);

        ResponseEntity<List<java.util.Map<String, Object>>> response = rest.exchange(
                "/api/world/cells", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<java.util.Map<String, Object>>>() { });

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<java.util.Map<String, Object>> cells = response.getBody();
        assertThat(cells).isNotNull();
        // the environment root and the view cell the socket handshake minted
        assertThat(cells).anyMatch(c -> created.getViewCellID().toString()
                .equals(c.get("id")));
        assertThat(cells).anyMatch(c -> CellID.getEnvironmentCellID().toString()
                .equals(c.get("id")));
        // and a single cell reads back by id over the same real port
        Object single = rest.getForObject("/api/world/cells/"
                + created.getViewCellID(), java.util.Map.class);
        assertThat(single).isNotNull();
        assertThat(registry.get(init.getSessionID())).isNotNull();

        cache.session.close(CloseStatus.NORMAL);
    }
}
