/*
 * wonderland-server M3 unit tests: the session oracle that replaces the retired
 * Sun Game Server's login machine. Pins the id policy the wire and the client's
 * echo-suppression depend on: session ids start at 1 and rise (never 0, so a
 * zero/null sender is unambiguously "not me"), client ids are a separate counter
 * unique across the whole server, and close() drops a session from the table the
 * router broadcasts over.
 */
package org.jdesktop.wonderland.server.comms;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    private static final WonderlandIdentity USER =
            new WonderlandIdentity("alice", "Alice A", "alice@example.org");

    private SessionRegistry registry;
    private List<Object[]> sinkCalls;

    @BeforeEach
    void setUp() {
        sinkCalls = new ArrayList<>();
        registry = new SessionRegistry();
    }

    private FrameSink recordingSink() {
        return (session, type, frame) -> sinkCalls.add(new Object[]{session, type, frame});
    }

    @Test
    void sessionIdsStartAtOneAndRise() {
        ServerSession first = registry.create(USER, recordingSink());
        ServerSession second = registry.create(USER, recordingSink());

        assertThat(first.getID()).isEqualTo(BigInteger.ONE);
        assertThat(second.getID()).isEqualTo(BigInteger.TWO);
        assertThat(first.getUserID()).isSameAs(USER);
    }

    @Test
    void clientIdsAreASeparateGlobalCounter() {
        ServerSession a = registry.create(USER, recordingSink());
        ServerSession b = registry.create(USER, recordingSink());

        short aCache = registry.attach(a, CellCacheConnectionType.CLIENT_TYPE);
        short aChannel = registry.attach(a, CellChannelConnectionType.CLIENT_TYPE);
        short bCache = registry.attach(b, CellCacheConnectionType.CLIENT_TYPE);

        // unique across sessions, not per-session: the wire's short clientID field
        assertThat(aCache).isNotEqualTo(aChannel);
        assertThat(bCache).isNotEqualTo(aCache).isNotEqualTo(aChannel);
        assertThat(a.clientID(CellCacheConnectionType.CLIENT_TYPE)).isEqualTo(aCache);
    }

    @Test
    void lookupAndCountReflectTheLiveTable() {
        ServerSession a = registry.create(USER, recordingSink());
        ServerSession b = registry.create(USER, recordingSink());

        assertThat(registry.get(a.getID())).isSameAs(a);
        assertThat(registry.all()).containsExactlyInAnyOrder(a, b);
        assertThat(registry.count()).isEqualTo(2);

        registry.close(a.getID());
        assertThat(registry.get(a.getID())).isNull();
        assertThat(registry.count()).isEqualTo(1);
        assertThat(registry.all()).containsExactly(b);
    }

    @Test
    void detachKeepsTheSessionButClearsTheClientSlot() {
        ServerSession session = registry.create(USER, recordingSink());
        registry.attach(session, CellCacheConnectionType.CLIENT_TYPE);

        registry.detach(session, CellCacheConnectionType.CLIENT_TYPE);

        assertThat(session.isAttached(CellCacheConnectionType.CLIENT_TYPE)).isFalse();
        assertThat(registry.get(session.getID())).isSameAs(session);
    }

    @Test
    void serverSessionDeliversThroughItsSink() {
        ServerSession session = registry.create(USER, recordingSink());
        registry.attach(session, CellCacheConnectionType.CLIENT_TYPE);
        ByteBuffer frame = ByteBuffer.allocate(4);

        session.deliver(CellCacheConnectionType.CLIENT_TYPE, frame);

        assertThat(sinkCalls).hasSize(1);
        assertThat(sinkCalls.get(0)[0]).isSameAs(session);
        assertThat(sinkCalls.get(0)[1]).isEqualTo(CellCacheConnectionType.CLIENT_TYPE);
        assertThat(sinkCalls.get(0)[2]).isSameAs(frame);
    }
}
