/*
 * wonderland-server M3 unit tests: the Spring WebSocket transport, driven
 * headless against a Mockito-stubbed WebSocketSession (no port bound -- the
 * full multi-socket story is M3b). Pins the rebuilt login handshake the client
 * interface dropped: the first binary frame is an AttachClientMessage, and the
 * socket is answered Attached + SessionInitialization (server-minted BigInteger
 * id, identity from the attach properties) through the real codec; later frames
 * route straight into the ProtocolRouter; a frame on an un-attached socket is
 * dropped with a warning instead of a crash; and closing detaches the router
 * and evicts the session from the registry. Frames are captured from
 * sendMessage and unpacked, so every assertion rides the M1 wire, not objects.
 */
package org.jdesktop.wonderland.server.ws;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
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
    private WebSocketSession socket;
    private Map<String, Object> attributes;
    private List<Message> sentFrames;

    @BeforeEach
    void setUp() throws Exception {
        world = new WorldModel();
        registry = new SessionRegistry();
        router = new ProtocolRouter(world, registry);
        handler = new CommsWebSocketHandler(router, registry);

        attributes = new HashMap<>();
        sentFrames = new ArrayList<>();
        socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("ws-test");
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

    private void feed(Message message) throws Exception {
        ByteBuffer packed;
        try {
            packed = MessagePacker.pack(message, (short) 1);
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException(ex);
        }
        handler.handleMessage(socket, new BinaryMessage(packed));
    }

    private AttachClientMessage attachCache() {
        Properties properties = new Properties();
        properties.setProperty("username", "carol");
        properties.setProperty("fullname", "Carol C");
        properties.setProperty("email", "carol@example.org");
        return new AttachClientMessage(CellCacheConnectionType.CLIENT_TYPE, properties);
    }

    @Test
    void attachAnswersTheLoginHandshakeThroughTheCodec() throws Exception {
        feed(attachCache());

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
        feed(new AttachClientMessage(CellCacheConnectionType.CLIENT_TYPE, null));

        SessionInitializationMessage init =
                (SessionInitializationMessage) sentFrames.get(1);
        assertThat(init.getUserID().getUsername()).isEqualTo("anonymous");
        assertThat(init.getUserID().getEmail()).isEqualTo("anonymous@wonderland.invalid");
    }

    @Test
    void dataFramesReachTheRouter() throws Exception {
        feed(attachCache());
        sentFrames.clear();

        feed(CellHierarchyMessage.newSetAvatarMessage("carol-avatar"));

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
        feed(CellHierarchyMessage.newSetAvatarMessage("ghost"));

        assertThat(sentFrames).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @Test
    void closingDetachesTheRouterAndEvictsTheSession() throws Exception {
        feed(attachCache());
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
}
