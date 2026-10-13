/*
 * M2e: the inbound CellMessage demultiplexer. Pins the load-race queueing
 * (messages before the cell has a receiver are buffered, then flushed the
 * moment ACTIVE installs one), the documented Noop receiver for an ACTIVE
 * cell with no channel and for a failed load, the null-cache SEVERE branch
 * (the ClientContext seam substitution), the scheduled drop of the delivery
 * entry after unload, and the typed send paths on the loopback session.
 */
package org.jdesktop.wonderland.client.cell;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.CacheableCell;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.RecordingChannel;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.TestResponse;
import org.jdesktop.wonderland.client.comms.WaitResponseListener;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

class CellChannelConnectionTest {

    private CellCacheBasicImpl cache;
    private CellChannelConnection conn;
    private PipelineSession session;
    private RecordingChannel channel;

    /** A concrete CellMessage; the exact-class receiver map is keyed on it. */
    static class ProbeMessage extends CellMessage {
        ProbeMessage() {
            super();
        }

        ProbeMessage(CellID cellID) {
            super(cellID);
        }
    }

    @BeforeEach
    void setUp() {
        cache = new CellCacheBasicImpl();
        conn = new CellChannelConnection();
        cache.setCellChannelConnection(conn);
        conn.setCellCache(cache);
        session = new PipelineSession();
        channel = new RecordingChannel();
    }

    /** Installs the RecordingChannel on load, the way the session's hook does. */
    private void installChannelOnLoad() {
        cache.addCellCacheListener(new CellCache.CellCacheListener() {
            @Override
            public void cellLoaded(CellID cellId, Cell cell) {
                cell.setMessageChannel(channel);
            }

            @Override
            public void cellLoadFailed(CellID cellId, String className,
                    CellID parentCellID, Throwable cause) {
            }

            @Override
            public void cellUnloaded(CellID cellId, Cell cell) {
            }
        });
    }

