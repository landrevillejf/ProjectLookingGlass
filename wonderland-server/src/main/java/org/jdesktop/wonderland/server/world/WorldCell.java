/*
 * wonderland-server (M3): the server-side, transport-agnostic descriptor of one
 * cell in the authoritative world. It carries exactly what the client cache's
 * loadCell(...) needs to reflect a Cell open and place it in the tree -- the
 * class NAME (a wonderland-client Cell subclass, held here as inert data so the
 * server never imports the client), the parent link, the local bounds, the
 * transform and the client state -- plus a human name. It is the piece the SGS
 * "cell storage" provided, rebuilt as plain data on the Spring session.
 *
 * toLoadMessage() renders the M1 wire type (CellHierarchyMessage LOAD_CELL) that
 * the protocol router packs and pushes to a client's cell-cache connection; the
 * field order mirrors the client CellCacheBasicImpl.loadCell(...) signature, so
 * a round-tripped load lands on the right arguments.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.bounding.BoundingVolume;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage.ActionType;
import org.jdesktop.wonderland.common.cell.state.CellClientState;

public final class WorldCell {

    private final CellID cellID;
    private final CellID parentID;
    private final String className;
    private final String name;
    private final BoundingVolume localBounds;
    private final CellTransform transform;
    private final CellClientState clientState;

    public WorldCell(CellID cellID, CellID parentID, String className, String name,
            BoundingVolume localBounds, CellTransform transform, CellClientState clientState) {
        this.cellID = cellID;
        this.parentID = parentID;
        this.className = className;
        this.name = name;
        this.localBounds = localBounds;
        this.transform = transform == null ? new CellTransform() : transform;
        this.clientState = clientState;
    }

    public CellID getCellID() {
        return cellID;
    }

    public CellID getParentID() {
        return parentID;
    }

    public String getClassName() {
        return className;
    }

    public String getName() {
        return name;
    }

    public BoundingVolume getLocalBounds() {
        return localBounds;
    }

    public CellTransform getTransform() {
        return transform;
    }

    public CellClientState getClientState() {
        return clientState;
    }

    /** The wire form the router pushes over the cell-cache connection. */
    public CellHierarchyMessage toLoadMessage() {
        return new CellHierarchyMessage(ActionType.LOAD_CELL, className, localBounds,
                cellID, parentID, transform, clientState, name);
    }

    /** The wire form for a server-authoritative move update (see router broadcast). */
    public CellHierarchyMessage toConfigureMessage() {
        return new CellHierarchyMessage(ActionType.CONFIGURE_CELL, className, localBounds,
                cellID, parentID, transform, clientState, name);
    }
}
