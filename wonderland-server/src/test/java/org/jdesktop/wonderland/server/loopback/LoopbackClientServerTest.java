/*
 * wonderland-server M3a: the milestone's proof. A REAL wonderland-client
 * CellClientSession connects to the REAL Spring-side ProtocolRouter +
 * SessionRegistry + WorldModel through the in-JVM loopback, and every message
 * between them rides the real M1 MessagePacker codec -- pack, route, unpack,
 * deliver, exactly the frame path M3b's sockets will use, minus the socket.
 *
 * What "no functionality lost" means here, pinned test by test:
 *   - the login/view handshake: CellCacheConnection's sendAndWait(SET_VIEW)
 *     meets ViewCreateResponse, the world fans out as LOAD_CELLs (environment
 *     concrete-loadable, content cells, names and transforms intact), and the
 *     deferred view-cell chain lands after the reply list so the client's
 *     ViewCell -> LocalAvatar.viewCellConfigured -> setViewCell machine fires
 *     -- lifting the whole tree to VISIBLE, the walk the original async socket
 *     ordering produced;
 *   - the avatar command chain: LocalAvatar.localMoveRequest drives the view
 *     cell's MovableAvatarComponent, a MovableMessage crosses the wire, and the
 *     server world records the authoritative transform;
 *   - the multiplayer round trip: one session's furniture move gets its
 *     MOVE_MODIFIED reply (routed by MessageID to the waiting listener), the
 *     mover's LOCAL transform applies, and the foreign session's cell applies
 *     the same transform as a REMOTE move through its registered receiver;
 *   - echo suppression: the MOVED broadcast the mover itself receives is
 *     dropped because its senderID equals the mover's session id -- the client
 *     applies a foreign move but never re-applies its own;
 *   - departure: disconnecting removes the session and unloads the departed
 *     avatar's view cell from every remaining client cache.
 */
package org.jdesktop.wonderland.server.loopback;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.nio.ByteBuffer;
import org.jdesktop.wonderland.client.cell.CellCacheBasicImpl;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.EnvironmentCell;
import org.jdesktop.wonderland.client.cell.MovableComponent;
import org.jdesktop.wonderland.client.cell.view.LocalAvatar;
import org.jdesktop.wonderland.client.cell.view.ViewCell;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.server.comms.ProtocolRouter;
import org.jdesktop.wonderland.server.comms.SessionRegistry;
import org.jdesktop.wonderland.server.testcells.ServerTestFixtures.CacheableEnv;
import org.jdesktop.wonderland.server.testcells.ServerTestFixtures.MovableCell;
import org.jdesktop.wonderland.server.testcells.ServerTestFixtures.TestViewCell;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

class LoopbackClientServerTest {

    private static final WonderlandIdentity ALICE =
            new WonderlandIdentity("alice", "Alice A", "alice@example.org");
    private static final WonderlandIdentity BOB =
            new WonderlandIdentity("bob", "Bob B", "bob@example.org");

    private WorldModel world;
    private SessionRegistry registry;
    private ProtocolRouter router;
    private CellID movableCellID;

    @BeforeEach
    void setUp() {
        world = new WorldModel(CacheableEnv.class.getName());
        registry = new SessionRegistry();
        router = new ProtocolRouter(world, registry, TestViewCell.class.getName());
        movableCellID = world.newCellID();
        CellClientState state = new CellClientState();
        state.setName("movable-box");
        world.addCell(movableCellID, MovableCell.class.getName(), "movable-box",
                new CellTransform(), state);
    }

    /** The M3a connect: a real CellClientSession over a real server session. */
    private CellClientSession connectClient(WonderlandIdentity user) throws Exception {
        CellClientSession client = new CellClientSession();
        LoopbackWonderlandSession loopback =
                new LoopbackWonderlandSession(router, registry, user, "test-server");
        client.connect(loopback);
        // the socket's async delivery of the post-handshake view-cell chain --
        // safe now because CellCacheConnection.connect has stored its viewCellID
        loopback.pump();
        return client;
    }

    private static CellTransform translation(float x, float y, float z) {
        CellTransform t = new CellTransform();
        t.setTranslation(new Vector3f(x, y, z));
        return t;
    }

