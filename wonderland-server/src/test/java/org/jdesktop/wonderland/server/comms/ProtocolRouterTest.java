/*
 * wonderland-server M3 unit tests: the protocol brain, driven through the real
 * M1 codec (MessagePacker) with recording sinks in place of any transport. Pins
 * what was ported from the 2006 SGS router, frame for frame:
 *   - SET_VIEW answers ViewCreateResponse + the hierarchy LOAD_CELL fan-out, and
 *     registers a ViewCell-typed world cell whose own LOAD rides the sink AFTER
 *     the reply frames (the async timing the client's ViewCell ->
 *     viewCellConfigured chain depends on);
 *   - a second session's fan-out carries the first session's view cell too --
 *     multiplayer avatar presence;
 *   - an accepted move answers the mover MOVE_MODIFIED and pushes MOVED to
 *     every channel-attached session stamped with the mover's session id (the
 *     echo-suppression key the client compares to its own);
 *   - a move for an unknown cell is refused with an ErrorMessage and the world
 *     stays untouched;
 *   - a reparent mutates the world and broadcasts CONFIGURE_CELL; detaching the
 *     cache connection removes the departed view cell and broadcasts UNLOAD_CELL;
 *   - type mismatches and unknown connection types are silent no-ops, and a
 *     corrupt frame fails loud (the transport decides to drop, the router throws).
 */
