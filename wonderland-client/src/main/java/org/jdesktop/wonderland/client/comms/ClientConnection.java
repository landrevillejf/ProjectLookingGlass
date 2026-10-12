/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.comms),
 * verbatim -- the enum and the seven operations carry no SGS reference.
 *
 * M2e note: this is the client half of the connection contract the 2006
 * CommsManager/MINA plumbing fed. WonderlandSessionImpl's login handshake is
 * rebuilt in M3 (Spring), but everything above the transport -- BaseConnection
 * and the three cell connections -- runs and is tested against a session seam
 * today.
 */
package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;

@ExperimentalAPI
public interface ClientConnection {

    ConnectionType getConnectionType();

    WonderlandSession getSession();

    Status getStatus();

    void connected(WonderlandSession session);

    void disconnected();

    void messageReceived(Message message);

    public enum Status {
        DISCONNECTED,
        CONNECTED;
    }
}
