/*
 * wonderland-server (M3): the session oracle the retired Sun Game Server provided
 * and the login machine the client interface dropped -- rebuilt as a plain Spring
 * service. It mints the BigInteger session ids and the per-connection short
 * client ids the wire stamps, owns the live session table, and looks a session up
 * for the router's broadcasts.
 *
 * Id policy mirrors the 2006 server: session ids start at 1 and rise (never 0, so
 * a null/zero sender on the client is unambiguously "not me"); client ids are a
 * separate short counter starting at 1 and are unique across the whole server,
 * which is what the wire's short clientID field assumes. Both counters are
 * AtomicLong-backed so create/attach are safe under concurrent logins.
 */
package org.jdesktop.wonderland.server.comms;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.springframework.stereotype.Service;

@Service
public class SessionRegistry {

    private final Map<BigInteger, ServerSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionSeq = new AtomicLong(0);
    private final AtomicLong clientSeq = new AtomicLong(0);

    /**
     * Register a brand-new session and hand back its server view. The caller
     * (the transport) supplies the identity and the sink that reaches the client.
     */
    public ServerSession create(WonderlandIdentity user, FrameSink sink) {
        BigInteger id = BigInteger.valueOf(sessionSeq.incrementAndGet());
        ServerSession session = new ServerSession(id, user, sink);
        sessions.put(id, session);
        return session;
    }

    /**
     * Attach one of a session's connections, minting and recording the short
     * client id the wire stamps on every frame that connection sends/receives.
     */
    public short attach(ServerSession session, ConnectionType type) {
        short clientID = (short) clientSeq.incrementAndGet();
        session.attach(type, clientID);
        return clientID;
    }

    /** Detach one connection; the session stays until close() when fully gone. */
    public void detach(ServerSession session, ConnectionType type) {
        session.detach(type);
    }

    public ServerSession get(BigInteger id) {
        return sessions.get(id);
    }

    public Collection<ServerSession> all() {
        return sessions.values();
    }

    /** Drop a session entirely (the transport saw its last connection close). */
    public void close(BigInteger id) {
        sessions.remove(id);
    }

    public int count() {
        return sessions.size();
    }
}
