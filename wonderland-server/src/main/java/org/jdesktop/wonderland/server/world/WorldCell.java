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
    private final String deployedModelURL;

    public WorldCell(CellID cellID, CellID parentID, String className, String name,
            BoundingVolume localBounds, CellTransform transform, CellClientState clientState) {
        this(cellID, parentID, className, name, localBounds, transform, clientState, null);
    }

    /**
     * Full form, adding the cell's {@code wla://} deployed-model URL (Stage 2).
     * This field is the SERVER-SIDE copy that feeds the read-only REST descriptor
     * and the HTTP asset endpoint. The same URL also reaches the native client,
     * but through {@code clientState}: for a model cell the seeder passes a
     * {@code ModelCellClientState} carrying it, which {@link #toLoadMessage()}
     * sends over the wire (the renderer's WonderlandModelCell reads it back in
     * setClientState()). Keeping both is deliberate -- the field is the HTTP
     * surface's view, the clientState is the socket surface's view of one URL.
     * Null for a cell that carries no model (the environment root, a non-model
     * cell), whose clientState stays a plain CellClientState.
     */
    public WorldCell(CellID cellID, CellID parentID, String className, String name,
            BoundingVolume localBounds, CellTransform transform, CellClientState clientState,
            String deployedModelURL) {
        this.cellID = cellID;
        this.parentID = parentID;
        this.className = className;
        this.name = name;
        this.localBounds = localBounds;
        this.transform = transform == null ? new CellTransform() : transform;
        this.clientState = clientState;
        this.deployedModelURL = deployedModelURL;
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

    /** The cell's {@code wla://} deployed-model URL, or null when it has no model. */
    public String getDeployedModelURL() {
        return deployedModelURL;
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
