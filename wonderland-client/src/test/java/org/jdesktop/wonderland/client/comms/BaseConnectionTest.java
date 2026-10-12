/*
 * M2e: the BaseConnection skeleton, driven against the loopback session.
 * Pins the status pair, the "Not connected" guard on every send path, the
 * request-reply routing (a ResponseMessage with a waiting listener is
 * consumed and never reaches handleMessage; an unknown one falls through),
 * the sendAndWait round trip on the same thread (the responder replies
 * synchronously, so the latch is already open), connect/disconnect via the
 * session, and the session-status observer pair.
 */
package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.TestConnection;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.TestResponse;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.ResponseMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaseConnectionTest {

    private PipelineSession session;
    private TestConnection connection;

    /** A second concrete connection: the typed-lookup rejection target. */
    static class WrongConnection extends TestConnection {
    }

    @BeforeEach
    void setUp() {
        session = new PipelineSession();
        connection = new TestConnection();
    }

    @Test
    void freshConnectionIsDisconnected() {
        assertThat(connection.getStatus())
                .isSameAs(ClientConnection.Status.DISCONNECTED);
        assertThat(connection.getSession()).isNull();
    }

    @Test
    void sendBeforeConnectThrows() {
        assertThatThrownBy(() -> connection.sendOut(new Message() {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Not connected");
    }

    @Test
    void sendWithListenerBeforeConnectThrows() {
        assertThatThrownBy(() -> connection.sendOut(new Message() {
        }, new WaitResponseListener()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Timeout(5)
    void connectViaSessionRegistersAndFlipsStatus() throws Exception {
        connection.connect(session);
        assertThat(connection.getStatus()).isSameAs(ClientConnection.Status.CONNECTED);
        assertThat(connection.getSession()).isSameAs(session);
        assertThat(session.getConnection(ConnectionTestFixtures.TEST_TYPE))
                .isSameAs(connection);
        assertThat(session.getStatus())
                .isSameAs(PipelineSession.Status.CONNECTED);
    }

    @Test
    @Timeout(5)
    void connectWithPropsViaSession() throws Exception {
        connection.connect(session, new Properties());
        assertThat(connection.getStatus()).isSameAs(ClientConnection.Status.CONNECTED);
    }

    @Test
    @Timeout(5)
    void disconnectRoutesBackThroughSession() throws Exception {
        connection.connect(session);
        connection.disconnect();
        assertThat(connection.getStatus())
                .isSameAs(ClientConnection.Status.DISCONNECTED);
        assertThat(session.getConnection(ConnectionTestFixtures.TEST_TYPE)).isNull();
        assertThat(session.getStatus())
                .isSameAs(PipelineSession.Status.DISCONNECTED);
    }

    @Test
    @Timeout(5)
    void plainSendReachesSessionOutbound() throws Exception {
        connection.connect(session);
        Message m = new Message() {
        };
        connection.sendOut(m);
        assertThat(session.outbound).containsExactly(m);
        assertThat(session.inboundVia).containsExactly(connection);
    }

    @Test
    @Timeout(5)
    void sendWithListenerRoutesReplyOnceAndSkipsHandleMessage() throws Exception {
        connection.connect(session);
        WaitResponseListener listener = new WaitResponseListener();
        Message request = new Message() {
        };
        connection.sendOut(request, listener);

        // a reply for the waiting listener is consumed...
        Message reply = new TestResponse(request.getMessageID());
        connection.messageReceived(reply);
        assertThat(listener.waitForResponse()).isSameAs(reply);
        assertThat(connection.handled).isEmpty();

        // ...the same reply is NOT routed twice: a second one falls through
        connection.messageReceived(reply);
        assertThat(connection.handled).containsExactly(reply);
    }

    @Test
    @Timeout(5)
    void nonResponseMessageAlwaysHitsHandleMessage() throws Exception {
        connection.connect(session);
        Message m = new Message() {
        };
        connection.messageReceived(m);
        assertThat(connection.handled).containsExactly(m);
    }

    @Test
    @Timeout(5)
    void sendAndWaitUnblocksOnSynchronousResponder() throws Exception {
        Message request = new Message() {
        };
        // the responder replies inline, so the latch is already open when
        // waitForResponse runs -- no thread needed
        session.responder = (conn, msg) -> conn.messageReceived(
                new TestResponse(msg.getMessageID()));
        connection.connect(session);

        ResponseMessage reply = connection.sendWait(request);
        assertThat(reply.getMessageID()).isEqualTo(request.getMessageID());
        assertThat(connection.handled).isEmpty();
    }

    @Test
    @Timeout(5)
    void notifyUnknownReturnsFalse() throws Exception {
        connection.connect(session);
        assertThat(connection.notifyUnknown()).isFalse();
    }

    @Test
    @Timeout(5)
    void sessionTypedLookupCastsAndRejects() throws Exception {
        connection.connect(session);
        assertThat(session.getConnection(
                ConnectionTestFixtures.TEST_TYPE, TestConnection.class))
                        .isSameAs(connection);
        assertThatThrownBy(() -> session.getConnection(
                ConnectionTestFixtures.TEST_TYPE, WrongConnection.class))
                        .isInstanceOf(ClassCastException.class);
    }

    @Test
    @Timeout(5)
    void sessionStatusListenersFireAndUnregister() throws Exception {
        java.util.List<PipelineSession.Status> seen = new java.util.ArrayList<>();
        SessionStatusListener l = (s, st) -> seen.add(st);
        session.addSessionStatusListener(l);
        assertThat(session.listenerCount()).isEqualTo(1);
        connection.connect(session);
        assertThat(seen).containsExactly(PipelineSession.Status.CONNECTED);
        session.removeSessionStatusListener(l);
        assertThat(session.listenerCount()).isZero();
        connection.disconnect();
        assertThat(seen).hasSize(1);
    }

    @Test
    void toStringIsConnectionType() {
        assertThat(connection.toString())
                .isEqualTo(ConnectionTestFixtures.TEST_TYPE.toString());
    }
}
