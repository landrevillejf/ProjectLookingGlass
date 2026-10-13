/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell.view),
 * verbatim. The local user's view/avatar handle: it owns the "which cell am I
 * looking out of" state, forwards a move request to the view cell, and notifies
 * ViewCellConfiguredListeners whenever the view cell is (un)configured -- the
 * hook ViewCell fires as the status walk crosses ACTIVE/INACTIVE.
 *
 * The original serverInitialized is EMPTY. That is kept: in 2006 the view's
 * real configuration arrived as the view cell reaching ACTIVE (viewCellConfigured),
 * not as the create-response; a no-op here is the actual contract, so the
 * connect() path -- which calls serverInitialized then stores the view cell id
 * on CellCacheConnection -- is exercised through the viewCellConfigured listener
 * instead.
 *
 * One seam (docs/wonderland-migration.md): the original resolved the cache with
 * ClientContext.getCellCache(session) (the global ClientContext is not in this
 * tree). LocalAvatar is constructed by the CellClientSession that owns the
 * cache, so it reaches the same cache through session.getCellCache() -- the
 * CellClientSession stands in for both the WonderlandSession and the context.
 */
package org.jdesktop.wonderland.client.cell.view;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.util.ArrayList;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;

public class LocalAvatar implements ClientView {

    private String viewID = "DEFAULT";
    private ViewCell viewCell = null;
    private final CellClientSession session;
    private ArrayList<ViewCellConfiguredListener> configListeners = null;

    public LocalAvatar(CellClientSession session) {
        this.session = session;
    }

    public void localMoveRequest(Vector3f location, Quaternion rotation) {
        if (this.viewCell != null) {
            this.viewCell.localMoveRequest(new CellTransform(rotation, location));
        }
    }

    public String getViewID() {
        return this.viewID;
    }

    @Override
    public void serverInitialized(ViewCreateResponseMessage msg) {
        // empty in the original, kept empty: the view is configured when its
        // cell reaches ACTIVE, see header
    }

    public void viewCellConfigured(CellID cellID) {
        CellCache cache = this.session.getCellCache();
        if (cellID == null) {
            this.viewCell = null;
        } else {
            this.viewCell = (ViewCell) cache.getCell(cellID);
        }

        cache.setViewCell(this.viewCell);
        this.notifyViewCellConfiguredListeners();
    }

    public void addViewCellConfiguredListener(ViewCellConfiguredListener listener) {
        synchronized (this) {
            if (this.configListeners == null) {
                this.configListeners = new ArrayList<>();
            }

            this.configListeners.add(listener);
        }
    }

    public void removeViewCellConfiguredListener(ViewCellConfiguredListener listener) {
        synchronized (this) {
            if (this.configListeners != null) {
                this.configListeners.remove(listener);
            }
        }
    }

    private void notifyViewCellConfiguredListeners() {
        if (this.configListeners != null) {
            synchronized (this) {
                for (ViewCellConfiguredListener l : this.configListeners) {
                    l.viewConfigured(this);
                }
            }
        }
    }

    public ViewCell getViewCell() {
        return this.viewCell;
    }

    public interface ViewCellConfiguredListener {
        void viewConfigured(LocalAvatar avatar);
    }
}
