/*
 * M2e: the real channel a loaded cell gets -- the class the M2c MessageChannel
 * seam was cut for. Pins the receiver-map contract (duplicate class with a
 * DIFFERENT receiver throws, same receiver is an idempotent replace, removal
 * reopens the slot), the wrong-CellID SEVERE no-dispatch and the
 * missing-receiver warning branch, and the send paths that fill in the cell's
 * own id before riding the session-wide connection.
 */
package org.jdesktop.wonderland.client.cell;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.CacheableCell;
import org.jdesktop.wonderland.client.comms.ClientConnection;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.TestResponse;
import org.jdesktop.wonderland.client.comms.WaitResponseListener;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelComponentTest {

    private CellCacheBasicImpl cache;
    private CellChannelConnection conn;
    private Cell cell;
    private ChannelComponent component;
    private PipelineSession session;

    static class ProbeMessage extends CellMessage {
        ProbeMessage() {
            super();
        }

        ProbeMessage(CellID cellID) {
            super(cellID);
        }
    }

    static class OtherMessage extends CellMessage {
        OtherMessage(CellID cellID) {
            super(cellID);
        }
    }

    /** Collects dispatches so "no dispatch" branches are observable. */
    static class HitReceiver implements MessageChannel.MessageReceiver {
        final List<CellMessage> hits = new ArrayList<>();

        @Override
        public void messageReceived(CellMessage message) {
            hits.add(message);
        }
    }

    @BeforeEach
    void setUp() {
        cache = new CellCacheBasicImpl();
        conn = new CellChannelConnection();
        cache.setCellChannelConnection(conn);
        cell = new CacheableCell(CellTestFixtures.id(1), cache);
        component = new ChannelComponent(cell);
        session = new PipelineSession();
    }

    @Test
    void statusMirrorsTheConnection() throws Exception {
        assertThat(component.getStatus()).isSameAs(ClientConnection.Status.DISCONNECTED);
        conn.connect(session);
        assertThat(component.getStatus()).isSameAs(ClientConnection.Status.CONNECTED);
    }

    @Test
    void duplicateClassWithDifferentReceiverThrowsAndSameReceiverIsIdempotent() {
        MessageChannel.MessageReceiver a = new HitReceiver();
        component.addMessageReceiver(ProbeMessage.class, a);
        component.addMessageReceiver(ProbeMessage.class, a); // same receiver: ok
        assertThatThrownBy(() -> component.addMessageReceiver(ProbeMessage.class, new HitReceiver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate Message class");
        // removal reopens the slot for a fresh receiver
        component.removeMessageReceiver(ProbeMessage.class);
        MessageChannel.MessageReceiver b = new HitReceiver();
        component.addMessageReceiver(ProbeMessage.class, b);
        assertThat(component.getMessageReceiver(ProbeMessage.class)).isSameAs(b);
    }

    @Test
    void inboundDispatchesOnExactClassForTheOwnCellId() {
        HitReceiver probe = new HitReceiver();
        HitReceiver other = new HitReceiver();
        component.addMessageReceiver(ProbeMessage.class, probe);
        component.addMessageReceiver(OtherMessage.class, other);

        ProbeMessage m = new ProbeMessage(cell.getCellID());
        // under FINEST the received-trace line inside messageReceived runs too
        CellTestFixtures.capture(ChannelComponent.class, Level.FINEST,
                () -> component.messageReceived(m));
        assertThat(probe.hits).containsExactly(m);
        assertThat(other.hits).isEmpty();
    }

    @Test
    void wrongCellIdIsNotDispatched() {
        HitReceiver receiver = new HitReceiver();
        component.addMessageReceiver(ProbeMessage.class, receiver);
        component.messageReceived(new ProbeMessage(CellTestFixtures.id(2)));
        assertThat(receiver.hits).isEmpty();
    }

    @Test
    void unregisteredClassWarnsAndDrops() {
        // no receiver for OtherMessage: the documented warning branch, no throw
        component.messageReceived(new OtherMessage(cell.getCellID()));
    }

    @Test
    @Timeout(5)
    void sendFillsOwnCellIdAndRoutesThroughTheConnection() throws Exception {
        conn.connect(session);
        ProbeMessage m = new ProbeMessage(); // no cell id yet
        component.send(m);
        assertThat(m.getCellID()).isEqualTo(cell.getCellID());
        assertThat(session.outbound).containsExactly(m);
    }

    @Test
    @Timeout(5)
    void sendKeepsAnExplicitCellId() throws Exception {
        conn.connect(session);
        CellID explicit = CellTestFixtures.id(5);
        ProbeMessage m = new ProbeMessage(explicit);
        component.send(m);
        assertThat(m.getCellID()).isEqualTo(explicit);
    }

    @Test
    void sendBeforeTheConnectionIsUpThrowsAfterFillingTheId() {
        ProbeMessage m = new ProbeMessage();
        assertThatThrownBy(() -> component.send(m))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Not connected");
        assertThat(m.getCellID()).isEqualTo(cell.getCellID());
    }

    @Test
    @Timeout(5)
    void sendAndWaitRoundTripsThroughTheLoopback() throws Exception {
        conn.connect(session);
        session.responder = (c, msg) -> c.messageReceived(
                new TestResponse(msg.getMessageID()));
        // a bare message (id filled in) and an explicit-id one cover both
        // arms of the cellID guard on every send path
        ProbeMessage ask = new ProbeMessage();
        assertThat(component.sendAndWait(ask).getMessageID()).isEqualTo(ask.getMessageID());
        ProbeMessage explicit = new ProbeMessage(cell.getCellID());
        assertThat(component.sendAndWait(explicit).getMessageID())
                .isEqualTo(explicit.getMessageID());
    }

    @Test
    @Timeout(5)
    void sendWithListenerConsumesTheReply() throws Exception {
        conn.connect(session);
        session.responder = (c, msg) -> c.messageReceived(
                new TestResponse(msg.getMessageID()));
        ProbeMessage ask = new ProbeMessage(cell.getCellID()); // explicit id
        WaitResponseListener listener = new WaitResponseListener();
        component.send(ask, listener);
        assertThat(listener.waitForResponse().getMessageID()).isEqualTo(ask.getMessageID());
    }
}
