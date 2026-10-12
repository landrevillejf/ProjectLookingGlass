/*
 * M2e test fixtures: the in-JVM loopback session -- the stand-in for the M3
 * Spring endpoint that proves the whole BaseConnection contract (connect /
 * register / send / request-reply routing) without a socket. Public placement
 * in client.comms so both the comms tests and the cell-layer e2e test drive
 * the same machine; the responder hook is where a test plays "server" (reply
 * to a request, or echo nothing).
 */
package org.jdesktop.wonderland.client.comms;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

public class ConnectionTestFixtures {

    public static final ConnectionType TEST_TYPE = new ConnectionType("__Test");

    /** ResponseMessage is abstract; a bare one identifies by message id. */
    public static class TestResponse extends ResponseMessage {
        public TestResponse(MessageID messageID) {
            super(messageID);
        }
    }

    /** Minimal concrete connection: records what reached handleMessage. */
    public static class TestConnection extends BaseConnection {
        public final List<Message> handled = new ArrayList<>();

        @Override
        public ConnectionType getConnectionType() {
            return TEST_TYPE;
        }

        @Override
        public void handleMessage(Message message) {
            handled.add(message);
        }

        public void sendOut(Message message) {
            send(message);
        }

        public void sendOut(Message message, ResponseListener listener) {
            send(message, listener);
        }

        public ResponseMessage sendWait(Message message) throws InterruptedException {
            return sendAndWait(message);
        }

        public boolean notifyUnknown() {
            return notifyResponseListener(new TestResponse(new MessageID(999_999L)));
        }
    }

    /** The loopback WonderlandSession; public fields are the test's view. */
    public static class PipelineSession implements WonderlandSession {

        public final Map<ConnectionType, ClientConnection> connections =
                new LinkedHashMap<>();
        public final List<Message> outbound = new ArrayList<>();
        public final List<ClientConnection> inboundVia = new ArrayList<>();
        public BiConsumer<ClientConnection, Message> responder;

        private final WonderlandServerInfo serverInfo =
                new WonderlandServerInfo("loopback", 0);
        private final BigInteger id = BigInteger.ONE;
        private final WonderlandIdentity user =
                new WonderlandIdentity("test-user", "Test User", "test@example.invalid");
        private Status status = Status.DISCONNECTED;
        private final List<SessionStatusListener> listeners = new ArrayList<>();

        @Override
        public WonderlandServerInfo getServerInfo() {
            return serverInfo;
        }

        @Override
        public Status getStatus() {
            return status;
        }

        @Override
        public BigInteger getID() {
            return id;
        }

        @Override
        public WonderlandIdentity getUserID() {
            return user;
        }

        @Override
        public void connect(ClientConnection connection) throws ConnectionFailureException {
            connect(connection, new Properties());
        }

        @Override
        public void connect(ClientConnection connection, Properties properties)
                throws ConnectionFailureException {
            connections.put(connection.getConnectionType(), connection);
            connection.connected(this);
            if (status != WonderlandSession.Status.CONNECTED) {
                setStatus(WonderlandSession.Status.CONNECTED);
            }
        }

        @Override
        public void disconnect(ClientConnection connection) {
            connections.remove(connection.getConnectionType());
            connection.disconnected();
            if (connections.isEmpty()) {
                setStatus(WonderlandSession.Status.DISCONNECTED);
            }
        }

        @Override
        public ClientConnection getConnection(ConnectionType connectionType) {
            return connections.get(connectionType);
        }

        @Override
        public <T extends ClientConnection> T getConnection(ConnectionType connectionType,
                Class<T> clazz) {
            return clazz.cast(connections.get(connectionType));
        }

        @Override
        public Collection<ClientConnection> getConnections() {
            return connections.values();
        }

        @Override
        public void send(ClientConnection connection, Message message) {
            outbound.add(message);
            inboundVia.add(connection);
            if (responder != null) {
                responder.accept(connection, message);
            }
        }

        @Override
        public void addSessionStatusListener(SessionStatusListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeSessionStatusListener(SessionStatusListener listener) {
            listeners.remove(listener);
        }

        public int listenerCount() {
            return listeners.size();
        }

        private void setStatus(Status next) {
            status = next;
            for (SessionStatusListener l : new ArrayList<>(listeners)) {
                l.sessionStatusChanged(this, next);
            }
        }
    }
}
