/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c/M6c2: the bootstrap entry point that connects the
 * wonderland client session, installs the renderer, and mounts the world into
 * the lg3d desktop.
 *
 * This class orchestrates the live path: it calls
 * WonderlandRendererBootstrap.install() to fill the AvatarRendererInstallers
 * slot, creates a WonderlandWorldContainer, puts it in its own Frame3D (the
 * desktop's top-level 3D window, the same one every native 3D app gets), and
 * drives cell lifecycle events from the client session through CellFrameBridge.
 * Not constructed in CI (its methods touch Component3D and AppConnector paths
 * that require a live desktop session); verified by the in-JVM probe.
 *
 * M6c2 added the two pieces that make the world part of the desktop rather than
 * a scene graph sitting in a JVM: show(), which hands the world to the desktop's
 * scene manager as a frame, and installInputRouting(), which connects the
 * desktop's picks to the cells that want them.
 */
package org.jdesktop.wonderland.desktop;

import java.util.function.Consumer;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jogamp.vecmath.Vector3f;
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

    /**
     * The world frame's size hint in metres: the volume the world is authored
     * in, matching the renderers' own units (1 unit = 1 m). The scene manager
     * places and scales the frame; this only tells it how big to make it.
     */
    private static final float WORLD_EXTENT = 1.2f;

    private final CellFrameBridge bridge;
    private final WonderlandWorldContainer worldContainer;
    private boolean installed;
    private Frame3D frame;
    private PickRouter pickRouter;

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
        // Say out loud which roadmap features this JVM decided to run, and read
        // it through the same class the renderer consults -- so the line cannot
        // claim something the pixels do not. A person who finds the world looks
        // wrong should be able to see which switches were read without opening
        // the source, which is the difference between a wired feature and a
        // rumor about one.
        java.util.logging.Logger.getLogger(WonderlandDesktop.class.getName())
                .info(() -> org.jdesktop.wonderland.common.features.WorldFeatures
                        .summary(System::getProperty));
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

    /**
     * M6c2: give the world to the desktop as a 3D window. A Frame3D is how every
     * native lg3d app reaches the screen -- the scene manager then owns its
     * position, its decorations and its visibility, so the world behaves like a
     * window instead of an unmanaged blob in the universe.
     *
     * <p>Idempotent: a second call does not put a second world frame on the
     * desktop.</p>
     */
    public void show() {
        if (frame != null) {
            return;
        }
        Frame3D worldFrame = new Frame3D();
        worldFrame.addChild(worldContainer);
        worldFrame.setPreferredSize(new Vector3f(WORLD_EXTENT, WORLD_EXTENT, WORLD_EXTENT));
        // changeEnabled(true) joins the frame to the scene manager, and
        // changeVisible(true) shows it; both are required, in that order.
        worldFrame.changeEnabled(true);
        worldFrame.changeVisible(true);
        frame = worldFrame;
    }

    /** The desktop's frame for the world, or null before {@link #show()}. */
    public Frame3D getFrame() {
        return frame;
    }

    /**
     * The pick / input routing layer, created on first use. Cells declare the
     * gestures they own through it; the desktop keeps everything else.
     */
    public PickRouter getPickRouter() {
        if (pickRouter == null) {
            // lg3d does the picking, so this router is fed hit lists from the
            // desktop's mouse events rather than asking for a pick itself.
            pickRouter = new PickRouter(bridge);
        }
        return pickRouter;
    }

    /**
     * M6c2: connect the desktop's picked events to the routing layer.
     *
     * @param sink where each decision goes; may be null to prove the path
     * @return the router the cells register with
     */
    public PickRouter installInputRouting(Consumer<PickDecision> sink) {
        PickRouter router = getPickRouter();
        worldContainer.addWorldListener(
                new WorldInputRouter(worldContainer, router, sink));
        return router;
    }
}
