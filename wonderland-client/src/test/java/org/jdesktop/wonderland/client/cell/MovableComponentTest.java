/*
 * M2f: the movable-cell movement machine, driven through the REAL pipeline the
 * session wires (loopback transport, hierarchy load, the session-installed
 * ChannelComponent). Pins the ACTIVE-rung receiver registration on the cell's
 * actual channel (the seam in MovableComponent's header: no reflective
 * injection), the outbound throttled request, the self-echo ignore (sender id
 * == session id), the REMOTE apply + notify for a foreign move, the
 * move-modified and error reply paths on the response listener, the
 * not-connected warning, and the DISK-rung receiver removal.
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.math.Vector3f;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.MovableCell;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.TestResponse;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessageResponse;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.messages.ErrorMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

class MovableComponentTest {

    private static final CellID CONNECT_VIEW = new CellID(7777L);

    private CellClientSession session;
    private PipelineSession loop;
    private CellCacheBasicImpl cache;

    @BeforeEach
    void setUp() throws Exception {
        session = new CellClientSession();
        loop = new PipelineSession();
        loop.responder = (c, msg) -> {
            if (msg instanceof CellHierarchyMessage
                    && ((CellHierarchyMessage) msg).getActionType()
                            == CellHierarchyMessage.ActionType.SET_VIEW) {
                c.messageReceived(new ViewCreateResponseMessage(
                        msg.getMessageID(), CONNECT_VIEW));
            }
        };
        session.connect(loop);
        cache = (CellCacheBasicImpl) session.getCellCache();
    }

    /** A loaded MovableCell; the detached view makes the walk end at VISIBLE. */
    private MovableCell loadMovable(CellID id) {
        cache.setViewCell(CellTestFixtures.viewCell(cache));
        return (MovableCell) cache.loadCell(id, MovableCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f),
                CellTestFixtures.namedState("m-" + id), "m-" + id);
    }

    private static Vector3f translationOf(Cell cell) {
        return cell.getLocalTransform().getTranslation(null);
    }

    /** the moves only -- outbound also holds connect's SET_VIEW message */
    private List<MovableMessage> sentMoves() {
        List<MovableMessage> out = new ArrayList<>();
        for (Message m : loop.outbound) {
            if (m instanceof MovableMessage mm) {
                out.add(mm);
            }
        }
        return out;
    }

    @Test
    void activeLoadRegistersOnTheRealChannelAndFirstMoveRunsInline() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);
        assertThat(cell.getStatus()).isEqualTo(CellStatus.VISIBLE);

        ChannelComponent ch = (ChannelComponent) cell.getChannel();
        assertThat(ch.getMessageReceiver(MovableMessage.class)).isNotNull();

        cell.movable.localMoveRequest(CellTestFixtures.translation(1f, 2f, 3f));

        // first schedule of a fresh Throttle runs inline: one message out ...
        assertThat(sentMoves()).hasSize(1);
        assertThat(sentMoves().get(0).getCellID()).isEqualTo(id);
        // ... and the LOCAL transform applied immediately
        assertThat(translationOf(cell)).isEqualTo(new Vector3f(1f, 2f, 3f));
    }

    @Test
    void foreignMoveAppliesRemotelyAndNotifiesListeners() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);

        List<CellTransform> moved = new ArrayList<>();
        List<MovableComponent.CellMoveSource> sources = new ArrayList<>();
        cell.movable.addServerCellMoveListener((t, s) -> {
            moved.add(t);
            sources.add(s);
        });

        MovableMessage inbound =
                MovableMessage.newMoveRequestMessage(id, CellTestFixtures.translation(5, 6, 7));
        session.getCellChannelConnection().handleMessage(inbound); // senderID null -> ZERO

        assertThat(translationOf(cell)).isEqualTo(new Vector3f(5f, 6f, 7f));
        assertThat(sources).containsExactly(MovableComponent.CellMoveSource.REMOTE);
        assertThat(moved.get(0).getTranslation(null)).isEqualTo(new Vector3f(5f, 6f, 7f));

        cell.movable.removeServerCellMoveListener((t, s) -> {
        }); // removal of an unknown listener is a silent no-op
    }

    @Test
    void selfEchoIsIgnored() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);

        List<CellTransform> moved = new ArrayList<>();
        cell.movable.addServerCellMoveListener((t, s) -> moved.add(t));

        MovableMessage echo =
                MovableMessage.newMoveRequestMessage(id, CellTestFixtures.translation(9, 9, 9));
        echo.setSenderID(BigInteger.ONE); // the loopback session's own id
        session.getCellChannelConnection().handleMessage(echo);

        assertThat(moved).isEmpty();
        assertThat(translationOf(cell)).isEqualTo(new Vector3f(0f, 0f, 0f));
    }

    @Test
    void moveModifiedReplyReachesTheListener() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);
        loop.responder = (c, msg) -> {
            if (msg instanceof MovableMessage) {
                c.messageReceived(MovableMessageResponse.newMoveModifiedMessage(
                        msg.getMessageID(), new Vector3f(4f, 5f, 6f),
                        ((MovableMessage) msg).getRotation(), 1f));
            }
        };

        List<CellTransform> requested = new ArrayList<>();
        List<Integer> reasons = new ArrayList<>();
        List<CellTransform> actual = new ArrayList<>();
        cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3),
                (req, reason, act) -> {
                    requested.add(req);
                    reasons.add(reason);
                    actual.add(act);
                });

        assertThat(requested).containsExactly((CellTransform) null);
        assertThat(reasons).containsExactly(1);
        assertThat(actual.get(0).getTranslation(null)).isEqualTo(new Vector3f(4f, 5f, 6f));
    }

    @Test
    void errorReplyWithoutAListenerIsWarned() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);
        loop.responder = (c, msg) -> {
            if (msg instanceof MovableMessage) {
                c.messageReceived(new ErrorMessage(msg.getMessageID(), "move refused"));
            }
        };

        List<LogRecord> warnings = CellTestFixtures.capture(
                MovableComponent.class, Level.WARNING,
                () -> cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3)));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).startsWith("Error sending move: move refused");
    }

    @Test
    void moveOnADisconnectedChannelWarnsAndSendsNothing() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);
        session.disconnect();

        List<LogRecord> warnings = CellTestFixtures.capture(
                MovableComponent.class, Level.WARNING,
                () -> cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3)));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage())
                .isEqualTo("Cell channel not connected when moving cell " + id);
        assertThat(sentMoves()).isEmpty();
    }

    @Test
    void aCellThatNeverReachedActiveHasNoChannelAndWarns() {
        CellID id = CellTestFixtures.id(1);
        // no view set, not a ViewCell: the load guard leaves the cell below ACTIVE
        MovableCell cell = (MovableCell) cache.loadCell(id, MovableCell.class.getName(),
                null, null, CellTestFixtures.translation(0f, 0f, 0f),
                CellTestFixtures.namedState("m-" + id), "m-" + id);
        assertThat(cell.getStatus().ordinal())
                .isLessThan(CellStatus.ACTIVE.ordinal());

        List<LogRecord> warnings = CellTestFixtures.capture(
                MovableComponent.class, Level.WARNING,
                () -> cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3)));
        assertThat(warnings).hasSize(1);
        assertThat(sentMoves()).isEmpty();
    }

    @Test
    void unloadRemovesTheReceiver() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);
        ChannelComponent ch = (ChannelComponent) cell.getChannel();
        assertThat(ch.getMessageReceiver(MovableMessage.class)).isNotNull();

        cache.unloadCell(id);
        assertThat(cell.getStatus()).isEqualTo(CellStatus.DISK);
        assertThat(ch.getMessageReceiver(MovableMessage.class)).isNull();
    }

    @Test
    @Timeout(10)
    void secondMoveWithinTheWindowIsBufferedThenSent() throws InterruptedException {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);

        cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3));
        cell.movable.localMoveRequest(CellTestFixtures.translation(4, 5, 6));
        // the throttle (2/sec -> 500 ms window): the second send is pending
        assertThat(sentMoves()).hasSize(1);

        long deadline = System.currentTimeMillis() + 4000;
        while (sentMoves().size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(sentMoves()).hasSize(2);
        assertThat(sentMoves().get(1).getCellID()).isEqualTo(id);
        assertThat(translationOf(cell)).isEqualTo(new Vector3f(4f, 5f, 6f));
    }

    @Test
    void channellessActiveWarnsAndSkipsRegistration() {
        // the instanceof-guarded seam rung: a cell whose channel is not the
        // real ChannelComponent (here: none at all) warns and registers nothing
        CellTestFixtures.MovableCell orphan = new CellTestFixtures.MovableCell(
                CellTestFixtures.id(50), null);
        List<LogRecord> warnings = CellTestFixtures.capture(
                MovableComponent.class, Level.WARNING,
                () -> orphan.movable.setStatus(CellStatus.ACTIVE, true));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage())
                .startsWith("Cell has no channel when moving cell");
        assertThat(orphan.movable.msgReceiver).isNull();
    }

    @Test
    void defensiveRungAndListenerListGuards() {
        // DISK rung with nothing registered is a no-op
        CellTestFixtures.MovableCell fresh = new CellTestFixtures.MovableCell(
                CellTestFixtures.id(51), null);
        fresh.movable.setStatus(CellStatus.DISK, false);
        assertThat(fresh.movable.msgReceiver).isNull();

        // ACTIVE rung when a receiver already exists skips re-registration
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);
        ChannelComponent ch = (ChannelComponent) cell.getChannel();
        MessageChannel.MessageReceiver first = ch.getMessageReceiver(MovableMessage.class);
        cell.movable.setStatus(CellStatus.ACTIVE, true);
        assertThat(ch.getMessageReceiver(MovableMessage.class)).isSameAs(first);

        // listener-list guards: remove-before-add is null-safe, second add
        // shares the existing list
        MovableComponent.CellMoveListener l1 = (t, s) -> {
        };
        fresh.movable.removeServerCellMoveListener(l1);
        fresh.movable.addServerCellMoveListener(l1);
        fresh.movable.addServerCellMoveListener((t, s) -> {
        });
        assertThat(fresh.movable.serverMoveListeners).hasSize(2);
    }

    @Test
    void nonErrorReplyWithNoListenerIsSilent() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);
        loop.responder = (c, msg) -> {
            if (msg instanceof MovableMessage mm) {
                c.messageReceived(MovableMessageResponse.newMoveModifiedMessage(
                        mm.getMessageID(), new Vector3f(1f, 1f, 1f), mm.getRotation(), 1f));
            }
        };
        List<LogRecord> warnings = CellTestFixtures.capture(
                MovableComponent.class, Level.WARNING,
                () -> cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3)));
        assertThat(warnings).isEmpty(); // the null-listener listener only logs errors
    }

    @Test
    void foreignReplyWithListenerReportsNoActualTransform() {
        CellID id = CellTestFixtures.id(1);
        MovableCell cell = loadMovable(id);
        loop.responder = (c, msg) -> {
            if (msg instanceof MovableMessage mm) {
                c.messageReceived(new TestResponse(mm.getMessageID())); // not a move response
            }
        };

        List<CellTransform> actual = new ArrayList<>();
        cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3),
                (req, reason, act) -> actual.add(act));
        assertThat(actual).containsExactly((CellTransform) null);
    }

    @Test
    void receiverWithoutAnySessionIdentityAppliesMovesRemotely() {
        // a cache with no session wired: selfSessionID() -> null, so even a
        // null sender (ZERO) is "not me" -- the move lands as REMOTE
        CellCacheBasicImpl bare = new CellCacheBasicImpl();
        CellChannelConnection conn = new CellChannelConnection();
        bare.setCellChannelConnection(conn);
        CellID id = CellTestFixtures.id(60);
        CellTestFixtures.MovableCell sessionless = new CellTestFixtures.MovableCell(id, bare);
        ChannelComponent ch = new ChannelComponent(sessionless);
        sessionless.setMessageChannel(ch);
        sessionless.movable.setStatus(CellStatus.ACTIVE, true);

        ch.messageReceived(
                MovableMessage.newMoveRequestMessage(id, CellTestFixtures.translation(2, 2, 2)));
        assertThat(translationOf(sessionless)).isEqualTo(new Vector3f(2f, 2f, 2f));

        // and a cell with no cache at all: the null-cache first step of the
        // identity chain, no NPE on the reply path. Its own channel (the
        // duplicate-class guard above forbids sharing one between cells)
        ChannelComponent chOrphan = new ChannelComponent(
                new CellTestFixtures.CacheableCell(id, bare));
        CellTestFixtures.MovableCell orphan = new CellTestFixtures.MovableCell(id, null);
        orphan.setMessageChannel(chOrphan);
        orphan.movable.setStatus(CellStatus.ACTIVE, true);
        chOrphan.messageReceived(
                MovableMessage.newMoveRequestMessage(id, CellTestFixtures.translation(3, 3, 3)));
        assertThat(orphan.getLocalTransform().getTranslation(null))
                .isEqualTo(new Vector3f(3f, 3f, 3f));
    }
}
