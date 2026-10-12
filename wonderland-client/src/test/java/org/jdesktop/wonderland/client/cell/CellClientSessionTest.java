/*
 * M2e end-to-end: the whole headless pipeline the session wires -- loopback
 * session connect (view registration through CellCacheConnection's
 * sendAndWait), a hierarchy LOAD landing on the cache, the session hook
 * installing the REAL ChannelComponent on cellLoaded, the walk to ACTIVE
 * registering its receiver on the delivery, an inbound CellMessage arriving
 * at that receiver, an outbound channel send surfacing on the session with
 * the cell id filled in, and a missing-parent load asking for a reparent
 * over the EDIT connection. Placement in client.cell is forced: the M2c/M2d
 * fixtures are package-private; the class under test is public.
 */
package org.jdesktop.wonderland.client.cell;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.CacheableCell;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.client.comms.ClientConnection;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.client.cell.view.ClientView;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.messages.CellReparentMessage;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

class CellClientSessionTest {

    private CellClientSession session;
    private PipelineSession loop;
    private RecordingView view;

    static class ProbeMessage extends CellMessage {
        ProbeMessage() {
            super();
        }

        ProbeMessage(CellID cellID) {
            super(cellID);
        }
    }

    /** Records the view lifecycle the connect() registration drives. */
    static class RecordingView implements ClientView {
        final List<ViewCreateResponseMessage> initialized = new ArrayList<>();
        final List<CellID> configured = new ArrayList<>();

        @Override
        public String getViewID() {
            return "view-1";
        }

        @Override
        public void serverInitialized(ViewCreateResponseMessage response) {
            initialized.add(response);
        }

        @Override
        public void viewCellConfigured(CellID cellID) {
            configured.add(cellID);
        }
    }

    @BeforeEach
    void setUp() {
        session = new CellClientSession(view = new RecordingView());
        loop = new PipelineSession();
        loop.responder = (c, msg) -> {
            if (msg instanceof CellHierarchyMessage
                    && ((CellHierarchyMessage) msg).getActionType()
                            == CellHierarchyMessage.ActionType.SET_VIEW) {
                c.messageReceived(new ViewCreateResponseMessage(
                        msg.getMessageID(), new CellID(7777L)));
            }
        };
    }

    private static CellHierarchyMessage load(CellID id, CellID parent) {
        CellClientState state = new CellClientState();
        state.setName("c-" + id);
        return new CellHierarchyMessage(CellHierarchyMessage.ActionType.LOAD_CELL,
                CacheableCell.class.getName(), null, id, parent,
                CellTestFixtures.translation(0f, 0f, 0f), state, "c-" + id);
    }

    @Test
    @Timeout(5)
    void connectLinksTheThreeConnectionsAndRegistersTheView() throws Exception {
        session.connect(loop);
        assertThat(session.getCellCacheConnection().getStatus())
                .isSameAs(ClientConnection.Status.CONNECTED);
        assertThat(session.getCellChannelConnection().getStatus())
                .isSameAs(ClientConnection.Status.CONNECTED);
        assertThat(session.getCellEditChannelConnection().getStatus())
                .isSameAs(ClientConnection.Status.CONNECTED);
        // the cache connection's override ran the SET_VIEW registration
        assertThat(view.initialized).hasSize(1);
        assertThat(session.getCellCacheConnection().getViewCellID())
                .isEqualTo(new CellID(7777L));
    }

    @Test
    @Timeout(5)
    void disconnectUnlinksEverything() throws Exception {
        session.connect(loop);
        session.disconnect();
        // the faithful quirk: CellCacheConnection.disconnected() only clears
        // its listeners -- the original never called super, so the CACHE
        // connection keeps its CONNECTED status (session membership is gone;
        // a reconnect re-registers it). The channel/edit connections flip.
        assertThat(session.getCellChannelConnection().getStatus())
                .isSameAs(ClientConnection.Status.DISCONNECTED);
        assertThat(session.getCellEditChannelConnection().getStatus())
                .isSameAs(ClientConnection.Status.DISCONNECTED);
        assertThat(session.getCellCacheConnection().getStatus())
                .isSameAs(ClientConnection.Status.CONNECTED);
        assertThat(loop.connections).isEmpty();
        assertThat(loop.getStatus()).isSameAs(PipelineSession.Status.DISCONNECTED);
    }