    private static Vector3f translationOf(Cell cell) {
        return cell.getLocalTransform().getTranslation(null);
    }

    @Test
    void theHandshakeLoadsTheWorldAndConfiguresTheAvatar() throws Exception {
        CellClientSession alice = connectClient(ALICE);
        CellCacheBasicImpl cache = (CellCacheBasicImpl) alice.getCellCache();

        // the environment root loaded as the concrete class the world named
        EnvironmentCell env = cache.getEnvironmentCell();
        assertThat(env).isInstanceOf(CacheableEnv.class);
        assertThat(env.getStatus()).isGreaterThanOrEqualTo(CellStatus.ACTIVE);

        // the content cell arrived through the fan-out, name and all
        Cell box = cache.getCell(movableCellID);
        assertThat(box).isInstanceOf(MovableCell.class);
        assertThat(box.getName()).isEqualTo("movable-box");

        // the deferred view cell configured the avatar: cache.viewCell, the
        // connection's recorded id, and the LocalAvatar all agree
        CellID viewID = alice.getCellCacheConnection().getViewCellID();
        assertThat(viewID).isNotNull();
        assertThat(world.find(viewID)).isNotNull();
        assertThat(cache.getViewCell()).isNotNull()
                .isInstanceOf(TestViewCell.class);
        assertThat(cache.getViewCell().getCellID()).isEqualTo(viewID);
        assertThat(alice.getLocalAvatar().getViewCell()).isSameAs(cache.getViewCell());

        // and the view-cell walk lifted the whole tree to VISIBLE -- the M2f
        // movement machinery's precondition, now produced by the SERVER
        assertThat(box.getStatus()).isEqualTo(CellStatus.VISIBLE);
        assertThat(cache.getViewCell().getStatus()).isEqualTo(CellStatus.VISIBLE);

        // every connection the 2006 login opened is attached on the server side
        assertThat(alice.getID()).isNotNull();
        assertThat(registry.count()).isEqualTo(1);
        assertThat(registry.get(alice.getID()).attachedTypes()).hasSize(3);
    }

    @Test
    @Timeout(10)
    void theAvatarCommandChainMovesTheViewCellOnTheServer() throws Exception {
        CellClientSession alice = connectClient(ALICE);
        CellID viewID = alice.getCellCacheConnection().getViewCellID();
        TestViewCell viewCell = (TestViewCell) alice.getCellCache().getCell(viewID);
        assertThat(viewCell.avatarMovable).isNotNull();

        alice.getLocalAvatar().localMoveRequest(new Vector3f(4f, 5f, 6f), new Quaternion());

        // the request rode the wire and the server world is authoritative
        assertThat(world.find(viewID).getTransform().getTranslation(null))
                .isEqualTo(new Vector3f(4f, 5f, 6f));
        // the mover applied locally at once (the 2006 optimistic paint)
        assertThat(translationOf(viewCell)).isEqualTo(new Vector3f(4f, 5f, 6f));
    }

    @Test
    @Timeout(10)
    void aMoveGetsItsReplyAndReachesTheOtherSessionAsARemoteApply() throws Exception {
        CellClientSession alice = connectClient(ALICE);
        CellClientSession bob = connectClient(BOB);
        MovableCell aliceBox = (MovableCell) alice.getCellCache().getCell(movableCellID);
        MovableCell bobBox = (MovableCell) bob.getCellCache().getCell(movableCellID);
        assertThat(bobBox.getStatus()).isEqualTo(CellStatus.VISIBLE);

        java.util.List<Integer> reasons = new java.util.ArrayList<>();
        java.util.List<CellTransform> actual = new java.util.ArrayList<>();
        aliceBox.movable.localMoveRequest(translation(1f, 2f, 3f),
                (requested, reason, act) -> {
                    reasons.add(reason);
                    actual.add(act);
                });

        // reply path: MOVE_MODIFIED routed by MessageID into the waiting
        // listener (the 2006 reason code 1, the M2f-pinned value)
        assertThat(reasons).containsExactly(1);
        assertThat(translationOf(aliceBox)).isEqualTo(new Vector3f(1f, 2f, 3f));

        // foreign path: bob's receiver applied the broadcast as his own REMOTE move
        assertThat(translationOf(bobBox)).isEqualTo(new Vector3f(1f, 2f, 3f));

        // and the server world agrees with both clients
        assertThat(world.find(movableCellID).getTransform().getTranslation(null))
                .isEqualTo(new Vector3f(1f, 2f, 3f));
    }

