/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell),
 * verbatim -- a typed front over BaseConnection's send paths so the editor
 * only ever sees CellEditMessage, with an intentionally empty receive side
 * (edits are client-authored; replies route through the response listeners).
 *
 * M2e reconciliation: it also becomes the real implementation of the M2d
 * MessageSink seam. The cache's one session-level send -- a CellReparentMessage
 * for a child whose parent is not loaded -- is a CellEditMessage, and this is
 * exactly the connection the 2006 client ran it over (the edit channel, not
 * the per-cell data channel). handleMessage stays empty, per the original.
 */
package org.jdesktop.wonderland.client.cell;

import java.util.logging.Logger;
import org.jdesktop.wonderland.client.comms.BaseConnection;
import org.jdesktop.wonderland.client.comms.ResponseListener;
import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellEditConnectionType;
import org.jdesktop.wonderland.common.cell.messages.CellEditMessage;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@InternalAPI
public class CellEditChannelConnection extends BaseConnection implements MessageSink {

    private static final Logger logger =
            Logger.getLogger(CellEditChannelConnection.class.getName());

    @Override
    public ConnectionType getConnectionType() {
        return CellEditConnectionType.CLIENT_TYPE;
    }

    public void send(CellEditMessage message) {
        super.send(message);
    }

    public void send(CellEditMessage message, ResponseListener listener) {
        super.send(message, listener);
    }

    public ResponseMessage sendAndWait(CellEditMessage message) throws InterruptedException {
        return super.sendAndWait(message);
    }

    /** The M2d seam, made real: the cache's reparent rides the edit channel. */
    @Override
    public void send(Message message) {
        super.send(message);
    }

    @Override
    public void handleMessage(Message message) {
        // the edit channel does not receive; verbatim 2006
    }
}
