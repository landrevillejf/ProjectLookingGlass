/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell),
 * verbatim in logic -- and this is the class the M2c MessageChannel seam was
 * cut for: the real channel a Cell gets when the cache loads it. It rides the
 * session-wide CellChannelConnection, resolved through the cache accessor
 * exactly as the original did (cell.getCellCache().getCellChannelConnection(),
 * restored to the CellCache interface in this milestone).
 *
 * The receiver map is the 2006 contract to the letter: registering a class
 * twice with a DIFFERENT receiver throws (same receiver is an idempotent
 * replace); an inbound message for the wrong CellID is a SEVERE log and no
 * dispatch; a message class with no receiver warns. send()/sendAndWait() fill
 * in the cell's own id when the message carries none -- the detail that lets
 * components build a bare CellComponentMessage and still route it.
 *
 * The nested ComponentMessageReceiver of the original is the M2c
 * MessageChannel.MessageReceiver (renamed then, referenced here).
 */
package org.jdesktop.wonderland.client.cell;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.client.comms.ClientConnection;
import org.jdesktop.wonderland.client.comms.ResponseListener;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@ExperimentalAPI
public class ChannelComponent extends CellComponent implements MessageChannel {

    private static final Logger logger = Logger.getLogger(ChannelComponent.class.getName());

    private final Map<Class<? extends CellMessage>, MessageReceiver> messageReceivers =
            new LinkedHashMap<>();
    private final CellChannelConnection connection;

    public ChannelComponent(Cell cell) {
        super(cell);
        this.connection = cell.getCellCache().getCellChannelConnection();
    }

    @Override
    public synchronized void addMessageReceiver(Class<? extends CellMessage> msgClass,
            MessageReceiver receiver) {
        MessageReceiver old = messageReceivers.put(msgClass, receiver);
        if (old != null && old != receiver) {
            throw new IllegalStateException("Duplicate Message class added " + msgClass);
        }
    }

    @Override
    public synchronized void removeMessageReceiver(Class<? extends CellMessage> msgClass) {
        messageReceivers.remove(msgClass);
    }

    protected synchronized MessageReceiver getMessageReceiver(
            Class<? extends CellMessage> msgClass) {
        return messageReceivers.get(msgClass);
    }

    @Override
    public void messageReceived(CellMessage message) {
        if (logger.isLoggable(Level.FINEST)) {
            logger.finest("---> Impl received message for " + message.getCellID()
                    + "   impl cell " + cell.getCellID() + "  recievers "
                    + messageReceivers.size());
        }
        if (!message.getCellID().equals(cell.getCellID())) {
            logger.severe("Message for wrong cell " + message.getCellID());
            return;
        }
        MessageReceiver recvRef = getMessageReceiver(message.getClass());
        if (recvRef == null) {
            logger.warning("No listener for message " + message.getClass() + " from cell "
                    + cell.getClass().getName() + " status " + cell.getStatus());
            return;
        }
        recvRef.messageReceived(message);
    }

    public ClientConnection.Status getStatus() {
        return connection.getStatus();
    }

    public void send(CellMessage message, ResponseListener listener) {
        if (message.getCellID() == null) {
            message.setCellID(cell.getCellID());
        }
        connection.send(message, listener);
    }

    @Override
    public void send(CellMessage message) {
        if (message.getCellID() == null) {
            message.setCellID(cell.getCellID());
        }
        connection.send(message);
    }

    @Override
    public ResponseMessage sendAndWait(CellMessage message) throws InterruptedException {
        if (message.getCellID() == null) {
            message.setCellID(cell.getCellID());
        }
        return connection.sendAndWait(message);
    }
}
