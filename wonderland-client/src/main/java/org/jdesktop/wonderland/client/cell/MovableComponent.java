/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell),
 * verbatim in logic -- M2f's local/server move machinery: a rate-limited
 * (Throttle, 2/sec) request/reply for a cell transform, an inbound receiver
 * that ignores the server echo of our own moves, and the two change-source
 * paths (LOCAL applies immediately, REMOTE notifies server move listeners).
 *
 * One seam, the same one the M2c MessageChannel contract was cut for:
 *   - the original's {@code @UsesCellComponent ChannelComponent channelComp}
 *     was reflectively injected with the cell's channel. In this port the real
 *     ChannelComponent is installed as {@code cell.getChannel()} by the session
 *     on cellLoaded (M2e), NOT in the component map, so injecting a fresh one
 *     here would register a receiver that inbound delivery (which routes
 *     through cell.getChannel()) never reaches. channelComp is therefore
 *     resolved from cell.getChannel() at the ACTIVE rung. The field keeps the
 *     original name and type because MovableAvatarComponent drives it directly.
 *   - the nested {@code ChannelComponent.ComponentMessageReceiver} is the M2c
 *     {@code MessageChannel.MessageReceiver}.
 *   - the session identity compare uses the CellCache.getSession() accessor that
 *     M2f restores (the 2006 WonderlandSession getSession(), typed here as the
 *     CellClientSession that actually owns the cache); a null session -- a
 *     cache with no session wired -- is treated as "no self id", so an inbound
 *     move is applied as a REMOTE move exactly as the original did for a nil
 *     sender.
 */
package org.jdesktop.wonderland.client.cell;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.client.comms.ClientConnection;
import org.jdesktop.wonderland.client.comms.ResponseListener;
import org.jdesktop.wonderland.client.utils.Throttle;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessageResponse;
import org.jdesktop.wonderland.common.messages.ErrorMessage;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@ExperimentalAPI
public class MovableComponent extends CellComponent {

    protected final Throttle throttle;
    protected static final Logger logger =
            Logger.getLogger(MovableComponent.class.getName());
    protected ArrayList<CellMoveListener> serverMoveListeners;
    /** seam: resolved from {@code cell.getChannel()} at ACTIVE, see header. */
    protected ChannelComponent channelComp;
    protected MessageChannel.MessageReceiver msgReceiver;

    public MovableComponent(Cell cell) {
        super(cell);
        this.throttle = new Throttle(2L, TimeUnit.SECONDS);
        this.serverMoveListeners = null;
        this.msgReceiver = null;
    }

    @Override
    protected void setStatus(CellStatus status, boolean increasing) {
        super.setStatus(status, increasing);
        switch (status) {
            case DISK:
                if (this.msgReceiver != null && this.channelComp != null) {
                    this.channelComp.removeMessageReceiver(this.getMessageClass());
                    this.msgReceiver = null;
                }
                break;
            case ACTIVE:
                if (increasing && this.msgReceiver == null) {
                    MessageChannel ch = cell.getChannel();
                    this.channelComp =
                            (ch instanceof ChannelComponent) ? (ChannelComponent) ch : null;
                    if (this.channelComp == null) {
                        logger.warning("Cell has no channel when moving cell "
                                + this.cell.getCellID());
                        break;
                    }
                    this.msgReceiver = new MessageChannel.MessageReceiver() {
                        public void messageReceived(CellMessage message) {
                            BigInteger senderID = message.getSenderID();
                            if (senderID == null) {
                                senderID = BigInteger.ZERO;
                            }

                            if (!senderID.equals(selfSessionID())) {
                                MovableComponent.this.serverMoveRequest(
                                        (MovableMessage) message);
                            }
                        }
                    };
                    this.channelComp.addMessageReceiver(this.getMessageClass(),
                            this.msgReceiver);
                }
                break;
            default:
                break;
        }
    }