package org.jdesktop.wonderland.server.comms;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.jdesktop.wonderland.common.cell.CellEditConnectionType;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage.ActionType;
import org.jdesktop.wonderland.common.cell.messages.CellReparentMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessageResponse;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.ErrorMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.jdesktop.wonderland.common.cell.view.ViewCellClientState;
import org.jdesktop.wonderland.server.world.WorldCell;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolRouterTest {

    private static final WonderlandIdentity ALICE =
            new WonderlandIdentity("alice", "Alice A", "alice@example.org");
    private static final WonderlandIdentity BOB =
            new WonderlandIdentity("bob", "Bob B", "bob@example.org");

    /** A delivered frame plus the short the wire stamped it with. */
    private record Incoming(ServerSession session, ConnectionType type, Message message,
            short clientID) {
    }

    private WorldModel world;
    private SessionRegistry registry;
    private ProtocolRouter router;
    private List<Incoming> pushedA;
    private List<Incoming> pushedB;
    private ServerSession sessionA;
    private ServerSession sessionB;

    @BeforeEach
    void setUp() {
        world = new WorldModel();
        registry = new SessionRegistry();
        router = new ProtocolRouter(world, registry);
        pushedA = new ArrayList<>();
        pushedB = new ArrayList<>();
        sessionA = registry.create(ALICE, recordingSink(pushedA));
        sessionB = registry.create(BOB, recordingSink(pushedB));
        registry.attach(sessionA, CellCacheConnectionType.CLIENT_TYPE);
        registry.attach(sessionA, CellChannelConnectionType.CLIENT_TYPE);
        registry.attach(sessionB, CellCacheConnectionType.CLIENT_TYPE);
        registry.attach(sessionB, CellChannelConnectionType.CLIENT_TYPE);
    }

    private FrameSink recordingSink(List<Incoming> into) {
        return (session, type, frame) -> {
            MessagePacker.ReceivedMessage received = unpack(frame);
            into.add(new Incoming(session, type, received.getMessage(),
                    received.getClientID()));
        };
    }

    private static ByteBuffer pack(Message message) {
        try {
            return MessagePacker.pack(message, (short) 1);
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static MessagePacker.ReceivedMessage unpack(ByteBuffer frame) {
        try {
            return MessagePacker.unpack(frame);
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<Message> messages(List<ByteBuffer> frames) {
        List<Message> out = new ArrayList<>();
        for (ByteBuffer frame : frames) {
            out.add(unpack(frame).getMessage());
        }
        return out;
    }

    private CellHierarchyMessage setView() {
        return CellHierarchyMessage.newSetAvatarMessage("alice-avatar");
    }

    private static CellTransform translation(float x, float y, float z) {
        CellTransform t = new CellTransform();
        t.setTranslation(new Vector3f(x, y, z));
        return t;
    }

    // ---- the view handshake ---------------------------------------------------

    /** Drain the router's post-reply queue the way a transport does. */
    private List<Message> flushed(ServerSession session) {
        List<Message> out = new ArrayList<>();
        ByteBuffer pending;
        while ((pending = router.flush(session)) != null) {
            out.add(unpack(pending).getMessage());
        }
        return out;
    }

    @Test
    void setViewAnswersWithResponseThenFanOutAndDefersTheViewCell() {
        CellHierarchyMessage request = setView();
        List<ByteBuffer> reply = router.routeInbound(sessionA,
                CellCacheConnectionType.CLIENT_TYPE, pack(request));

        List<Message> frames = messages(reply);
        // synchronous reply: ViewCreateResponse, then the world (environment)
        assertThat(frames).hasSize(2);
        ViewCreateResponseMessage created = (ViewCreateResponseMessage) frames.get(0);
        assertThat(created.getMessageID()).isEqualTo(request.getMessageID());
        CellID viewCellID = created.getViewCellID();
        assertThat(frames.get(1)).isInstanceOf(CellHierarchyMessage.class);
        assertThat(((CellHierarchyMessage) frames.get(1)).getActionType())
                .isEqualTo(ActionType.LOAD_CELL);

        // the minted view is a real world cell of the client ViewCell type
        WorldCell viewCell = world.find(viewCellID);
        assertThat(viewCell).isNotNull();
        assertThat(viewCell.getClassName()).isEqualTo(ProtocolRouter.VIEW_CELL_CLASS);
        assertThat(viewCell.getParentID()).isEqualTo(CellID.getEnvironmentCellID());
        assertThat(((ViewCellClientState) viewCell.getClientState()).getIdentity())
                .isEqualTo(ALICE);
        assertThat(router.viewCellOf(sessionA)).isEqualTo(viewCellID);

        // and its LOAD is deferred: queued behind the reply list for the
        // transport to flush, stamped with A's cache-connection client id
        assertThat(pushedA).isEmpty();
        List<Message> deferredFrames = flushed(sessionA);
        assertThat(deferredFrames).hasSize(1);
        CellHierarchyMessage load = (CellHierarchyMessage) deferredFrames.get(0);
        assertThat(load.getActionType()).isEqualTo(ActionType.LOAD_CELL);
        assertThat(load.getCellID()).isEqualTo(viewCellID);
        assertThat(load.getCellClassName()).isEqualTo(ProtocolRouter.VIEW_CELL_CLASS);
        // the queue drains empty afterwards
        assertThat(router.flush(sessionA)).isNull();
    }

    @Test
    void aSecondSessionGetsItsOwnViewFirstAndTheForeignViewAfter() {
        CellID aliceView = ((ViewCreateResponseMessage) messages(router.routeInbound(sessionA,
                CellCacheConnectionType.CLIENT_TYPE, pack(setView()))).get(0)).getViewCellID();
        flushed(sessionA);

        CellHierarchyMessage bobSetView = setView();
        List<Message> sync = messages(router.routeInbound(sessionB,
                CellCacheConnectionType.CLIENT_TYPE, pack(bobSetView)));

        // the sync pass is the NON-view world only: response + environment, no
        // alice-view frame that could hijack bob's LocalAvatar before his own
        // view configures
        assertThat(sync).hasSize(2);
        CellID bobView = ((ViewCreateResponseMessage) sync.get(0)).getViewCellID();
        List<Message> deferredFrames = flushed(sessionB);
        assertThat(deferredFrames).hasSize(2);
        assertThat(((CellHierarchyMessage) deferredFrames.get(0)).getCellID())
                .isEqualTo(bobView);
        assertThat(((CellHierarchyMessage) deferredFrames.get(1)).getCellID())
                .isEqualTo(aliceView);
        assertThat(world.find(bobView)).isNotNull();
    }

    @Test
    void cacheMessagesThatAreNotHierarchyAreIgnored() {
        List<ByteBuffer> reply = router.routeInbound(sessionA,
                CellCacheConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(
                        CellID.getEnvironmentCellID(), translation(1, 1, 1))));

        assertThat(reply).isEmpty();
    }

    @Test
    void hierarchyActionsOtherThanSetViewAreIgnored() {
        CellHierarchyMessage load = new CellHierarchyMessage(ActionType.LOAD_CELL,
                "some.client.Cell", null, new CellID(42L), null, new CellTransform(),
                new CellClientState(), "x");

        assertThat(router.routeInbound(sessionA, CellCacheConnectionType.CLIENT_TYPE,
                pack(load))).isEmpty();
    }

    // ---- the authoritative move -----------------------------------------------

    @Test
    void acceptedMoveAnswersTheMoverAndBroadcastsTheEchoKey() {
        CellID cellID = world.newCellID();
        world.addCell(cellID, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());

        MovableMessage request = MovableMessage.newMoveRequestMessage(
                cellID, new Vector3f(1f, 2f, 3f), new Quaternion(), 2f);
        List<Message> reply = messages(router.routeInbound(sessionA,
                CellChannelConnectionType.CLIENT_TYPE, pack(request)));

        // the mover's own reply: exactly one, MOVE_MODIFIED, matching request id
        assertThat(reply).hasSize(1);
        MovableMessageResponse response = (MovableMessageResponse) reply.get(0);
        assertThat(response.getMessageID()).isEqualTo(request.getMessageID());
        assertThat(response.getActionType())
                .isEqualTo(MovableMessageResponse.ActionType.MOVE_MODIFIED);
        assertThat(response.getTranslation()).isEqualTo(new Vector3f(1f, 2f, 3f));
        assertThat(response.getScale()).isEqualTo(2f);

        // the world mutated authoritatively
        assertThat(world.find(cellID).getTransform().getTranslation(null))
                .isEqualTo(new Vector3f(1f, 2f, 3f));

        // and EVERY channel-attached session (mover included) got the MOVED
        // broadcast stamped with the mover's session id -- the client drops it
        // for itself when senderID == own session id, applies it elsewhere
        assertThat(pushedA).hasSize(1);
        MovableMessage echoA = (MovableMessage) pushedA.get(0).message();
        assertThat(echoA.getActionType()).isEqualTo(MovableMessage.ActionType.MOVED);
        assertThat(echoA.getSenderID()).isEqualTo(sessionA.getID());
        assertThat(pushedB).hasSize(1);
        MovableMessage echoB = (MovableMessage) pushedB.get(0).message();
        assertThat(echoB.getCellID()).isEqualTo(cellID);
        assertThat(echoB.getTranslation()).isEqualTo(new Vector3f(1f, 2f, 3f));
        assertThat(echoB.getSenderID()).isEqualTo(sessionA.getID());
        assertThat(pushedB.get(0).clientID())
                .isEqualTo(sessionB.clientID(CellChannelConnectionType.CLIENT_TYPE));
    }

    @Test
    void moveForAnUnknownCellIsRefusedAndTheWorldStaysUntouched() {
        MovableMessage request = MovableMessage.newMoveRequestMessage(
                new CellID(9999L), new Vector3f(1f, 2f, 3f), new Quaternion());

        List<Message> reply = messages(router.routeInbound(sessionA,
                CellChannelConnectionType.CLIENT_TYPE, pack(request)));

        assertThat(reply).hasSize(1);
        ErrorMessage error = (ErrorMessage) reply.get(0);
        assertThat(error.getMessageID()).isEqualTo(request.getMessageID());
        assertThat(error.getErrorMessage()).contains("unknown cell");
        assertThat(pushedA).isEmpty();
        assertThat(pushedB).isEmpty();
    }

    @Test
    void channelMessagesThatAreNotMovableAreIgnored() {
        assertThat(router.routeInbound(sessionA, CellChannelConnectionType.CLIENT_TYPE,
                pack(setView()))).isEmpty();
    }

    // ---- the edit connection ----------------------------------------------------

    @Test
    void reparentMutatesTheWorldAndBroadcastsConfigure() {
        CellID cellID = world.newCellID();
        world.addCell(cellID, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());
        CellID newParent = world.newCellID();
        world.putCell(new WorldCell(newParent, CellID.getEnvironmentCellID(),
                "some.client.Cell", "box", null, new CellTransform(), null));

        CellReparentMessage reparent =
                new CellReparentMessage(cellID, newParent, translation(4, 5, 6));
        assertThat(router.routeInbound(sessionA, CellEditConnectionType.CLIENT_TYPE,
                pack(reparent))).isEmpty();

        WorldCell moved = world.find(cellID);
        assertThat(moved.getParentID()).isEqualTo(newParent);
        assertThat(moved.getTransform().getTranslation(null)).isEqualTo(new Vector3f(4, 5, 6));

        // cache-attached sessions learn the new placement; a configure has no
        // reply frames of its own
        assertThat(pushedA).hasSize(1);
        CellHierarchyMessage configure = (CellHierarchyMessage) pushedA.get(0).message();
        assertThat(configure.getActionType()).isEqualTo(ActionType.CONFIGURE_CELL);
        assertThat(configure.getParentID()).isEqualTo(newParent);
        assertThat(pushedB).hasSize(1);
    }

    @Test
    void reparentOfAnUnknownCellIsSilent() {
        CellReparentMessage reparent = new CellReparentMessage(new CellID(9999L),
                CellID.getEnvironmentCellID(), new CellTransform());

        assertThat(router.routeInbound(sessionA, CellEditConnectionType.CLIENT_TYPE,
                pack(reparent))).isEmpty();
        assertThat(pushedA).isEmpty();
    }

    @Test
    void detachOfTheCacheConnectionUnloadsTheDepartedViewCell() {
        CellID viewCellID = ((ViewCreateResponseMessage) messages(router.routeInbound(
                sessionA, CellCacheConnectionType.CLIENT_TYPE, pack(setView()))).get(0))
                        .getViewCellID();
        flushed(sessionA);
        pushedB.clear();

        router.detach(sessionA, CellCacheConnectionType.CLIENT_TYPE);

        assertThat(world.find(viewCellID)).isNull();
        assertThat(router.viewCellOf(sessionA)).isNull();
        // A's detached session holds no stale queue; B learns the cell is gone
        assertThat(router.flush(sessionA)).isNull();
        assertThat(pushedA).isEmpty();
        assertThat(pushedB).hasSize(1);
        CellHierarchyMessage unload = (CellHierarchyMessage) pushedB.get(0).message();
        assertThat(unload.getActionType()).isEqualTo(ActionType.UNLOAD_CELL);
        assertThat(unload.getCellID()).isEqualTo(viewCellID);
    }

    // ---- transport-facing edges --------------------------------------------------

    @Test
    void unknownConnectionTypesRouteNowhere() {
        assertThat(router.routeInbound(sessionA, new ConnectionType("OTHER"),
                pack(setView()))).isEmpty();
    }

    @Test
    void aCorruptFrameFailsLoud() {
        assertThatThrownBy(() -> router.routeInbound(sessionA,
                CellCacheConnectionType.CLIENT_TYPE, ByteBuffer.wrap(new byte[]{1, 2, 3})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to unpack");
    }

    @Test
    void viewCellOfIsNullBeforeTheHandshake() {
        assertThat(router.viewCellOf(sessionB)).isNull();
    }
}
