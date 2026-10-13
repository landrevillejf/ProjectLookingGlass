/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: headless tests for the session status feed.
 *
 * The feed is the seam between the transport (which fires on its own thread) and
 * the HUD / login panel (which must not import the transport). A fake
 * WonderlandSession stands in for the connection: the port has no production
 * client transport yet -- the login handshake was rebuilt server-side in M3 -- so
 * the feed is exactly the layer that lets the desktop's HUD be finished and
 * tested before that transport lands, and be driven by the M3a loopback harness.
 */
package org.jdesktop.wonderland.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import org.jdesktop.wonderland.client.comms.ClientConnection;
import org.jdesktop.wonderland.client.comms.ConnectionFailureException;
import org.jdesktop.wonderland.client.comms.SessionStatusListener;
import org.jdesktop.wonderland.client.comms.WonderlandServerInfo;
import org.jdesktop.wonderland.client.comms.WonderlandSession;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link SessionFeed}: the attach / detach contract, the
 * duplicate-status rule, and the guarantee that one broken viewer cannot stop
 * the others.
 */
class SessionFeedTest {

    /**
     * The minimum a feed can be pointed at: the four members the feed calls
     * (status, listener add / remove) plus the no-op bodies the rest of the
     * interface needs. The SGS login members are already dropped (M2b), so this
     * fake has no handshake to imitate.
     */
    private static final class FakeSession implements WonderlandSession {

        private WonderlandSession.Status status = WonderlandSession.Status.DISCONNECTED;
        /** When true the session reports no status at all, as a stub transport would. */
        private boolean statusless;
        private final List<SessionStatusListener> listeners = new ArrayList<>();
        private int added;
        private int removed;

        @Override
        public WonderlandServerInfo getServerInfo() {
            return new WonderlandServerInfo("localhost", 1);
        }

        @Override
        public Status getStatus() {
            return statusless ? null : status;
        }

        @Override
        public BigInteger getID() {
            return BigInteger.ONE;
        }

        @Override
        public WonderlandIdentity getUserID() {
            return null;
        }

        @Override
        public void connect(ClientConnection connection) throws ConnectionFailureException {
        }

        @Override
        public void connect(ClientConnection connection, Properties properties)
                throws ConnectionFailureException {
        }

        @Override
        public void disconnect(ClientConnection connection) {
        }

        @Override
        public ClientConnection getConnection(ConnectionType connectionType) {
            return null;
        }

        @Override
        public <T extends ClientConnection> T getConnection(ConnectionType connectionType,
                Class<T> clazz) {
            return null;
        }

        @Override
        public Collection<ClientConnection> getConnections() {
            return List.of();
        }

        @Override
        public void send(ClientConnection connection, Message message) {
        }

        @Override
        public void addSessionStatusListener(SessionStatusListener listener) {
            added++;
            listeners.add(listener);
        }

        @Override
        public void removeSessionStatusListener(SessionStatusListener listener) {
            removed++;
            listeners.remove(listener);
        }

        /** Drives a transition exactly the way the transport callback would. */
        void fire(WonderlandSession.Status next) {
            this.status = next;
            for (SessionStatusListener listener : List.copyOf(listeners)) {
                listener.sessionStatusChanged(this, next);
            }
        }
    }

    private FakeSession session;
    private SessionFeed feed;
    private List<WonderlandSession.Status> seen;

    @BeforeEach
    void setUp() {
        session = new FakeSession();
        feed = new SessionFeed();
        seen = new ArrayList<>();
        feed.addStatusListener(seen::add);
    }