    private Cell load(CellID id) {
        return cache.loadCell(id, CacheableCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f),
                CellTestFixtures.namedState("c-" + id), "c-" + id);
    }

    @Test
    void inboundBeforeTheChannelExistsIsQueuedThenFlushedOnActive() {
        installChannelOnLoad();
        CellID id = CellTestFixtures.id(1);
        Cell cell = load(id); // no view yet: the walk has not reached ACTIVE

        ProbeMessage first = new ProbeMessage(id);
        ProbeMessage second = new ProbeMessage(id);
        // run under FINE so the delay-queue trace lines are exercised too
        List<LogRecord> queued = CellTestFixtures.capture(
                CellChannelConnection.class, Level.FINE, () -> {
                    conn.handleMessage(first);  // creates the queue
                    conn.handleMessage(second); // rides the existing queue
                });
        assertThat(queued).isNotEmpty();
        assertThat(cell.getStatus()).isNotEqualTo(CellStatus.ACTIVE);
        assertThat(channel.received).isEmpty(); // buffered, nothing dispatched

        // walk the tree through ACTIVE under FINE -- the flush trace runs too
        CellTestFixtures.capture(CellChannelConnection.class, Level.FINE,
                () -> cache.setViewCell(CellTestFixtures.viewCell(cache)));
        assertThat(cell.getStatus()).isEqualTo(CellStatus.VISIBLE);
        assertThat(channel.received).containsExactly(first, second);
    }

    @Test
    void activeCellWithoutAChannelGetsTheNoopReceiver() {
        cache.setViewCell(CellTestFixtures.viewCell(cache)); // walk happens inside the load, channel still null
        CellID id = CellTestFixtures.id(1);
        Cell cell = load(id);
        assertThat(cell.getChannel()).isNull();
        assertThat(conn.getCellMessageDelivery(id).getMessageReceiver())
                .isNotNull()
                .matches(r -> r.getClass().getSimpleName().equals("NoopMessageReceiver"));

        // delivery lands in the void -- no queueing, no throw, receiver intact
        conn.handleMessage(new ProbeMessage(id));
        assertThat(conn.getCellMessageDelivery(id).getMessageReceiver())
                .matches(r -> r.getClass().getSimpleName().equals("NoopMessageReceiver"));
    }

    @Test
    void loadFailureBlindsTheDelivery() {
        CellID id = CellTestFixtures.id(1);
        conn.cellLoadFailed(id, "no.SuchCell", null, new RuntimeException("boom"));
        assertThat(conn.getCellMessageDelivery(id).getMessageReceiver())
                .matches(r -> r.getClass().getSimpleName().equals("NoopMessageReceiver"));
        // under FINE the Noop receiver's own trace line runs, and still swallows
        List<LogRecord> ignored = CellTestFixtures.capture(
                CellChannelConnection.class, Level.FINE,
                () -> conn.handleMessage(new ProbeMessage(id)));
        assertThat(ignored).isNotEmpty();
    }

    @Test
    void nullCacheIsASevereLogNotADelivery() {
        CellChannelConnection bare = new CellChannelConnection();
        CellMessage m = new ProbeMessage(CellTestFixtures.id(1));
        List<LogRecord> severe = CellTestFixtures.capture(CellChannelConnection.class, Level.SEVERE,
                () -> bare.handleMessage(m));
        assertThat(severe).hasSize(1);
        assertThat(severe.get(0).getMessage()).contains("CellCache is null");
    }

    @Test
    void unknownCellUnloadIsHarmless() {
        // the cellUnloaded listener arm guards on a missing delivery entry
        conn.cellUnloaded(CellTestFixtures.id(42), null);
    }

    @Test
    @Timeout(10)
    void unloadDropsTheDeliveryEntryAfterTheCleanupDelay() throws Exception {
        installChannelOnLoad();
        cache.setViewCell(CellTestFixtures.viewCell(cache)); // the walk to DISK on unload fires the rung
        CellID id = CellTestFixtures.id(1);
        load(id);
        conn.handleMessage(new ProbeMessage(id));
        CellChannelConnection.CellMessageDelivery cmd = conn.getCellMessageDelivery(id);

        conn.cleanupDelayMillis = 10L;
        cache.unloadCell(id);
        // swapped to Noop immediately ...
        assertThat(cmd.getMessageReceiver())
                .matches(r -> r.getClass().getSimpleName().equals("NoopMessageReceiver"));
        // ... and the map entry itself dies a beat later, off the executor
        long deadline = System.currentTimeMillis() + 5000L;
        while (conn.getCellMessageDelivery(id) == cmd && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(conn.getCellMessageDelivery(id)).isNotSameAs(cmd);
    }

    @Test
    @Timeout(10)
    void reloadBeforeTheCleanupKeepsTheDeliveryEntry() throws Exception {
        installChannelOnLoad();
        cache.setViewCell(CellTestFixtures.viewCell(cache));
        CellID id = CellTestFixtures.id(1);
        load(id);
        CellChannelConnection.CellMessageDelivery cmd = conn.getCellMessageDelivery(id);

        conn.cleanupDelayMillis = 250L;
        cache.unloadCell(id);   // schedules the drop, swaps to Noop ...
        load(id);               // ... and a fast reload re-installs a real receiver
        Thread.sleep(400L);     // let the scheduled cleanup run and back off
        // the guard saw a receiver that is not its Noop and kept the entry
        assertThat(conn.getCellMessageDelivery(id)).isSameAs(cmd);
        assertThat(cmd.getMessageReceiver())
                .matches(r -> r.getClass().getSimpleName().equals("ChannelMessageReceiver"));
    }

    @Test
    @Timeout(5)
    void typedSendPathsRideTheSessionTransport() throws Exception {
        conn.connect(session);
        CellID id = CellTestFixtures.id(1);
        ProbeMessage m = new ProbeMessage(id);
        conn.send(m);
        assertThat(session.outbound).containsExactly(m);

        session.responder = (c, msg) -> c.messageReceived(
                new TestResponse(msg.getMessageID()));
        WaitResponseListener listener = new WaitResponseListener();
        ProbeMessage ask = new ProbeMessage(id);
        conn.send(ask, listener);
        assertThat(listener.waitForResponse().getMessageID()).isEqualTo(ask.getMessageID());

        ProbeMessage wait = new ProbeMessage(id);
        assertThat(conn.sendAndWait(wait).getMessageID()).isEqualTo(wait.getMessageID());
    }

    @Test
    void connectionTypeIsTheCellChannel() {
        ConnectionType type = conn.getConnectionType();
        assertThat(type).isEqualTo(
                org.jdesktop.wonderland.common.cell.CellChannelConnectionType.CLIENT_TYPE);
    }
}
