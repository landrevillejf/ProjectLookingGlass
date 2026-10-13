/*
 * wonderland-server (M3): the server-side view of one connected client session
 * -- the object the retired Sun Game Server minted and the client's
 * WonderlandSession interface only saw the far end of. It carries the BigInteger
 * session id (the same type M1 round-trips in SessionInitializationMessage and
 * that MovableComponent reads as an echo's sender identity), the authenticated
 * WonderlandIdentity (the login surface SGS owned and the client interface
 * dropped -- here the server owns it), the per-connection short client ids the
 * wire stamps, and the transport sink that turns "deliver(frame)" into bytes on
 * a real pipe.
 *
 * A session is shared by its three cell connections (cache / channel / edit), so
 * clientIDs is keyed by ConnectionType, exactly the map the router packs against.
 * deliver(...) delegates to the sink; everything else is state the router reads.
 */
package org.jdesktop.wonderland.server.comms;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.comms.ConnectionType;

public final class ServerSession {

    private final BigInteger id;
    private final WonderlandIdentity user;
    private final FrameSink sink;
    private final Map<ConnectionType, Short> clientIDs = new ConcurrentHashMap<>();

    public ServerSession(BigInteger id, WonderlandIdentity user, FrameSink sink) {
        this.id = id;
        this.user = user;
        this.sink = sink;
    }

    public BigInteger getID() {
        return id;
    }

    public WonderlandIdentity getUserID() {
        return user;
    }

    /** Record the client id the wire stamps on frames for one connection type. */
    void attach(ConnectionType type, short clientID) {
        clientIDs.put(type, clientID);
    }

    void detach(ConnectionType type) {
        clientIDs.remove(type);
    }

    public boolean isAttached(ConnectionType type) {
        return clientIDs.containsKey(type);
    }

    public short clientID(ConnectionType type) {
        Short id = clientIDs.get(type);
        return id == null ? 0 : id;
    }

    public Set<ConnectionType> attachedTypes() {
        return Collections.unmodifiableSet(clientIDs.keySet());
    }

    /** Push one packed frame to one of this session's connections via its sink. */
    public void deliver(ConnectionType type, ByteBuffer packedFrame) {
        sink.deliver(this, type, packedFrame);
    }
}