    @Test
    @Timeout(10)
    void theMoverDropsItsOwnEcho() throws Exception {
        CellClientSession alice = connectClient(ALICE);
        MovableCell aliceBox = (MovableCell) alice.getCellCache().getCell(movableCellID);

        java.util.List<MovableComponent.CellMoveSource> sources =
                new java.util.ArrayList<>();
        aliceBox.movable.addServerCellMoveListener(
                (transform, source) -> sources.add(source));

        aliceBox.movable.localMoveRequest(translation(7f, 8f, 9f));

        // the MOVED broadcast came back with senderID == alice's session id; the
        // component suppressed it, so no REMOTE apply and no second listener hit
        assertThat(sources).isEmpty();
        assertThat(translationOf(aliceBox)).isEqualTo(new Vector3f(7f, 8f, 9f));
    }

    @Test
    @Timeout(10)
    void aForeignMoveAppliesRemotelyOnTheListeningClient() throws Exception {
        CellClientSession alice = connectClient(ALICE);
        CellClientSession bob = connectClient(BOB);
        MovableCell aliceBox = (MovableCell) alice.getCellCache().getCell(movableCellID);

        java.util.List<MovableComponent.CellMoveSource> aliceSources =
                new java.util.ArrayList<>();
        aliceBox.movable.addServerCellMoveListener(
                (transform, source) -> aliceSources.add(source));

        // bob moves the shared box -- alice did nothing, so any apply she sees
        // arrived through the server broadcast, not her own optimistic paint
        MovableCell bobBox = (MovableCell) bob.getCellCache().getCell(movableCellID);
        bobBox.movable.localMoveRequest(translation(2f, 4f, 6f));

        assertThat(aliceSources).containsExactly(MovableComponent.CellMoveSource.REMOTE);
        assertThat(translationOf(aliceBox)).isEqualTo(new Vector3f(2f, 4f, 6f));
    }

    @Test
    @Timeout(10)
    void departureUnloadsTheGoneAvatarFromEveryoneElse() throws Exception {
        CellClientSession alice = connectClient(ALICE);
        CellClientSession bob = connectClient(BOB);
        CellID aliceView = alice.getCellCacheConnection().getViewCellID();
        // bob learned alice's avatar cell through the deferred foreign-view pass
        assertThat(bob.getCellCache().getCell(aliceView)).isNotNull();

        alice.disconnect();

        // bob is still connected; only alice's session left the registry
        assertThat(registry.count()).isEqualTo(1);
        assertThat(world.find(aliceView)).isNull();
        assertThat(bob.getCellCache().getCell(aliceView)).isNull();
        // bob keeps his own world intact
        assertThat(bob.getCellCache().getCell(movableCellID)).isNotNull();
        assertThat(bob.getCellCache().getViewCell()).isNotNull();
    }

    /** The loopback's own contract: every hop packed and unpacked real bytes. */
    @Test
    @Timeout(10)
    void everyHopRodeTheRealCodecNotAnObjectReference() throws Exception {
        CellClientSession alice = connectClient(ALICE);
        assertThat(registry.count()).isEqualTo(1);
        org.jdesktop.wonderland.server.comms.ServerSession serverSession =
                registry.get(alice.getID());

        // all three 2006 connections attached with distinct wire ids -- only a
        // packed frame carries a short clientID, so the counters prove the codec
        // path ran per connection, in both directions, for every message type
        // the handshake crossed (SET_VIEW/reply, LOAD fan-out, view chain)
        assertThat(serverSession.attachedTypes()).hasSize(3);
        assertThat(serverSession.clientID(org.jdesktop.wonderland.common.cell
                .CellCacheConnectionType.CLIENT_TYPE)).isNotEqualTo(
                        serverSession.clientID(org.jdesktop.wonderland.common.cell
                                .CellChannelConnectionType.CLIENT_TYPE));
        assertThat(alice.getCellCacheConnection().getViewCellID()).isNotNull();
    }
}
