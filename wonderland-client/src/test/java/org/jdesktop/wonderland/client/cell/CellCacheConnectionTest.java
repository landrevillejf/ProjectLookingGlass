/*
 * M2e: CellCacheConnection, the server's cell-hierarchy protocol landing on
 * the cache. Drives LOAD/CONFIGURE/UNLOAD/DELETE routing into a real
 * CellCacheBasicImpl (registered exactly as the 2006 createCellCache() did),
 * MessageList fan-out, the hard RuntimeException on a non-hierarchy message,
 * the unimplemented-action warning branch, the connect() view registration
 * over the loopback session (sendAndWait + ViewCreateResponseMessage), and
 * the disconnect clearing of the listener list.
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.CacheableCell;
import org.jdesktop.wonderland.client.cell.view.ClientView;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessageList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellCacheConnectionTest {

    private CellCacheBasicImpl cache;
    private CellCacheConnection conn;
    private RecordingView view;
    private PipelineSession session;

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
        cache = new CellCacheBasicImpl();
        view = new RecordingView();
        conn = new CellCacheConnection(view);
        conn.addListener(cache);
        session = new PipelineSession();
        // play the server's view-registration handler
        session.responder = (c, msg) -> {
            if (msg instanceof CellHierarchyMessage
                    && ((CellHierarchyMessage) msg).getActionType()
                            == CellHierarchyMessage.ActionType.SET_VIEW) {
                c.messageReceived(new ViewCreateResponseMessage(
                        msg.getMessageID(), new CellID(4242L)));
            }
        };
    }

    private static CellHierarchyMessage load(CellID id, String className, CellID parent) {
        return new CellHierarchyMessage(CellHierarchyMessage.ActionType.LOAD_CELL,
                className, null, id, parent, CellTestFixtures.translation(0f, 0f, 0f),
                CellTestFixtures.namedState("c-" + id), "c-" + id);
    }

    @Test
    void loadCellHierarchyMessageOpensACellInCache() {
        CellID id = CellTestFixtures.id(1);
        conn.handleMessage(load(id, CacheableCell.class.getName(), null));
        Cell cell = cache.getCell(id);
        assertThat(cell).isNotNull();
        assertThat(cell.getName()).isEqualTo("c-" + id);
        assertThat(cell.getCellCache()).isSameAs(cache);
    }

    @Test
    void configureAndUnloadAndDeleteRouteToCache() {
        CellID id = CellTestFixtures.id(1);
        conn.handleMessage(load(id, CacheableCell.class.getName(), null));

        conn.handleMessage(new CellHierarchyMessage(
                CellHierarchyMessage.ActionType.CONFIGURE_CELL, null, null, id, null,
                null, CellTestFixtures.namedState("renamed"), "renamed"));
        assertThat(cache.getCell(id).getName()).isEqualTo("renamed");

        conn.handleMessage(new CellHierarchyMessage(
                CellHierarchyMessage.ActionType.UNLOAD_CELL, null, null, id, null,
                null, null, null));
        assertThat(cache.getCell(id)).isNull();

        // reload, then DELETE: the faithful fall-through also hits the empty
        // CHANGE_PARENT arm and must not disturb the registry
        conn.handleMessage(load(id, CacheableCell.class.getName(), null));
        conn.handleMessage(new CellHierarchyMessage(
                CellHierarchyMessage.ActionType.DELETE_CELL, null, null, id, null,
                null, null, null));
        assertThat(cache.getCell(id)).isNull();
    }

    @Test
    void messageListFansOutEachElement() {
        MessageList batch = new MessageList();
        CellID a = CellTestFixtures.id(1);
        CellID b = CellTestFixtures.id(2);
        batch.addMessage(load(a, CacheableCell.class.getName(), null));
        batch.addMessage(load(b, CacheableCell.class.getName(), a));
        conn.handleMessage(batch);
        assertThat(cache.getCell(a)).isNotNull();
        assertThat(cache.getCell(b).getParent()).isSameAs(cache.getCell(a));
    }

    @Test
    void nonHierarchyMessageIsAHardFailure() {
        assertThatThrownBy(() -> conn.handleMessage(new Message() {
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected message type");
    }

    @Test
    void unhandledActionTypeWarnsAndDrops() {
        // SET_VIEW inbound is not part of the five-way cache vocabulary
        conn.handleMessage(CellHierarchyMessage.newSetAvatarMessage("v"));
        assertThat(cache.getCells()).isEmpty();
    }

    @Test
    void changeParentActionTypeIsTheEmptyFallThroughArm() {
        // The 2006 port's faithful quirk: DELETE_CELL falls through into
        // CHANGE_PARENT, whose body is empty. A direct CHANGE_PARENT message
        // therefore lands on the same empty arm -- the registry is untouched,
        // no listener is called, and nothing throws. Pin the arm as its own
        // switch target (the fall-through from DELETE_CELL does not count as
        // hitting it for coverage purposes).
        CellID id = CellTestFixtures.id(1);
        conn.handleMessage(load(id, CacheableCell.class.getName(), null));
        assertThat(cache.getCell(id)).isNotNull();

        conn.handleMessage(new CellHierarchyMessage(
                CellHierarchyMessage.ActionType.CHANGE_PARENT, null, null, id, null,
                CellTestFixtures.translation(0f, 0f, 0f), null, null));

        assertThat(cache.getCell(id)).isNotNull();
    }

    @Test
    @Timeout(5)
    void connectRegistersTheViewOverTheLoopback() throws Exception {
        // the two-arg form is the one CellClientSession.login used, and the
        // one this class overrides to register the view right after linking
        conn.connect(session, new java.util.Properties());
        assertThat(conn.getViewCellID()).isEqualTo(new CellID(4242L));
        assertThat(view.initialized).hasSize(1);
        assertThat(view.initialized.get(0).getViewCellID())
                .isEqualTo(new CellID(4242L));
        // the SET_VIEW request went out on the session transport
        assertThat(session.outbound).hasSize(1);
    }

    @Test
    @Timeout(5)
    void interruptedRegistrationDegradesToANullViewCell() throws Exception {
        // registerView's InterruptedException catch is a real 2006 branch: on a
        // session that never answers, the pending waitForResponse() meets the
        // interrupt flag, the catch logs SEVERE and returns null, and the
        // unguarded viewCellID read then throws -- kept verbatim
        PipelineSession silent = new PipelineSession();
        view = new RecordingView();
        CellCacheConnection fresh = new CellCacheConnection(view);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> fresh.connect(silent, new java.util.Properties()))
                    .isInstanceOf(NullPointerException.class);
            assertThat(view.initialized).containsExactly((ViewCreateResponseMessage) null);
            assertThat(fresh.getViewCellID()).isNull();
        } finally {
            Thread.interrupted(); // clear the flag for the suites after this one
        }
    }

    @Test
    void disconnectedBlindsTheConnection() {
        CellID id = CellTestFixtures.id(1);
        conn.handleMessage(load(id, CacheableCell.class.getName(), null));
        conn.disconnected();
        // listener list cleared: a second load message routes to nobody and
        // the duplicate registration inside the cache is untouched
        CellID id2 = CellTestFixtures.id(2);
        conn.handleMessage(load(id2, CacheableCell.class.getName(), null));
        assertThat(cache.getCell(id2)).isNull();
    }

    @Test
    void connectionTypeIsTheCacheChannel() {
        assertThat(conn.getConnectionType().toString())
                .isEqualTo(org.jdesktop.wonderland.common.cell
                        .CellCacheConnectionType.CLIENT_TYPE.toString());
    }
}
