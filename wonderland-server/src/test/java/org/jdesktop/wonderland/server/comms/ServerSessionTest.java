/*
 * wonderland-server M3 unit tests for ServerSession itself (the registry's view
 * was covered in SessionRegistryTest): the client-id slot defaults to 0 before
 * any attach -- the packer stamps that on frames for a type nobody registered,
 * the unattached/attached readback the router's broadcasts filter on, and the
 * attachedTypes view the transport inspects. attach/detach are package-private
 * here on purpose (only the registry mints ids), which same-package placement
 * lets this drive directly.
 */
package org.jdesktop.wonderland.server.comms;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ServerSessionTest {

    private static final WonderlandIdentity USER =
            new WonderlandIdentity("bob", "Bob B", "bob@example.org");

    @Test
    void clientIdDefaultsToZeroUntilAttached() {
        ServerSession session = new ServerSession(BigInteger.ONE, USER,
                (s, type, frame) -> {
                });

        assertThat(session.clientID(CellCacheConnectionType.CLIENT_TYPE)).isEqualTo((short) 0);
        assertThat(session.isAttached(CellCacheConnectionType.CLIENT_TYPE)).isFalse();
        assertThat(session.attachedTypes()).isEmpty();

        session.attach(CellCacheConnectionType.CLIENT_TYPE, (short) 7);

        assertThat(session.clientID(CellCacheConnectionType.CLIENT_TYPE)).isEqualTo((short) 7);
        assertThat(session.isAttached(CellCacheConnectionType.CLIENT_TYPE)).isTrue();
        assertThat(session.attachedTypes())
                .containsExactly(CellCacheConnectionType.CLIENT_TYPE);

        session.detach(CellCacheConnectionType.CLIENT_TYPE);
        assertThat(session.clientID(CellCacheConnectionType.CLIENT_TYPE)).isEqualTo((short) 0);
    }

    @Test
    void oneSessionCarriesEveryConnectionType() {
        ServerSession session = new ServerSession(BigInteger.TEN, USER,
                (s, type, frame) -> {
                });
        session.attach(CellCacheConnectionType.CLIENT_TYPE, (short) 1);
        session.attach(CellChannelConnectionType.CLIENT_TYPE, (short) 2);

        assertThat(session.clientID(CellChannelConnectionType.CLIENT_TYPE)).isEqualTo((short) 2);
        assertThat(session.attachedTypes()).containsExactlyInAnyOrder(
                CellCacheConnectionType.CLIENT_TYPE, CellChannelConnectionType.CLIENT_TYPE);
    }

    @Test
    void deliverPushesThroughTheSinkWithTheType() {
        ByteBuffer frame = ByteBuffer.allocate(8);
        java.util.concurrent.atomic.AtomicReference<Object[]> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        ServerSession session = new ServerSession(BigInteger.ONE, USER,
                (s, type, packed) -> seen.set(new Object[]{s, type, packed}));

        session.deliver(CellChannelConnectionType.CLIENT_TYPE, frame);

        assertThat(seen.get()[0]).isSameAs(session);
        assertThat(seen.get()[1]).isEqualTo(CellChannelConnectionType.CLIENT_TYPE);
        assertThat(seen.get()[2]).isSameAs(frame);
    }
}
