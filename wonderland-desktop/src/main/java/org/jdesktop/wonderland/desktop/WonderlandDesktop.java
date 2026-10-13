/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c: the bootstrap entry point that connects the
 * wonderland client session, installs the renderer, and mounts the world into
 * the lg3d desktop.
 *
 * This class orchestrates the live path: it calls
 * WonderlandRendererBootstrap.install() to fill the AvatarRendererInstallers
 * slot, creates a WonderlandWorldContainer, wires it under the desktop's
 * Locale, and drives cell lifecycle events from the CellClientSession through
 * CellFrameBridge. Not constructed in CI (its methods touch Component3D and
 * AppConnector paths that require a live desktop session); verified by the
 * in-JVM probe.
 */
package org.jdesktop.wonderland.desktop;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.renderer.bootstrap.WonderlandRendererBootstrap;

/**
 * The wonderland-desktop bootstrap: wires the client, the renderer, and the
 * lg3d scene-graph container into a coherent running world inside the desktop.
 *
 * <p>Probe-only: every method here touches the live desktop session. CI never
 * runs any of this.</p>
 */
public final class WonderlandDesktop {

    private final CellFrameBridge bridge;
    private final WonderlandWorldContainer worldContainer;
    private boolean installed;

    /**
     * Create the desktop bootstrap with a fresh world container and bridge.
     * The world container's constructor needs a live AppConnector session.
     */
    public WonderlandDesktop() {
        this.worldContainer = new WonderlandWorldContainer();
        this.bridge = new CellFrameBridge(worldContainer);
    }

    /**
     * Install the live avatar renderer into the client seam. Called once at
     * desktop boot, before any cell is loaded.
     */
    public void installRenderer() {
        if (installed) {
            return;
        }
        WonderlandRendererBootstrap.install();
        installed = true;
    }

    /**
     * Mount a cell's renderer scene into the desktop. Called by the client's
     * cell-loaded notification.
     *
     * @param cell  the loaded cell (must have a non-null renderer already created)
     */
    public void cellLoaded(Cell cell) {
        if (cell == null) {
            return;
        }
        String cellId = cell.getCellID().toString();
        CellRenderer renderer = cell.getCellRenderer(Cell.RendererType.RENDERER_JME);
        bridge.mountCell(cellId, renderer);
    }

    /**
     * Unmount a cell from the desktop. Called by the client's cell-unloaded
     * notification.
     *
     * @param cell  the cell being removed
     */
    public void cellUnloaded(Cell cell) {
        if (cell == null) {
            return;
        }
        bridge.unmountCell(cell.getCellID().toString());
    }

    /**
     * Apply a transform update from the server. Called by the client's
     * movable-component listener.
     *
     * @param cell       the moving cell
     * @param transform  the new world-space transform
     */
    public void cellTransformUpdate(Cell cell, CellTransform transform) {
        if (cell == null) {
            return;
        }
        bridge.updateTransform(cell.getCellID().toString(), transform);
    }

    /**
     * The cell frame bridge backing this desktop instance. Exposed for M6c2's
     * pick/input routing installer.
     */
    public CellFrameBridge getBridge() {
        return bridge;
    }

    /**
     * The world container (the Component3D root). Exposed for mounting under
     * the desktop's Locale at bootstrap time.
     */
    public WonderlandWorldContainer getWorldContainer() {
        return worldContainer;
    }
}
