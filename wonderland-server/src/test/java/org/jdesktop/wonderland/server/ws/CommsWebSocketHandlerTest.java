/*
 * wonderland-server M3b unit tests: the Spring WebSocket transport, driven
 * headless against Mockito-stubbed WebSocketSessions (no port bound -- the
 * real-socket story is the M3b integration test). Beyond the M3a handshake it
 * pins the multi-socket -> one-session fan-out that lifts the "a socket owns its
 * session" note: a follow-on channel that echoes the SessionInitialization id in
 * its attach properties (SESSION_ID_KEY) REUSES the ServerSession rather than
 * minting a second registry entry; the session's shared FrameSink dispatches a
 * frame to the RIGHT one of a session's sockets by ConnectionType (a channel
 * move's MOVED broadcast leaves on the channel socket, a cache LOAD on the cache
 * socket); and a socket close detaches just its own channel, tearing the session
 * down only when its last channel goes. Frames are captured per-socket from
 * sendMessage and unpacked, so every assertion rides the M1 wire, not objects.
 */
package org.jdesktop.wonderland.server.ws;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
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
import org.jdesktop.wonderland.server.comms.ProtocolRouter;
import org.jdesktop.wonderland.server.comms.ServerSession;
import org.jdesktop.wonderland.server.comms.SessionRegistry;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommsWebSocketHandlerTest {

    private WorldModel world;
    private SessionRegistry registry;
    private ProtocolRouter router;
    private CommsWebSocketHandler handler;

    /** The default single socket every M3a-era assertion still rides. */
    private MockSocket main;
    private WebSocketSession socket;
    private Map<String, Object> attributes;
    private List<Message> sentFrames;

    @BeforeEach
    void setUp() throws Exception {
        world = new WorldModel();
        registry = new SessionRegistry();
        router = new ProtocolRouter(world, registry);
        handler = new CommsWebSocketHandler(router, registry);

        main = new MockSocket("ws-test");
        socket = main.socket;
        attributes = main.attributes;
        sentFrames = main.sentFrames;
    }

    /**
     * A Mockito-stubbed WebSocketSession with its own attribute map and its own
     * capture of the frames the handler writes back (unpacked through the real
     * codec), so a test can tell which one of a session's sockets a frame left on.
     */
    private final class MockSocket {
        private final WebSocketSession socket;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<Message> sentFrames = new ArrayList<>();

        MockSocket(String id) throws Exception {
            socket = mock(WebSocketSession.class);
            when(socket.getId()).thenReturn(id);
            when(socket.isOpen()).thenReturn(true);
            when(socket.getAttributes()).thenReturn(attributes);
            doAnswer((Answer<Void>) invocation -> {
                BinaryMessage message = invocation.getArgument(0);
                try {
                    sentFrames.add(MessagePacker.unpack(message.getPayload()).getMessage());
                } catch (MessagePacker.PackerException ex) {
                    throw new IllegalStateException(ex);
                }
                return null;
            }).when(socket).sendMessage(any(BinaryMessage.class));
        }

        void feed(Message message) throws Exception {
            ByteBuffer packed;
            try {
                packed = MessagePacker.pack(message, (short) 1);
            } catch (MessagePacker.PackerException ex) {
                throw new IllegalStateException(ex);
            }
            handler.handleMessage(socket, new BinaryMessage(packed));
        }
    }

    private AttachClientMessage attach(ConnectionType type, BigInteger sessionId) {
        Properties properties = new Properties();
        properties.setProperty("username", "carol");
        properties.setProperty("fullname", "Carol C");
        properties.setProperty("email", "carol@example.org");
        if (sessionId != null) {
            properties.setProperty(CommsWebSocketHandler.SESSION_ID_KEY,
                    sessionId.toString(16));
        }
        return new AttachClientMessage(type, properties);
    }

    /** Read the session id the handler handed back in the SessionInitialization. */
    private BigInteger sessionIdOf(MockSocket sock) {
        return ((SessionInitializationMessage) sock.sentFrames.get(1)).getSessionID();
    }

    private static CellTransform translation(float x, float y, float z) {
        CellTransform t = new CellTransform();
        t.setTranslation(new Vector3f(x, y, z));
        return t;
    }

    // ---- the M3a handshake (single socket) ----------------------------------

    @Test
    void attachAnswersTheLoginHandshakeThroughTheCodec() throws Exception {
        main.feed(attach(CellCacheConnectionType.CLIENT_TYPE, null));

        assertThat(sentFrames).hasSize(2);
        AttachedClientMessage attached = (AttachedClientMessage) sentFrames.get(0);
        SessionInitializationMessage init = (SessionInitializationMessage) sentFrames.get(1);
        assertThat(attached.getMessageID()).isNotNull();
        assertThat(init.getSessionID().longValue()).isEqualTo(1L);
        assertThat(init.getUserID().getUsername()).isEqualTo("carol");
        assertThat(init.getUserID().getFullName()).isEqualTo("Carol C");
        assertThat(registry.count()).isEqualTo(1);
        assertThat(router.viewCellOf(registry.get(init.getSessionID()))).isNull();
    }

    @Test
    void attachFallsBackToAnAnonymousIdentity() throws Exception {
        main.feed(new AttachClientMessage(CellCacheConnectionType.CLIENT_TYPE, null));

        SessionInitializationMessage init =
                (SessionInitializationMessage) sentFrames.get(1);
        assertThat(init.getUserID().getUsername()).isEqualTo("anonymous");
        assertThat(init.getUserID().getEmail()).isEqualTo("anonymous@wonderland.invalid");
    }

    @Test
    void dataFramesReachTheRouter() throws Exception {
        main.feed(attach(CellCacheConnectionType.CLIENT_TYPE, null));
        sentFrames.clear();

        main.feed(CellHierarchyMessage.newSetAvatarMessage("carol-avatar"));

        // the transport flushed the router's post-reply queue after the reply
        // list: ViewCreateResponse + environment LOAD sync, view cell LOAD after
        assertThat(sentFrames).hasSize(3);
        assertThat(sentFrames.get(0)).isInstanceOf(ViewCreateResponseMessage.class);
        CellID viewCellID = ((ViewCreateResponseMessage) sentFrames.get(0)).getViewCellID();
        assertThat(world.find(viewCellID)).isNotNull();
        CellHierarchyMessage viewLoad = (CellHierarchyMessage) sentFrames.get(2);
        assertThat(viewLoad.getCellID()).isEqualTo(viewCellID);
        // and the queue is drained exactly -- no duplicate frame on a second flush
        ServerSession serverSession =
                (ServerSession) attributes.get("wonderland.server.session");
        assertThat(router.flush(serverSession)).isNull();
    }

    @Test
    void dataOnAnUnattachedSocketIsDropped() throws Exception {
        main.feed(CellHierarchyMessage.newSetAvatarMessage("ghost"));

        assertThat(sentFrames).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @Test
    void closingDetachesTheRouterAndEvictsTheSession() throws Exception {
        main.feed(attach(CellCacheConnectionType.CLIENT_TYPE, null));
        SessionInitializationMessage init =
                (SessionInitializationMessage) sentFrames.get(1);

        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);

        assertThat(registry.get(init.getSessionID())).isNull();
        assertThat(registry.count()).isZero();
    }

    @Test
    void closingAnUnattachedSocketIsHarmless() {
        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);

        assertThat(registry.count()).isZero();
    }

    @Test
    void aCorruptFrameIsDroppedWithAWarningNotACrash() throws Exception {
        handler.handleMessage(socket, new BinaryMessage(ByteBuffer.wrap(new byte[]{9, 8, 7})));

        assertThat(sentFrames).isEmpty();
    }

    // ---- M3b: multi-socket fan-out onto ONE session --------------------------

    @Test
    void aFollowOnChannelRejoinsTheSameSessionRatherThanMintingANewOne() throws Exception {
        MockSocket cache = new MockSocket("ws-cache");
        cache.feed(attach(CellCacheConnectionType.CLIENT_TYPE, null));
        BigInteger sessionId = sessionIdOf(cache);

        MockSocket channel = new MockSocket("ws-channel");
        channel.feed(attach(CellChannelConnectionType.CLIENT_TYPE, sessionId));

        // one registry entry, not one per socket -- the router's per-session
        // semantics (echo suppression, broadcast scoping, one view cell) survive
        assertThat(registry.count()).isEqualTo(1);
        ServerSession shared = registry.get(sessionId);
        assertThat(shared.attachedTypes()).contains(
                CellCacheConnectionType.CLIENT_TYPE,
                CellChannelConnectionType.CLIENT_TYPE);
        // the follow-on socket gets its own handshake, echoing the SAME session id
        assertThat(channel.sentFrames).hasSize(2);
        SessionInitializationMessage init =
                (SessionInitializationMessage) channel.sentFrames.get(1);
        assertThat(init.getSessionID()).isEqualTo(sessionId);
        // and the handler now has two distinct live sockets for that one session
        assertThat(handler.socketFor(sessionId, CellCacheConnectionType.CLIENT_TYPE))
                .isSameAs(cache.socket);
        assertThat(handler.socketFor(sessionId, CellChannelConnectionType.CLIENT_TYPE))
                .isSameAs(channel.socket);
    }

    @Test
    void anAttachNamingAnUnknownSessionOpensAFreshOne() throws Exception {
        MockSocket channel = new MockSocket("ws-orphan");
        channel.feed(attach(CellChannelConnectionType.CLIENT_TYPE, BigInteger.valueOf(999L)));

        assertThat(registry.count()).isEqualTo(1);
        ServerSession session = registry.get(sessionIdOf(channel));
        assertThat(session.attachedTypes())
                .containsExactly(CellChannelConnectionType.CLIENT_TYPE);
    }

    @Test
    void aMalformedSessionIdFallsBackToAFreshSession() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("username", "carol");
        properties.setProperty(CommsWebSocketHandler.SESSION_ID_KEY, "not-hex");
        MockSocket sock = new MockSocket("ws-bad");
        sock.feed(new AttachClientMessage(CellCacheConnectionType.CLIENT_TYPE, properties));

        assertThat(registry.count()).isEqualTo(1);
        assertThat(sessionIdOf(sock)).isNotNull();
    }

    @Test
    void theFrameSinkDispatchesByConnectionTypeToTheRightSocket() throws Exception {
        MockSocket cache = new MockSocket("ws-cache");
        cache.feed(attach(CellCacheConnectionType.CLIENT_TYPE, null));
        BigInteger sessionId = sessionIdOf(cache);
        MockSocket channel = new MockSocket("ws-channel");
        channel.feed(attach(CellChannelConnectionType.CLIENT_TYPE, sessionId));
        cache.sentFrames.clear();
        channel.sentFrames.clear();

        // a real content cell so the move is accepted by the authoritative world
        CellID cellID = world.newCellID();
        world.addCell(cellID, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());

        // a channel move: the mover is answered MOVE_MODIFIED on the acting
        // (channel) socket, and the MOVED broadcast for every channel-attached
        // session -- here just this one -- leaves through the sink on the CHANNEL
        // socket too, never on the cache socket. The broadcast is a side effect of
        // routing (so it lands before the reply write), hence the order-agnostic
        // assertions: both frames arrive on the channel socket, none on the cache.
        channel.feed(MovableMessage.newMoveRequestMessage(
                cellID, new Vector3f(1f, 2f, 3f), new Quaternion(), 2f));

        assertThat(channel.sentFrames).hasSize(2);
        assertThat(channel.sentFrames)
                .anyMatch(MovableMessageResponse.class::isInstance);
        MovableMessage echo = (MovableMessage) channel.sentFrames.stream()
                .filter(MovableMessage.class::isInstance).findFirst().orElseThrow();
        assertThat(echo.getActionType()).isEqualTo(MovableMessage.ActionType.MOVED);
        assertThat(echo.getSenderID()).isEqualTo(sessionId);
        assertThat(cache.sentFrames).isEmpty();
    }

    @Test
    void closingOneChannelLeavesTheSessionAliveUntilTheLastGoes() throws Exception {
        MockSocket cache = new MockSocket("ws-cache");
        cache.feed(attach(CellCacheConnectionType.CLIENT_TYPE, null));
        BigInteger sessionId = sessionIdOf(cache);
        // the cache connection mints the session's view cell, so a cache close
        // later has a real departure to announce
        cache.feed(CellHierarchyMessage.newSetAvatarMessage("carol-avatar"));
        CellID viewCellID = ((ViewCreateResponseMessage) cache.sentFrames.get(2))
                .getViewCellID();
        MockSocket channel = new MockSocket("ws-channel");
        channel.feed(attach(CellChannelConnectionType.CLIENT_TYPE, sessionId));
        cache.sentFrames.clear();

        handler.afterConnectionClosed(channel.socket, CloseStatus.NORMAL);

        // the channel socket is gone, the session and its cache channel survive
        assertThat(registry.get(sessionId)).isNotNull();
        assertThat(registry.count()).isEqualTo(1);
        assertThat(handler.socketFor(sessionId, CellChannelConnectionType.CLIENT_TYPE))
                .isNull();
        assertThat(cache.sentFrames).isEmpty();

        handler.afterConnectionClosed(cache.socket, CloseStatus.NORMAL);

        // the last channel leaving tears down the view cell and evicts the
        // session. The departing socket sees nothing on its way out: the handler
        // drops its channel before detaching, so the departed avatar's UNLOAD
        // broadcast has no live socket to leave on (surviving sessions would get it)
        assertThat(cache.sentFrames).isEmpty();
        assertThat(world.find(viewCellID)).isNull();
        assertThat(registry.get(sessionId)).isNull();
        assertThat(registry.count()).isZero();
    }
}
