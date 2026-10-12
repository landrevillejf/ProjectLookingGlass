/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 * Registers with the cell's MessageChannel at ACTIVE and applies a pushed
 * CellClientStateMessage onto the cell. The original implemented
 * ChannelComponent.ComponentMessageReceiver; M2c's seam (MessageChannel) owns
 * that role -- see the Cell header for the wiring deferral.
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.common.cell.messages.CellClientStateMessage;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;

public class CellClientStateMessageReceiver implements MessageChannel.MessageReceiver {

    private final Cell cell;

    public CellClientStateMessageReceiver(Cell cell) {
        this.cell = cell;
    }

    @Override
    public void messageReceived(CellMessage message) {
        cell.setClientState(((CellClientStateMessage) message).getClientState());
    }
}
