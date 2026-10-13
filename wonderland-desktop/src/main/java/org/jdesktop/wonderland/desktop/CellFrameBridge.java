/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c: the pure mount/unmount/transform decision logic.
 *
 * This class tracks which cells are currently mounted in the desktop scene
 * graph, guards against double-mounts, skips null scene references, and
 * forwards the HOW to an injected FrameMounter. It knows nothing about
 * Component3D, Frame3D, or AppConnector -- those live behind the seam in
 * WonderlandWorldContainer (probe-only). The logic here is fully testable
 * headless: pass a recording FrameMounter stub, drive mountCell / unmountCell
 * / updateTransform, and assert the calls arrived in the right order with the
 * right arguments.
 */
package org.jdesktop.wonderland.desktop;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.renderer.transform.WorldTransformMath;

/**
 * Manages the lifecycle of mounted cell scene-graphs inside the desktop,
 * delegating the actual scene-graph mutation to a {@link FrameMounter}.
 */
public final class CellFrameBridge {

    private final FrameMounter mounter;
    private final Set<String> mounted;

    /**
     * @param mounter the scene-graph operation target; must not be null
     */
    public CellFrameBridge(FrameMounter mounter) {
        if (mounter == null) {
            throw new IllegalArgumentException("mounter must not be null");
        }
        this.mounter = mounter;
        this.mounted = new LinkedHashSet<>();
    }

    /**
     * Mount a cell's renderer scene into the desktop. Idempotent: mounting an
     * already-mounted cell is a no-op. A null cellId or null renderer is skipped.
     *
     * @param cellId    the cell identifier
     * @param renderer  the cell's CellRenderer (the mounter extracts its BranchGroup)
     */
    public void mountCell(String cellId, CellRenderer renderer) {
        if (cellId == null || renderer == null) {
            return;
        }
        if (mounted.contains(cellId)) {
            return;
        }
        mounted.add(cellId);
        mounter.mount(cellId, renderer);
    }

    /**
     * Unmount a previously-mounted cell. Unmounting a cell that was never
     * mounted (or already unmounted) is a no-op.
     *
     * @param cellId  the cell identifier to remove
     */
    public void unmountCell(String cellId) {
        if (cellId == null) {
            return;
        }
        if (!mounted.remove(cellId)) {
            return;
        }
        mounter.unmount(cellId);
    }

    /**
     * Update the world-space transform of an already-mounted cell. Silently
     * ignored if the cell is not mounted (it may have been removed between
     * the server message and the render thread picking it up).
     *
     * @param cellId     the cell whose pose changed
     * @param transform  the new cell-space transform from the server
     */
    public void updateTransform(String cellId, CellTransform transform) {
        if (cellId == null || !mounted.contains(cellId)) {
            return;
        }
        double[] feed = WorldTransformMath.fromCellTransform(transform);
        mounter.applyTransform(cellId, feed);
    }

    /**
     * Set the visibility of an already-mounted cell.
     *
     * @param cellId   the cell identifier
     * @param visible  true to show, false to hide
     */
    public void setCellVisible(String cellId, boolean visible) {
        if (cellId == null || !mounted.contains(cellId)) {
            return;
        }
        mounter.setVisible(cellId, visible);
    }

    /**
     * Whether a cell is currently mounted.
     *
     * @param cellId  the cell to check
     * @return true if the cell's scene graph is live in the desktop
     */
    public boolean isMounted(String cellId) {
        return cellId != null && mounted.contains(cellId);
    }

    /**
     * An unmodifiable snapshot of the currently-mounted cell ids, in mount
     * order.
     */
    public Set<String> mountedCellIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(mounted));
    }

    /**
     * Unmount every cell. Used during teardown when the whole world container
     * is being removed from the desktop.
     */
    public void unmountAll() {
        for (String cellId : new LinkedHashSet<>(mounted)) {
            unmountCell(cellId);
        }
    }
}
