/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c: the seam that abstracts the lg3d scene-graph mount.
 *
 * Component3D and Frame3D cannot be constructed in a headless JVM (their
 * constructors reach through AppConnectorPrivate and instantiate Jogamp
 * BranchGroup), so the bridge's pure decision logic is tested against this
 * interface with a recording stub. The live implementation lives in
 * WonderlandWorldContainer, which extends Component3D and is probe-only.
 *
 * The mount call receives a CellRenderer (the headless interface from
 * wonderland-client); the probe-only implementation extracts the BranchGroup
 * from it via instanceof + getScene(). The bridge itself never touches a
 * Jogamp type.
 */
package org.jdesktop.wonderland.desktop;

import org.jdesktop.wonderland.client.cell.CellRenderer;

/**
 * Performs the actual scene-graph mount/unmount/transform operations on the
 * desktop's virtual universe. The {@link CellFrameBridge} calls these at the
 * right time; this interface says nothing about WHEN -- only HOW.
 */
public interface FrameMounter {

    /**
     * Mount the given cell's renderer into the desktop. The live implementation
     * extracts the renderer's BranchGroup and adds it to the scene graph.
     *
     * @param cellId    the cell's string identifier
     * @param renderer  the cell's renderer; never null at call time (the
     *                  bridge guards)
     */
    void mount(String cellId, CellRenderer renderer);

    /**
     * Unmount and detach the given cell's scene graph from the desktop.
     *
     * @param cellId  the cell to remove
     */
    void unmount(String cellId);

    /**
     * Apply a new world-space transform (row-major {@code double[16]}) to the
     * already-mounted cell.
     *
     * @param cellId        the cell whose pose changes
     * @param rowMajorFeed  16-element row-major matrix from
     *                      {@code WorldTransformMath.fromCellTransform}
     */
    void applyTransform(String cellId, double[] rowMajorFeed);

    /**
     * Set visibility on the already-mounted cell.
     *
     * @param cellId   the cell
     * @param visible  true to show, false to hide
     */
    void setVisible(String cellId, boolean visible);
}
