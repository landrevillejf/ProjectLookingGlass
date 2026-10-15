/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the router fans a move to its AUDIENCE, not the whole
 * server -- but only once a session is tracked. The InterestManager existed as a
 * tested kernel that nothing consulted; these cases prove it now drives the live
 * broadcast, and that the default-open rule keeps the pre-P5.4 behaviour intact
 * until an avatar actually moves.
 */
package org.jdesktop.wonderland.server.comms;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.jdesktop.wonderland.server.net.InterestManager;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProtocolRouterInterestTest {

    private static final WonderlandIdentity ALICE =
            new WonderlandIdentity("alice", "Alice A", "alice@example.org");
    private static final WonderlandIdentity BOB =
            new WonderlandIdentity("bob", "Bob B", "bob@example.org");
    private static final WonderlandIdentity CAROL =
            new WonderlandIdentity("carol", "Carol C", "carol@example.org");

    private record Incoming(ServerSession session, ConnectionType type, Message message,
            short clientID) {
    }

    private WorldModel world;
    private SessionRegistry registry;
    private InterestManager interest;
    private ProtocolRouter router;
    private List<Incoming> pushedA;
    private List<Incoming> pushedB;
    private List<Incoming> pushedC;
    private ServerSession alice;
    private ServerSession bob;
    private ServerSession carol;

    @BeforeEach
    void setUp() {
        world = new WorldModel();
        registry = new SessionRegistry();
        interest = new InterestManager();
        router = new ProtocolRouter(world, registry,
                ProtocolRouter.VIEW_CELL_CLASS, interest);
        pushedA = new ArrayList<>();
        pushedB = new ArrayList<>();
        pushedC = new ArrayList<>();
        alice = registry.create(ALICE, recordingSink(pushedA));
        bob = registry.create(BOB, recordingSink(pushedB));
        carol = registry.create(CAROL, recordingSink(pushedC));
        for (ServerSession s : List.of(alice, bob, carol)) {
            registry.attach(s, CellCacheConnectionType.CLIENT_TYPE);
            registry.attach(s, CellChannelConnectionType.CLIENT_TYPE);
        }
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

    /** Drive the SET_VIEW handshake and drain the deferred queue a transport would. */
    private CellID establishView(ServerSession session) {
        router.routeInbound(session, CellCacheConnectionType.CLIENT_TYPE,
                pack(CellHierarchyMessage.newSetAvatarMessage(session.getUserID()
                        .getUsername() + "-avatar")));
        while (router.flush(session) != null) {
            // drain: the view-cell LOAD chain is irrelevant to the assertions below
        }
        return router.viewCellOf(session);
    }

    /** Move a session's OWN avatar to a place -- this is what tracks its watch. */
    private void walkAvatarTo(ServerSession session, CellID ownView, float x, float y, float z) {
        router.routeInbound(session, CellChannelConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(ownView,
                        new Vector3f(x, y, z), new Quaternion())));
    }

    // ---- the default-open rule -------------------------------------------------

    @Test
    void beforeAnyAvatarMovesEveryChannelSessionStillHearsTheMove() {
        // Nobody has walked, so nobody is tracked: this is the pre-P5.4 world and
        // must be byte-identical -- a furniture move reaches every session.
        CellID cube = world.newCellID();
        world.addCell(cube, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());
        assertThat(interest.size()).isZero();

        router.routeInbound(alice, CellChannelConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(cube,
                        new Vector3f(5f, 0f, 0f), new Quaternion())));

        assertThat(pushedA).hasSize(1);   // the mover's own echo
        assertThat(pushedB).hasSize(1);   // untracked, still hears
        assertThat(pushedC).hasSize(1);   // untracked, still hears
    }

    @Test
    void anAvatarWalkTracksItsWatchAndAnUnrelatedMoveDoesNotTrackAnyone() {
        CellID aliceView = establishView(alice);
        CellID cube = world.newCellID();
        world.addCell(cube, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());

        // a furniture move by Alice must NOT re-home Alice's eye
        router.routeInbound(alice, CellChannelConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(cube,
                        new Vector3f(1f, 1f, 1f), new Quaternion())));
        assertThat(interest.isTracked(alice.getID())).isFalse();

        // walking her own avatar cell does
        walkAvatarTo(alice, aliceView, 0f, 0f, 0f);
        assertThat(interest.isTracked(alice.getID())).isTrue();
    }

    // ---- spatial filtering engages once tracked --------------------------------

    @Test
    void aMoveNearOneAvatarReachesThatAvatarButNotAFarAwayTrackedOne() {
        CellID aliceView = establishView(alice);
        CellID bobView = establishView(bob);
        // Alice at the origin, Bob a kilometre out on X -- both tracked
        walkAvatarTo(alice, aliceView, 0f, 0f, 0f);
        walkAvatarTo(bob, bobView, 1000f, 0f, 0f);
        pushedA.clear();
        pushedB.clear();
        pushedC.clear();

        CellID cube = world.newCellID();
        world.addCell(cube, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());

        // Carol (never walked -> untracked) moves a cube right next to Alice.
        router.routeInbound(carol, CellChannelConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(cube,
                        new Vector3f(5f, 0f, 0f), new Quaternion())));

        MovableMessage toAlice = onlyMove(pushedA);
        assertThat(toAlice).isNotNull();
        assertThat(toAlice.getActionType()).isEqualTo(MovableMessage.ActionType.MOVED);
        // Bob is tracked and a kilometre away: the audience excludes him, so he
        // receives nothing at all -- the whole point of interest management.
        assertThat(pushedB).isEmpty();
        // Carol is the sender: her own echo arrives so the client-side suppress
        // rule still fires.
        assertThat(onlyMove(pushedC)).isNotNull();
    }

    @Test
    void aTrackedAvatarWalksIntoRangeAndStartsHearing() {
        CellID aliceView = establishView(alice);
        CellID bobView = establishView(bob);
        walkAvatarTo(alice, aliceView, 0f, 0f, 0f);
        walkAvatarTo(bob, bobView, 1000f, 0f, 0f);

        CellID cube = world.newCellID();
        world.addCell(cube, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());

        // Bob walks to Alice's neighbourhood (this broadcast itself is near them
        // both, so it is delivered to Alice/Bob/Carol and re-homes Bob).
        walkAvatarTo(bob, bobView, 3f, 0f, 0f);
        pushedA.clear();
        pushedB.clear();
        pushedC.clear();

        // Now a fresh event right between them: both tracked, both near.
        router.routeInbound(carol, CellChannelConnectionType.CLIENT_TYPE,
                pack(MovableMessage.newMoveRequestMessage(cube,
                        new Vector3f(5f, 0f, 0f), new Quaternion())));

        assertThat(onlyMove(pushedA)).isNotNull();   // Alice back in range
        assertThat(onlyMove(pushedB)).isNotNull();   // Bob now near, starts hearing
    }

    @Test
    void aDepartedSessionIsForgottenAndStopsBeingTracked() {
        CellID aliceView = establishView(alice);
        walkAvatarTo(alice, aliceView, 0f, 0f, 0f);
        assertThat(interest.isTracked(alice.getID())).isTrue();

        router.detach(alice, CellCacheConnectionType.CLIENT_TYPE);

        assertThat(interest.isTracked(alice.getID())).isFalse();
    }

    /** The single MOVED broadcast delivered to a session, or null if none arrived. */
    private static MovableMessage onlyMove(List<Incoming> into) {
        MovableMessage found = null;
        for (Incoming in : into) {
            if (in.message() instanceof MovableMessage m
                    && m.getActionType() == MovableMessage.ActionType.MOVED) {
                assertThat(found).isNull();   // exactly one, never a duplicate fan-out
                found = m;
            }
        }
        return found;
    }
}