    /**
     * Reads {@code cell.getCellCache().getSession().getID()} exactly as the
     * original receiver did, but null-safe: a cache with no session wired (or
     * no session connected yet) has no self identity, so it returns null and
     * the compare falls through to "not me" -- an inbound move is applied as a
     * REMOTE move, the same outcome the original reached for a null sender id.
     */
    private BigInteger selfSessionID() {
        if (cell.getCellCache() == null) {
            return null;
        }
        org.jdesktop.wonderland.client.comms.CellClientSession s =
                cell.getCellCache().getSession();
        return s == null ? null : s.getID();
    }

    protected Class<? extends CellMessage> getMessageClass() {
        return MovableMessage.class;
    }

    public void localMoveRequest(CellTransform transform, CellMoveModifiedListener listener) {
        if (this.channelComp != null
                && this.channelComp.getStatus() == ClientConnection.Status.CONNECTED) {
            final CellMessage req = this.createMoveRequestMessage(transform);
            final ResponseListener resp = this.createMoveResponseListener(listener);
            this.throttle.schedule(new Runnable() {
                public void run() {
                    MovableComponent.this.channelComp.send(req, resp);
                }
            });
            this.applyLocalTransformChange(transform, TransformChangeListener.ChangeSource.LOCAL);
        } else {
            logger.warning("Cell channel not connected when moving cell "
                    + this.cell.getCellID());
        }
    }

    protected CellMessage createMoveRequestMessage(CellTransform transform) {
        return MovableMessage.newMoveRequestMessage(this.cell.getCellID(), transform);
    }

    protected ResponseListener createMoveResponseListener(
            final CellMoveModifiedListener listener) {
        return listener == null ? new ResponseListener() {
            public void responseReceived(ResponseMessage response) {
                if (response instanceof ErrorMessage) {
                    ErrorMessage error = (ErrorMessage) response;
                    MovableComponent.logger.log(Level.WARNING,
                            "Error sending move: " + error.getErrorMessage(),
                            error.getErrorCause());
                }
            }
        } : new ResponseListener() {
            public void responseReceived(ResponseMessage response) {
                CellTransform requestedTransform = null;
                CellTransform actualTransform = null;
                if (response instanceof MovableMessageResponse) {
                    MovableMessageResponse msg = (MovableMessageResponse) response;
                    actualTransform = new CellTransform(msg.getRotation(),
                            msg.getTranslation(), msg.getScale());
                }

                int reason = 1;
                listener.moveModified(requestedTransform, reason, actualTransform);
            }
        };
    }

    protected void applyLocalTransformChange(CellTransform transform,
            TransformChangeListener.ChangeSource source) {
        this.cell.setLocalTransform(transform, source);
    }

    public void localMoveRequest(CellTransform transform) {
        this.localMoveRequest(transform, (CellMoveModifiedListener) null);
    }

    protected void serverMoveRequest(MovableMessage msg) {
        CellTransform transform = msg.getCellTransform();
        this.applyLocalTransformChange(transform, TransformChangeListener.ChangeSource.REMOTE);
        this.notifyServerCellMoveListeners(msg, transform, CellMoveSource.REMOTE);
    }

    public void addServerCellMoveListener(CellMoveListener listener) {
        if (this.serverMoveListeners == null) {
            this.serverMoveListeners = new ArrayList<>();
        }

        this.serverMoveListeners.add(listener);
    }

    public void removeServerCellMoveListener(CellMoveListener listener) {
        if (this.serverMoveListeners != null) {
            this.serverMoveListeners.remove(listener);
        }
    }

    protected void notifyServerCellMoveListeners(MovableMessage msg, CellTransform transform,
            CellMoveSource source) {
        if (this.serverMoveListeners != null) {
            for (CellMoveListener listener : this.serverMoveListeners) {
                listener.cellMoved(transform, source);
            }
        }
    }

    public enum CellMoveSource {
        LOCAL,
        REMOTE
    }

    @ExperimentalAPI
    public interface CellMoveListener {
        void cellMoved(CellTransform transform, CellMoveSource source);
    }

    @ExperimentalAPI
    public interface CellMoveModifiedListener {
        void moveModified(CellTransform requested, int reason, CellTransform actual);
    }
}