    @Test
    void aFreshFeedStartsDisconnected() {
        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.DISCONNECTED);
        assertThat(feed.getSession()).isNull();
    }

    @Test
    void attachingReadsTheCurrentStatusInsteadOfAssuming() {
        session.fire(WonderlandSession.Status.CONNECTED);

        feed.attach(session);

        // A HUD opened after login must show the truth; the next transition of a
        // stable connection may never come.
        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.CONNECTED);
        assertThat(seen).containsExactly(WonderlandSession.Status.CONNECTED);
        assertThat(feed.getSession()).isSameAs(session);
    }

    @Test
    void attachingRegistersExactlyOneListener() {
        feed.attach(session);
        assertThat(session.added).isEqualTo(1);
        assertThat(session.listeners).hasSize(1);
    }

    @Test
    void transitionsAreRepublishedInOrder() {
        feed.attach(session);
        seen.clear();

        session.fire(WonderlandSession.Status.CONNECTING);
        session.fire(WonderlandSession.Status.CONNECTED);

        assertThat(seen).containsExactly(
                WonderlandSession.Status.CONNECTING,
                WonderlandSession.Status.CONNECTED);
        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.CONNECTED);
    }

    @Test
    void aRepeatedStatusIsNotRepublished() {
        feed.attach(session);
        session.fire(WonderlandSession.Status.CONNECTED);
        seen.clear();

        session.fire(WonderlandSession.Status.CONNECTED);

        assertThat(seen).isEmpty();
        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.CONNECTED);
    }

    @Test
    void aNullStatusReadsAsDisconnected() {
        feed.attach(session);
        session.fire(null);

        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.DISCONNECTED);
    }

    @Test
    void aSessionWithNoStatusIsTreatedAsDisconnected() {
        FakeSession statusless = new FakeSession();
        statusless.statusless = true;

        feed.attach(statusless);

        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.DISCONNECTED);
    }

    @Test
    void reAttachingDetachesThePreviousSession() {
        feed.attach(session);
        FakeSession next = new FakeSession();

        feed.attach(next);

        assertThat(session.removed).isEqualTo(1);
        assertThat(feed.getSession()).isSameAs(next);
    }

    @Test
    void detachingResetsToDisconnectedAndUnregisters() {
        feed.attach(session);
        session.fire(WonderlandSession.Status.CONNECTED);
        seen.clear();

        feed.detach();

        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.DISCONNECTED);
        assertThat(feed.getSession()).isNull();
        assertThat(session.removed).isEqualTo(1);
        assertThat(seen).containsExactly(WonderlandSession.Status.DISCONNECTED);
    }

    @Test
    void theCallbackCanBefedDirectlyWithoutASession() {
        feed.sessionStatusChanged(session, WonderlandSession.Status.CONNECTING);

        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.CONNECTING);
        assertThat(seen).containsExactly(WonderlandSession.Status.CONNECTING);
    }

    @Test
    void oneBrokenViewerCannotStopTheOthersOrEscapeToTheTransport() {
        feed.addStatusListener(status -> {
            throw new IllegalStateException("the HUD paint died");
        });
        feed.addStatusListener(status -> {
            throw new Error("the viewer took the thread down");
        });
        List<WonderlandSession.Status> alsoNotified = new ArrayList<>();
        feed.addStatusListener(alsoNotified::add);
        seen.clear();

        session.fire(WonderlandSession.Status.CONNECTED);
        feed.attach(session);

        assertThat(alsoNotified).containsExactly(WonderlandSession.Status.CONNECTED);
    }

    @Test
    void removingAListenerStopsTheNotifications() {
        List<WonderlandSession.Status> kept = new ArrayList<>();
        List<WonderlandSession.Status> dropped = new ArrayList<>();
        feed.addStatusListener(kept::add);
        java.util.function.Consumer<WonderlandSession.Status> dropMe = dropped::add;
        feed.addStatusListener(dropMe);
        feed.removeStatusListener(dropMe);
        feed.removeStatusListener(null);
        feed.addStatusListener(null);

        feed.sessionStatusChanged(session, WonderlandSession.Status.CONNECTING);

        assertThat(kept).containsExactly(WonderlandSession.Status.CONNECTING);
        assertThat(dropped).isEmpty();
    }

    @Test
    void detachingAnUnattachedFeedIsHarmless() {
        feed.detach();

        assertThat(feed.getStatus()).isEqualTo(WonderlandSession.Status.DISCONNECTED);
        assertThat(seen).isEmpty();
    }
}
