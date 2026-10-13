/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell),
 * verbatim. The avatar's move component: it carries trigger / pressed /
 * animation / height / collision fields alongside the transform, sends them
 * over the channel as a MovableAvatarMessage, and folds the server's reply back
 * into the owning AvatarCell (triggerCollision / triggerAction).
 *
 * No new seam beyond MovableComponent's: it drives the inherited
 * {@code channelComp} (resolved from cell.getChannel() at ACTIVE) and the
 * inherited {@code throttle} directly, exactly as the original did through its
 * injected channel field. The trigger path is deliberately UNthrottled -- the
 * original clears the throttle and sends immediately, because a triggered
 * animation must not be rate-limited away.
 *
 * createMoveResponseListener keeps the original's contract: a non-null
 * move-modified listener is "Not supported" for avatar moves (the reply cannot
 * carry the trigger semantics back), so only the null-listener form is used.
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.client.cell.view.AvatarCell;
import org.jdesktop.wonderland.client.comms.ResponseListener;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.ComponentLookupClass;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableAvatarMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;
import org.jdesktop.wonderland.common.cell.state.MovableAvatarComponentClientState;

@ComponentLookupClass(MovableComponent.class)
public class MovableAvatarComponent extends MovableComponent {

    private int trigger;
    private boolean pressed;
    private String animationName;
    private float height;
    private boolean collision;
    private int serverTrigger;
    private String serverAnimationName;
    public static final int NO_TRIGGER = -1;

    public MovableAvatarComponent(Cell cell) {
        super(cell);
    }

    public void localMoveRequest(CellTransform transform) {
        this.localMoveRequest(transform, NO_TRIGGER, false, null,
                (CellMoveModifiedListener) null);
    }

    public void localMoveRequest(CellTransform transform, float height, boolean collision) {
        this.localMoveRequest(transform, NO_TRIGGER, false, null, height, collision,
                (CellMoveModifiedListener) null);
    }

    public void localMoveRequest(CellTransform transform, CellMoveModifiedListener listener) {
        this.localMoveRequest(transform, NO_TRIGGER, false, null, listener);
    }

    public void localMoveRequest(CellTransform transform, int trigger, boolean pressed,
            String animationName, CellMoveModifiedListener listener) {
        this.localMoveRequest(transform, trigger, pressed, animationName, 0.0F, false, listener);
    }

    public void localMoveRequest(CellTransform transform, int trigger, boolean pressed,
            String animationName, float height, boolean collision,
            CellMoveModifiedListener listener) {
        synchronized (this) {
            this.trigger = trigger;
            this.pressed = pressed;
            this.animationName = animationName;
            this.height = height;
            this.collision = collision;
            if (trigger == NO_TRIGGER) {
                super.localMoveRequest(transform, (CellMoveModifiedListener) null);
            } else {
                this.throttle.clear();
                this.channelComp.send(this.createMoveRequestMessage(transform));
                this.applyLocalTransformChange(transform,
                        TransformChangeListener.ChangeSource.LOCAL);
            }
        }
    }

    @Override
    protected CellMessage createMoveRequestMessage(CellTransform transform) {
        return MovableAvatarMessage.newMoveRequestMessage(this.cell.getCellID(),
                transform.getTranslation((Vector3f) null),
                transform.getRotation((Quaternion) null),
                this.trigger, this.pressed, this.animationName, this.height, this.collision);
    }

    @Override
    protected ResponseListener createMoveResponseListener(CellMoveModifiedListener listener) {
        if (listener == null) {
            return super.createMoveResponseListener((CellMoveModifiedListener) null);
        } else {
            throw new RuntimeException("Not supported");
        }
    }

    @Override
    protected Class<? extends CellMessage> getMessageClass() {
        return MovableAvatarMessage.class;
    }

    @Override
    protected void serverMoveRequest(MovableMessage msg) {
        super.serverMoveRequest(msg);
        MovableAvatarMessage mam = (MovableAvatarMessage) msg;
        if (this.trigger != NO_TRIGGER) {
            this.serverTrigger = mam.getTrigger();
            this.serverAnimationName = mam.getAnimationName();
        }

        ((AvatarCell) this.cell).triggerCollision(mam.getHeight(), mam.isCollision());
        if (mam.getTrigger() != NO_TRIGGER) {
            ((AvatarCell) this.cell).triggerAction(mam.getTrigger(), mam.isPressed(),
                    mam.getAnimationName());
        }
    }

    @Override
    public void setClientState(CellComponentClientState clientState) {
        super.setClientState(clientState);
        MovableAvatarComponentClientState state = (MovableAvatarComponentClientState) clientState;
        this.serverAnimationName = state.getAnimationName();
        this.serverTrigger = state.getTrigger();
    }

    public int getServerTrigger() {
        return this.serverTrigger;
    }

    public String getServerAnimationName() {
        return this.serverAnimationName;
    }
}
