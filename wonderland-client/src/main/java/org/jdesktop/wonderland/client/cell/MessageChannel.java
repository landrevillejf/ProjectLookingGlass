/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * This is the M2c SEAM for what the original called ChannelComponent: Cell speaks
 * message-channel operations, and the concrete channel (a CellComponent riding the
 * M2d connection layer) will implement this interface instead of being hard-wired
 * into Cell through getComponent(ChannelComponent.class). The seam is what lets
 * the whole Cell lifecycle -- status machine, receiver registration, send /
 * sendAndWait -- be exercised headless in CI today with an in-test channel,
 * exactly the pattern the retired M2b view/renderer contracts follow.
 *
 * The nested MessageReceiver is the original ChannelComponent.ComponentMessageReceiver,
 * renamed to live with the seam; the two cell receivers implement it.
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

public interface MessageChannel {

    void addMessageReceiver(Class<? extends CellMessage> messageClass, MessageReceiver receiver);

    void removeMessageReceiver(Class<? extends CellMessage> messageClass);

    void send(CellMessage message);

    ResponseMessage sendAndWait(CellMessage message) throws InterruptedException;

    interface MessageReceiver {
        void messageReceived(CellMessage message);
    }
}
