/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell.view),
 * verbatim. The camera/avatar cell: it remembers the identity from its
 * ViewCellClientState, grabs its MovableAvatarComponent when it reaches ACTIVE,
 * and -- the whole point of this class -- tells the session's LocalAvatar that
 * the view cell is (un)configured as the status walk crosses ACTIVE, so the
 * desktop knows which cell the camera is standing in.
 *
 * Seam reconciliation: the original wrote
 *   CellClientSession s = (CellClientSession) cellCache.getSession();
 * because the 2006 CellCache.getSession() returned the WonderlandSession
 * supertype. M2f restores getSession() typed as the CellClientSession that
 * actually owns the cache (docs/wonderland-migration.md, "rebuild the
 * unrecoverable"), so the cast is unnecessary here and dropped -- the call
 * chain (getCellCacheConnection().getViewCellID(), getLocalAvatar()
 * .viewCellConfigured) is otherwise the original, line for line.
 *
 * The move component lookup keeps the original's double getComponent() call
 * (one to test instanceof MovableAvatarComponent, one to store it) verbatim.
 */
package org.jdesktop.wonderland.client.cell.view;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.cell.MovableAvatarComponent;
import org.jdesktop.wonderland.client.cell.MovableComponent;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.view.ViewCellClientState;

@ExperimentalAPI
public class ViewCell extends Cell {

    private WonderlandIdentity identity;
    private MovableAvatarComponent movableComp = null;

    public ViewCell(CellID cellID, CellCache cellCache) {
        super(cellID, cellCache);
    }

    @Override
    protected void setStatus(CellStatus status, boolean increasing) {
        super.setStatus(status, increasing);
        switch (status) {
            case ACTIVE:
                if (increasing) {
                    CellClientSession s = this.getCellCache().getSession();
                    if (s.getCellCacheConnection().getViewCellID().equals(this.getCellID())) {
                        s.getLocalAvatar().viewCellConfigured(this.getCellID());
                    }

                    MovableComponent mc = this.getComponent(MovableComponent.class);
                    if (mc instanceof MovableAvatarComponent) {
                        this.movableComp =
                                (MovableAvatarComponent) this.getComponent(MovableComponent.class);
                    }
                }
                break;
            case INACTIVE:
                if (!increasing) {
                    CellClientSession s = this.getCellCache().getSession();
                    if (s.getCellCacheConnection().getViewCellID().equals(this.getCellID())) {
                        s.getLocalAvatar().viewCellConfigured((CellID) null);
                    }

                    this.movableComp = null;
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void setClientState(CellClientState cellClientState) {
        super.setClientState(cellClientState);
        this.identity = ((ViewCellClientState) cellClientState).getIdentity();
    }

    public WonderlandIdentity getIdentity() {
        return this.identity;
    }

    public void localMoveRequest(CellTransform transform) {
        if (this.movableComp != null) {
            this.movableComp.localMoveRequest(transform, MovableAvatarComponent.NO_TRIGGER,
                    false, (String) null, (MovableComponent.CellMoveModifiedListener) null);
        }
    }
}
