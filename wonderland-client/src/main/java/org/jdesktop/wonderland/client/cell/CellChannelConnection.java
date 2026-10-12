/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * The inbound CellMessage transport: one connection for the whole session,
 * demultiplexing by CellID through a per-cell CellMessageDelivery that QUEUES
 * messages arriving before the cell has a channel (the classic load race: the
 * server starts talking the instant the load message goes out) and flushes the
 * queue the moment a receiver appears. On unload the delivery entry is swapped
 * to a Noop receiver and dropped a beat later, so in-flight messages land in
 * the void instead of resurrecting the map entry. All of that is the 2006
 * logic, verbatim -- and all of it is pure in-JVM queueing, fully testable.
 *
 * M2e seams (docs/wonderland-migration.md, "rebuild the unrecoverable"):
 *   - handleMessage() re-resolved the cache per message through
 *     ClientContext.getCellCache(session); ClientContext is a login/SGS relic.
 *     The cache is already wired here by setCellCache() -- the M2d registry
 *     calls this connection its CellCache transport -- so the lookup becomes
 *     the field. Same delivery, no static context.
 *   - the channel lookup getComponent(ChannelComponent.class) becomes
 *     cell.getChannel(), the M2c MessageChannel seam; the M2e ChannelComponent
 *     implements that seam for real, and a null channel still means the
 *     documented Noop-receiver warning path.
 *   - the cleanup delay is a settable field (2006: a hard-coded 1000 ms on a
 *     shared single-thread executor) so the drop-old-entry race is testable
 *     without sleeping a second; the default value is the original 1000 ms.
 */