    @Test
    @Timeout(5)
    void loadedCellGetsTheRealChannelAndFullRoundTrip() throws Exception {
        session.connect(loop);
        CellCacheBasicImpl cache = (CellCacheBasicImpl) session.getCellCache();
        cache.setHasView(true);

        CellID id = CellTestFixtures.id(1);
        session.getCellCacheConnection().handleMessage(load(id, null));

        Cell cell = cache.getCell(id);
        assertThat(cell).isNotNull();
        assertThat(cell.getStatus()).isEqualTo(CellStatus.VISIBLE);
        // the session hook installed the real component, not a stub
        assertThat(cell.getChannel()).isInstanceOf(ChannelComponent.class);

        // inbound: the server talks to the cell, delivery flushes to the
        // receiver registered on the real channel
        List<CellMessage> hits = new ArrayList<>();
        cell.getChannel().addMessageReceiver(ProbeMessage.class, hits::add);
        ProbeMessage inbound = new ProbeMessage(id);
        session.getCellChannelConnection().handleMessage(inbound);
        assertThat(hits).containsExactly(inbound);

        // outbound: a bare message gets the cell's id filled in and rides
        // the channel connection to the session transport
        ProbeMessage outbound = new ProbeMessage();
        cell.getChannel().send(outbound);
        assertThat(outbound.getCellID()).isEqualTo(id);
        assertThat(loop.outbound).contains(outbound);
    }

    @Test
    @Timeout(5)
    void missingParentLoadAsksForReparentOverTheEditConnection() throws Exception {
        session.connect(loop);
        CellID orphan = CellTestFixtures.id(2);
        session.getCellCacheConnection().handleMessage(load(orphan, CellTestFixtures.id(999)));
        // the cache refused the load (parent unknown) and the reparent request
        // rode the edit connection -- "the M2d MessageSink seam, made real"
        assertThat(loop.outbound).filteredOn(CellReparentMessage.class::isInstance).hasSize(1);
        assertThat(((CellReparentMessage) loop.outbound.get(
                loop.outbound.size() - 1)).getCellID()).isEqualTo(orphan);
    }

    @Test
    @Timeout(5)
    void failedAndUnloadedLoadsRunTheSessionHooks() {
        CellCacheBasicImpl cache = (CellCacheBasicImpl) session.getCellCache();

        // a client state whose component classes throw: loadCell fails AFTER
        // registration, firing the failure hook (the session's arm is the
        // documented no-op -- the cell never entered the tree)
        CellID bad = CellTestFixtures.id(3);
        Cell failed = cache.loadCell(bad, CellTestFixtures.CacheableCell.class.getName(),
                null, null, CellTestFixtures.translation(0f, 0f, 0f),
                new CellTestFixtures.ThrowingClientState(), "bad");
        assertThat(failed).isNull();

        // and an unload reaches the session hook's other empty arm
        CellID good = CellTestFixtures.id(4);
        cache.loadCell(good, CacheableCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f),
                CellTestFixtures.namedState("good"), "good");
        cache.unloadCell(good);
        assertThat(cache.getCell(good)).isNull();
    }

    @Test
    void accessorsExposeTheWiredMachine() {
        assertThat(session.getCellCache()).isInstanceOf(CellCacheBasicImpl.class);
        assertThat(session.getCellCacheConnection().getViewCellID()).isNull();
        assertThat(session.getCellCacheProperties()).isNotNull();
        assertThat(session.getCellChannelProperties()).isNotNull();
        assertThat(session.getCellEditProperties()).isNotNull();
    }
}
