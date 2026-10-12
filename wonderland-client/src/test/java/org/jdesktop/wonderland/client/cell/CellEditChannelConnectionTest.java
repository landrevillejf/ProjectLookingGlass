/*
 * M2e: the edit channel -- typed CellEditMessage front, the real MessageSink
 * the M2d cache seam resolves to, and the deliberately empty receive side
 * (verbatim 2006: edits are client-authored, replies route through the
 * response listeners). These paths were not on the session e2e's route, so
 * the suite exercises them directly on the loopback.
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.TestResponse;
import org.jdesktop.wonderland.client.comms.WaitResponseListener;
import org.jdesktop.wonderland.common.cell.CellEditConnectionType;
import org.jdesktop.wonderland.common.cell.messages.CellEditMessage;
import org.jdesktop.wonderland.common.cell.messages.CellReparentMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

class CellEditChannelConnectionTest {

    private CellEditChannelConnection conn;
    private PipelineSession session;

    @BeforeEach
    void setUp() {
        conn = new CellEditChannelConnection();
        session = new PipelineSession();
        session.responder = (c, msg) -> c.messageReceived(
                new TestResponse(msg.getMessageID()));
    }

    private static CellReparentMessage reparent() {
        return new CellReparentMessage(CellTestFixtures.id(1), CellTestFixtures.id(2), null);
    }

    @Test
    void connectionTypeIsTheEditChannel() {
        assertThat(conn.getConnectionType())
                .isEqualTo(CellEditConnectionType.CLIENT_TYPE);
    }

    @Test
    @Timeout(5)
    void typedAndSinkSendsAllRideTheSession() throws Exception {
        conn.connect(session);
        CellEditMessage a = reparent();
        conn.send(a);           // typed overload
        Message b = reparent(); // the M2d MessageSink side of the seam
        conn.send(b);
        assertThat(session.outbound).containsExactly(a, b);
    }

    @Test
    @Timeout(5)
    void requestReplyPathsWorkTyped() throws Exception {
        conn.connect(session);
        CellEditMessage ask = reparent();
        assertThat(conn.sendAndWait(ask).getMessageID()).isEqualTo(ask.getMessageID());

        CellEditMessage ask2 = reparent();
        WaitResponseListener listener = new WaitResponseListener();
        conn.send(ask2, listener);
        assertThat(listener.waitForResponse().getMessageID()).isEqualTo(ask2.getMessageID());
    }

    @Test
    void handleMessageIsEmptyPerTheOriginal() {
        conn.handleMessage(reparent()); // the edit channel does not receive
    }
}