package org.jdesktop.wonderland.client.cell;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.client.comms.BaseConnection;
import org.jdesktop.wonderland.client.comms.ResponseListener;
import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@InternalAPI
public class CellChannelConnection extends BaseConnection
        implements CellCache.CellCacheListener, CellStatusChangeListener {

    private static final Logger logger =
            Logger.getLogger(CellChannelConnection.class.getName());
    private static final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private CellCache cache;
    private final Map<CellID, CellMessageDelivery> delivery = new HashMap<>();

    /** 2006 CLEANUP_DELAY; settable (package-private) so tests run it fast. */
    long cleanupDelayMillis = 1000L;

    @Override
    public ConnectionType getConnectionType() {
        return CellChannelConnectionType.CLIENT_TYPE;
    }

    public void send(CellMessage message) {
        super.send(message);
    }

    public void send(CellMessage message, ResponseListener listener) {
        super.send(message, listener);
    }

    public ResponseMessage sendAndWait(CellMessage message) throws InterruptedException {
        return super.sendAndWait(message);
    }

    /** Session-side wiring entry: the M2e CellClientSession crosses the
     *  links; public because that session lives in client.comms (the 2006
     *  wiring was same-package, the headless split follows the decompiled
     *  layout's package names). */
    public void setCellCache(CellCache cache) {
        this.cache = cache;
        cache.addCellCacheListener(this);
    }

    @Override
    public void handleMessage(Message message) {
        if (logger.isLoggable(Level.FINEST)) {
            logger.finest("Handling Message " + message.getClass().getName());
        }
        CellMessage cellMessage = (CellMessage) message;
        // M2e seam: the 2006 code re-read the cache from ClientContext here;
        // the wired cache field is the same object without the static lookup.
        if (cache == null) {
            logger.severe("Unable to deliver CellMessage, CellCache is null");
            return;
        }
        getCellMessageDelivery(cellMessage.getCellID()).deliver(cellMessage);
    }

    protected synchronized CellMessageDelivery getCellMessageDelivery(CellID cellID) {
        CellMessageDelivery out = delivery.get(cellID);
        if (out == null) {
            out = new CellMessageDelivery();
            delivery.put(cellID, out);
        }
        return out;
    }

    @Override
    public void cellLoaded(CellID cellID, Cell cell) {
        cell.addStatusChangeListener(this);
    }

    @Override
    public void cellStatusChanged(Cell cell, CellStatus status) {
        if (status == CellStatus.ACTIVE) {
            MessageChannel channel = cell.getChannel();
            CellMessageDelivery cmd = getCellMessageDelivery(cell.getCellID());
            if (channel == null) {
                logger.warning("No channel for cell " + cell.getCellID()
                        + ": ignoring messages.");
                cmd.setMessageReceiver(new NoopMessageReceiver(cell.getCellID()));
            } else {
                cmd.setMessageReceiver(new ChannelMessageReceiver(channel));
            }
        }
        if (status == CellStatus.DISK) {
            cellUnloaded(cell.getCellID(), cell);
        }
    }

    @Override
    public void cellLoadFailed(CellID cellID, String className, CellID parentCellID,
            Throwable cause) {
        logger.warning("Failed to load cell " + cellID + " of type " + className
                + ".  Discarding messages.");
        CellMessageDelivery cmd = getCellMessageDelivery(cellID);
        cmd.setMessageReceiver(new NoopMessageReceiver(cellID));
    }

    @Override
    public void cellUnloaded(final CellID cellID, Cell cell) {
        final CellMessageDelivery cmd;
        synchronized (this) {
            cmd = delivery.get(cellID);
        }
        if (cmd != null) {
            final MessageReceiver noopRecv = new NoopMessageReceiver(cellID);
            cmd.setMessageReceiver(noopRecv);
            executor.schedule(() -> {
                synchronized (CellChannelConnection.this) {
                    if (cmd.getMessageReceiver() == noopRecv) {
                        delivery.remove(cellID);
                    }
                }
            }, cleanupDelayMillis, TimeUnit.MILLISECONDS);
        }
    }

    protected static class CellMessageDelivery {

        private Queue<CellMessage> delayQueue;
        private MessageReceiver receiver;

        protected void deliver(CellMessage message) {
            synchronized (this) {
                if (delayQueue != null) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine("Delaying message (existing queue) of type "
                                + message.getClass().getSimpleName() + " for "
                                + message.getCellID());
                    }
                    delayQueue.add(message);
                    return;
                }
                if (receiver == null) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine("Delaying message (no receiver) of type "
                                + message.getClass().getSimpleName() + " for "
                                + message.getCellID());
                    }
                    logger.warning("Delaying messages for cell " + message.getCellID());
                    delayQueue = new LinkedList<>();
                    delayQueue.add(message);
                    return;
                }
            }
            receiver.messageReceived(message);
        }

        protected void setMessageReceiver(MessageReceiver receiver) {
            synchronized (this) {
                this.receiver = receiver;
            }
            deliverDelayedMessages();
        }

        protected synchronized MessageReceiver getMessageReceiver() {
            return receiver;
        }

        protected void deliverDelayedMessages() {
            if (delayQueue != null && logger.isLoggable(Level.FINE)) {
                logger.fine("Delivering " + delayQueue.size() + " delayed messages");
            }
            while (true) {
                synchronized (this) {
                    if (delayQueue == null) {
                        return;
                    }
                    CellMessage message = delayQueue.poll();
                    if (message == null) {
                        delayQueue = null;
                        return;
                    }
                    receiver.messageReceived(message);
                }
            }
        }
    }

    private static class ChannelMessageReceiver implements MessageReceiver {

        private final MessageChannel channel;

        public ChannelMessageReceiver(MessageChannel channel) {
            this.channel = channel;
        }

        @Override
        public void messageReceived(CellMessage message) {
            channel.messageReceived(message);
        }
    }

    private class NoopMessageReceiver implements MessageReceiver {

        private final CellID cellID;

        public NoopMessageReceiver(CellID cellID) {
            this.cellID = cellID;
        }

        @Override
        public void messageReceived(CellMessage message) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Ignoring message " + message + " for failed "
                        + "cell " + cellID);
            }
        }
    }

    protected interface MessageReceiver {
        void messageReceived(CellMessage message);
    }
}
