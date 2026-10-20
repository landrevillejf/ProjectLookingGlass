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

import com.jme.bounding.BoundingSphere;
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
import org.jdesktop.wonderland.common.cell.messages.TextChatMessage;
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

    // ---- chat ------------------------------------------------------------------

    @Test
    void chatIsRelayedToEverySessionUnderTheAuthenticatedName() {
        // The name on the wire is a claim. Alice says she is Bob; the relay
        // answers with who the login actually was, so a peer's transcript can
        // never be forged by the client that typed it.
        TextChatMessage claim = new TextChatMessage(CellID.getEnvironmentCellID(),
                "Bob B", "hello from alice", 4242L);

        assertThat(router.routeInbound(sessionA, CellChannelConnectionType.CLIENT_TYPE,
                pack(claim))).isEmpty();

        assertThat(pushedA).hasSize(1);
        TextChatMessage toSelf = (TextChatMessage) pushedA.get(0).message();
        assertThat(toSelf.getSenderName()).isEqualTo("Alice A");
        assertThat(toSelf.getText()).isEqualTo("hello from alice");
        assertThat(toSelf.getSentAt()).isEqualTo(4242L);
        assertThat(toSelf.getSenderID()).isEqualTo(sessionA.getID());

        assertThat(pushedB).hasSize(1);
        TextChatMessage toOther = (TextChatMessage) pushedB.get(0).message();
        assertThat(toOther.getSenderName()).isEqualTo("Alice A");
        assertThat(toOther.getSenderID()).isEqualTo(sessionA.getID());
        // the frame left stamped for B's own channel connection
        assertThat(pushedB.get(0).clientID())
                .isEqualTo(sessionB.clientID(CellChannelConnectionType.CLIENT_TYPE));
    }

    @Test
    void chatAddressedToAnyOtherCellIsRefused() {
        // A line addressed to some content cell has no receiver guarantee: the
        // clients that have not loaded that cell would drop it silently. The
        // world root is the one address every session is listening on.
        CellID somewhereElse = world.newCellID();
        TextChatMessage misaddressed =
                new TextChatMessage(somewhereElse, "Alice A", "anyone there?");

        List<Message> reply = messages(router.routeInbound(sessionA,
                CellChannelConnectionType.CLIENT_TYPE, pack(misaddressed)));

        assertThat(reply).hasSize(1);
        assertThat(((ErrorMessage) reply.get(0)).getErrorMessage())
                .contains("addressed to the world cell");
        assertThat(pushedA).isEmpty();
        assertThat(pushedB).isEmpty();
    }

    @Test
    void aChatBodyThatNeverPassedTheConstructorIsRefused() throws Exception {
        // Java serialisation runs no constructor, so the bounds in
        // TextChatMessage are not a server-side guarantee on their own: a
        // hand-made frame arrives with whatever fields it names. The relay
        // rebuilds the line, which re-runs them, and a refusal is an ErrorMessage
        // to the sender -- not a stack trace that takes the socket down.
        TextChatMessage forged = new TextChatMessage(
                CellID.getEnvironmentCellID(), "Alice A", "perfectly normal");
        java.lang.reflect.Field text = TextChatMessage.class.getDeclaredField("text");
        text.setAccessible(true);
        text.set(forged, "   ");

        List<Message> reply = messages(router.routeInbound(sessionA,
                CellChannelConnectionType.CLIENT_TYPE, pack(forged)));

        assertThat(reply).hasSize(1);
        assertThat(((ErrorMessage) reply.get(0)).getErrorMessage()).contains("rejected");
        assertThat(pushedA).isEmpty();
        assertThat(pushedB).isEmpty();
    }

    @Test
    void theRelayedNameIsTheFullNameThenTheUsernameThenGuest() {
        // Three identities, one rule: show what the person gave, and never show
        // nothing. A blank fullname is as unusable as a missing identity.
        ServerSession blankFull = registry.create(
                new WonderlandIdentity("carol", "   ", "c@example.org"),
                recordingSink(new ArrayList<>()));
        registry.attach(blankFull, CellChannelConnectionType.CLIENT_TYPE);
        ServerSession anonymous = registry.create(null,
                recordingSink(new ArrayList<>()));
        registry.attach(anonymous, CellChannelConnectionType.CLIENT_TYPE);

        List<Incoming> listeners = pushedB;
        listeners.clear();

        relayFrom(blankFull, "signed with a username");
        assertThat(((TextChatMessage) listeners.get(0).message()).getSenderName())
                .isEqualTo("carol");

        relayFrom(anonymous, "signed with nothing");
        assertThat(((TextChatMessage) listeners.get(1).message()).getSenderName())
                .isEqualTo("guest");
    }

    /** One line from one session, relayed as any receiver would see it. */
    private void relayFrom(ServerSession from, String body) {
        router.routeInbound(from, CellChannelConnectionType.CLIENT_TYPE,
                pack(new TextChatMessage(CellID.getEnvironmentCellID(),
                        "whoever typed this", body)));
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

    // ---- transport-facing edges: a body-less but well-formed frame ------------

    @Test
    void aValidHeaderWithNoBodyFailsLoudToo() {
        // 0xACED0005 is a well-formed serialisation-stream header with no message
        // behind it: the codec reads the header, hits EOF and returns null rather
        // than throwing, which the router turns into the same loud failure a
        // corrupt header produces (received == null -> PackerException -> ISE)
        assertThatThrownBy(() -> router.routeInbound(sessionA,
                CellCacheConnectionType.CLIENT_TYPE,
                ByteBuffer.wrap(new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to unpack");
    }

    @Test
    void editMessagesThatAreNotReparentAreIgnored() {
        // the edit connection only acts on a reparent; anything else is a no-op
        assertThat(router.routeInbound(sessionA, CellEditConnectionType.CLIENT_TYPE,
                pack(setView()))).isEmpty();
    }

    // ---- defer(): a SET_VIEW from a session with no cache connection ----------

    @Test
    void setViewFromASessionWithoutACacheConnectionDefersNothing() {
        List<Incoming> channelOnly = new ArrayList<>();
        ServerSession channelSession = registry.create(ALICE, recordingSink(channelOnly));
        registry.attach(channelSession, CellChannelConnectionType.CLIENT_TYPE);

        // the type argument routes to the cache handler, but the session itself
        // has no cache connection, so defer() declines to queue the view chain
        List<ByteBuffer> reply = router.routeMessage(channelSession,
                CellCacheConnectionType.CLIENT_TYPE, setView());

        assertThat(reply).isNotEmpty();
        assertThat(router.flush(channelSession)).isNull();
        assertThat(channelOnly).isEmpty();
    }

    // ---- broadcasts skip a session missing the connection type they fan to ----

    @Test
    void aMoveBroadcastSkipsSessionsWithoutAChannelConnection() {
        CellID cellID = world.newCellID();
        world.addCell(cellID, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());
        List<Incoming> cacheOnly = new ArrayList<>();
        ServerSession cacheSession = registry.create(ALICE, recordingSink(cacheOnly));
        registry.attach(cacheSession, CellCacheConnectionType.CLIENT_TYPE);
        pushedA.clear();
        pushedB.clear();

        router.routeInbound(sessionA, CellChannelConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(cellID,
                        new Vector3f(1f, 2f, 3f), new Quaternion(), 2f)));

        // the two channel-attached sessions hear the move; the cache-only one is
        // skipped by the continue, so its sink stays empty
        assertThat(pushedA).hasSize(1);
        assertThat(pushedB).hasSize(1);
        assertThat(cacheOnly).isEmpty();
    }

    @Test
    void chatSkipsSessionsWithoutAChannelConnection() {
        List<Incoming> cacheOnly = new ArrayList<>();
        ServerSession cacheSession = registry.create(ALICE, recordingSink(cacheOnly));
        registry.attach(cacheSession, CellCacheConnectionType.CLIENT_TYPE);
        pushedA.clear();
        pushedB.clear();

        relayFrom(sessionA, "anyone on channel?");

        assertThat(pushedA).hasSize(1);
        assertThat(pushedB).hasSize(1);
        assertThat(cacheOnly).isEmpty();
    }

    // ---- displayName(): the null-full-name and null/blank-username arms -------

    @Test
    void theRelayedNameUsesTheUsernameWhenTheFullNameIsNull() {
        ServerSession noFull = registry.create(
                new WonderlandIdentity("dave", null, "d@example.org"),
                recordingSink(new ArrayList<>()));
        registry.attach(noFull, CellChannelConnectionType.CLIENT_TYPE);
        List<Incoming> listeners = pushedB;
        listeners.clear();

        relayFrom(noFull, "no full name");

        assertThat(((TextChatMessage) listeners.get(0).message()).getSenderName())
                .isEqualTo("dave");
    }

    @Test
    void theRelayedNameIsGuestWhenTheUsernameIsMissingOrBlank() {
        ServerSession nullName = registry.create(
                new WonderlandIdentity(null, null, "n@example.org"),
                recordingSink(new ArrayList<>()));
        registry.attach(nullName, CellChannelConnectionType.CLIENT_TYPE);
        ServerSession blankName = registry.create(
                new WonderlandIdentity("   ", "   ", "b@example.org"),
                recordingSink(new ArrayList<>()));
        registry.attach(blankName, CellChannelConnectionType.CLIENT_TYPE);
        List<Incoming> listeners = pushedB;
        listeners.clear();

        relayFrom(nullName, "who am i");
        assertThat(((TextChatMessage) listeners.get(0).message()).getSenderName())
                .isEqualTo("guest");

        relayFrom(blankName, "who am i");
        assertThat(((TextChatMessage) listeners.get(1).message()).getSenderName())
                .isEqualTo("guest");
    }

    // ---- the mover's own view cell: re-home the watch, or not -----------------

    @Test
    void movingTheMoversOwnViewCellReHomesTheWatch() {
        CellID viewCellID = ((ViewCreateResponseMessage) messages(router.routeInbound(
                sessionA, CellCacheConnectionType.CLIENT_TYPE, pack(setView()))).get(0))
                        .getViewCellID();
        flushed(sessionA);
        pushedA.clear();
        pushedB.clear();

        // moving the cell this session's own SET_VIEW minted is the person
        // walking: at != null, so the watch re-homes and the move broadcasts
        List<Message> reply = messages(router.routeInbound(sessionA,
                CellChannelConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(viewCellID,
                        new Vector3f(7f, 8f, 9f), new Quaternion(), 1f))));

        assertThat(reply).hasSize(1);
        assertThat(((MovableMessageResponse) reply.get(0)).getActionType())
                .isEqualTo(MovableMessageResponse.ActionType.MOVE_MODIFIED);
        assertThat(world.find(viewCellID).getTransform().getTranslation(null))
                .isEqualTo(new Vector3f(7f, 8f, 9f));
        assertThat(pushedA).hasSize(1);
    }

    @Test
    void aMoveWithNoTranslationInTheMessageLandsAtTheOrigin() {
        CellID viewCellID = ((ViewCreateResponseMessage) messages(router.routeInbound(
                sessionA, CellCacheConnectionType.CLIENT_TYPE, pack(setView()))).get(0))
                        .getViewCellID();
        flushed(sessionA);
        pushedA.clear();
        pushedB.clear();

        // a MovableMessage carrying no translation is normalised to the origin
        // by CellTransform, so the move is still accepted, applied and broadcast
        List<Message> reply = messages(router.routeInbound(sessionA,
                CellChannelConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(viewCellID,
                        null, new Quaternion(), 1f))));

        assertThat(reply).hasSize(1);
        assertThat(world.find(viewCellID).getTransform().getTranslation(null))
                .isEqualTo(new Vector3f(0f, 0f, 0f));
        assertThat(pushedA).hasSize(1);
        assertThat(((MovableMessage) pushedA.get(0).message()).getActionType())
                .isEqualTo(MovableMessage.ActionType.MOVED);
    }

    // ---- pack(): a message the codec cannot serialise -------------------------

    /** A Message the codec cannot serialise: its one field is not Serializable. */
    private static final class UnpackableMessage extends Message {
        private static final long serialVersionUID = 1L;
        @SuppressWarnings("unused")
        private final Object payload = new Object();
    }

    @Test
    void packingAnUnserialisableMessageRethrowsAsIllegalState() {
        assertThatThrownBy(() -> router.pack(new UnpackableMessage(), sessionA,
                CellCacheConnectionType.CLIENT_TYPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to pack");
    }

    // ---- broadcastConfigure(): the unknown-cell and no-cache arms -------------

    @Test
    void configureOfAnUnknownCellBroadcastsNothing() {
        router.broadcastConfigure(new CellID(9999L));

        assertThat(pushedA).isEmpty();
        assertThat(pushedB).isEmpty();
    }

    @Test
    void configureSkipsSessionsWithoutACacheConnection() {
        CellID cellID = world.newCellID();
        world.addCell(cellID, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());
        List<Incoming> channelOnly = new ArrayList<>();
        ServerSession channelSession = registry.create(ALICE, recordingSink(channelOnly));
        registry.attach(channelSession, CellChannelConnectionType.CLIENT_TYPE);
        pushedA.clear();
        pushedB.clear();

        router.broadcastConfigure(cellID);

        // the two cache-attached sessions get the configure; the channel-only
        // one is skipped by the isAttached guard
        assertThat(pushedA).hasSize(1);
        assertThat(pushedB).hasSize(1);
        assertThat(channelOnly).isEmpty();
    }

    // ---- eventRadiusOf(): the fallbacks and the positive-radius arm -----------

    @Test
    void eventRadiusOfFallsBackForUnknownAndNonSphericalCells() {
        // an id the world does not host -> the cell == null guard
        assertThat(router.eventRadiusOf(new CellID(9999L)))
                .isEqualTo(ProtocolRouter.DEFAULT_EVENT_RADIUS);
        // a hosted cell whose bounds are not a sphere -> the instanceof arm
        CellID boxy = world.newCellID();
        world.putCell(new WorldCell(boxy, CellID.getEnvironmentCellID(),
                "some.client.Cell", "boxy", null, new CellTransform(),
                new CellClientState()));
        assertThat(router.eventRadiusOf(boxy))
                .isEqualTo(ProtocolRouter.DEFAULT_EVENT_RADIUS);
    }

    @Test
    void eventRadiusOfUsesAPositiveSphereRadiusAndIgnoresANonPositiveOne() {
        CellID round = world.newCellID();
        world.putCell(new WorldCell(round, CellID.getEnvironmentCellID(),
                "some.client.Cell", "round", new BoundingSphere(5f, new Vector3f()),
                new CellTransform(), new CellClientState()));
        assertThat(router.eventRadiusOf(round)).isEqualTo(5f);

        // a zero radius is not a reach: it falls back to the documented default
        CellID flat = world.newCellID();
        world.putCell(new WorldCell(flat, CellID.getEnvironmentCellID(),
                "some.client.Cell", "flat", new BoundingSphere(0f, new Vector3f()),
                new CellTransform(), new CellClientState()));
        assertThat(router.eventRadiusOf(flat))
                .isEqualTo(ProtocolRouter.DEFAULT_EVENT_RADIUS);
    }

    // ---- detach(): a cache connection with no view cell, or an already-gone one

    @Test
    void detachOfACacheConnectionThatNeverSetAViewIsSilent() {
        // sessionB never sent SET_VIEW, so no view cell is recorded for it
        pushedA.clear();
        pushedB.clear();

        router.detach(sessionB, CellCacheConnectionType.CLIENT_TYPE);

        assertThat(router.viewCellOf(sessionB)).isNull();
        assertThat(pushedA).isEmpty();
        assertThat(pushedB).isEmpty();
    }

    @Test
    void detachOfACacheConnectionWhoseViewCellIsAlreadyGoneDoesNotBroadcast() {
        CellID viewCellID = ((ViewCreateResponseMessage) messages(router.routeInbound(
                sessionA, CellCacheConnectionType.CLIENT_TYPE, pack(setView()))).get(0))
                        .getViewCellID();
        flushed(sessionA);
        // the cell leaves the world by some other means before the detach lands,
        // but the session still has its id recorded
        world.removeCell(viewCellID);
        pushedA.clear();
        pushedB.clear();

        router.detach(sessionA, CellCacheConnectionType.CLIENT_TYPE);

        // the id is cleared, yet there is no cell to unload, so nothing is fanned
        assertThat(router.viewCellOf(sessionA)).isNull();
        assertThat(pushedA).isEmpty();
        assertThat(pushedB).isEmpty();
    }
}
