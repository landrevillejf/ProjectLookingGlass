/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.comms),
 * seam-reduced (docs/wonderland-migration.md, "rebuild the unrecoverable").
 *
 * The 2006 interface named four SGS/login members that cannot survive headless:
 *   - getSessionManager() -> ServerSessionManager, the SGS login oracle;
 *   - getSimpleClient() -> com.sun.sgs.client.simple.SimpleClient, retired
 *     upstream (the plan rebuilds the whole server on Spring Boot in M3);
 *   - login(LoginParameters) / logout(), the SGS handshake.
 * They are DROPPED, not stubbed: the M3 Spring session owns the login surface
 * (LoginParameters already exists as a pure value type, M2b). getID()/getUserID()
 * stay -- the SGS realm *minted* them, but the types (BigInteger,
 * WonderlandIdentity, round-trip tested in M1) carry no SGS reference. What
 * remains is exactly the connection-orchestration surface BaseConnection and the three
 * cell connections actually call -- and it is fully exercisable against an
 * in-JVM loopback session today, which is the harness M3a ships for real.
 */
package org.jdesktop.wonderland.client.comms;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Properties;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;

@ExperimentalAPI
public interface WonderlandSession {

    WonderlandServerInfo getServerInfo();

    Status getStatus();

    BigInteger getID();

    WonderlandIdentity getUserID();

    void connect(ClientConnection connection) throws ConnectionFailureException;

    void connect(ClientConnection connection, Properties properties)
            throws ConnectionFailureException;

    void disconnect(ClientConnection connection);

    ClientConnection getConnection(ConnectionType connectionType);

    <T extends ClientConnection> T getConnection(ConnectionType connectionType,
            Class<T> clazz);

    Collection<ClientConnection> getConnections();

    void send(ClientConnection connection, Message message);

    void addSessionStatusListener(SessionStatusListener listener);

    void removeSessionStatusListener(SessionStatusListener listener);

    @ExperimentalAPI
    public enum Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED;
    }
}
