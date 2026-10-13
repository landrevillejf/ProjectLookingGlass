/*
 * wonderland-server (M3): the outbound seam a transport offers the protocol
 * router. The router is transport-agnostic -- it never knows whether a client
 * rides a Spring WebSocket (M3b) or an in-JVM loopback (M3a) -- it only ever
 * says "deliver this already-packed frame to connection <type> of session <s>".
 * Each transport implements the sink and maps (session, ConnectionType) onto
 * whatever its real pipe is (a WebSocketSession.sendMessage, or a direct
 * ClientConnection.messageReceived on the loopback's own object graph).
 *
 * The frame is a packed M1 message (MessagePacker.pack output), so a sink that
 * wants to inspect it unpacks it exactly as a socket peer would -- the loopback
 * and the WebSocket handler share one code path through the codec.
 */
package org.jdesktop.wonderland.server.comms;

import java.nio.ByteBuffer;
import org.jdesktop.wonderland.common.comms.ConnectionType;

public interface FrameSink {

    void deliver(ServerSession session, ConnectionType connectionType, ByteBuffer packedFrame);
}
