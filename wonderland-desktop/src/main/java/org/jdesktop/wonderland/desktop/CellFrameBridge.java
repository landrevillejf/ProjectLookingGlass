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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
     * M6c2: the cells explicitly hidden through {@link #setCellVisible}. A cell
     * absent from here is shown -- hiding is the exception, not the rule, so a
     * freshly mounted cell needs no bookkeeping. Holding the state is what turns
     * visibility from a fire-and-forget write into a fact the rest of the bridge
     * can be asked about: the pick router consults {@link #isVisible} (the bridge
     * is the only object that knows what is really in the scene graph), and a
     * remount re-applies the hide to the fresh branch.
     */
    private final Map<String, Boolean> visibility = new HashMap<>();

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
        // M6c2: a reload paints a brand-new scene graph, so a hide that outlived
        // the unload is written to the fresh branch; the default shown state
        // needs no call and leaves the mounter's log untouched.
        if (Boolean.FALSE.equals(visibility.get(cellId))) {
            mounter.setVisible(cellId, false);
        }
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
        // The hide record deliberately survives an unload: a server reload of
        // the same cell paints a new scene graph, and a cell the user hid must
        // not pop back into view because the world happened to churn. Teardown
        // of the whole world drops the records instead (see unmountAll).
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
     * Set the visibility of an already-mounted cell. A write that does not
     * change the state is recorded but not forwarded: the scene-graph Switch is
     * already in that position, and re-writing it on every server tick would
     * dirty a live node for nothing.
     *
     * @param cellId   the cell identifier
     * @param visible  true to show, false to hide
     */
    public void setCellVisible(String cellId, boolean visible) {
        if (cellId == null || !mounted.contains(cellId)) {
            return;
        }
        // The map holds exactly the hidden cells (a hide stores FALSE, showing
        // removes the entry), so `previous` is only ever null (shown) or FALSE
        // (hidden), never TRUE. "Was visible" is therefore just the absence of a
        // hide record; the old `|| previous.booleanValue()` arm tested a value
        // the map cannot hold, and an unreachable branch is not coverage.
        Boolean previous = visibility.get(cellId);
        boolean wasVisible = previous == null;
        if (wasVisible == visible) {
            return;
        }
        // Shown is the default, so it is stored as the absence of an entry --
        // the map then holds exactly the hidden cells.
        if (visible) {
            visibility.remove(cellId);
        } else {
            visibility.put(cellId, Boolean.FALSE);
        }
        mounter.setVisible(cellId, visible);
    }

    /**
     * M6c2: whether a mounted cell is currently shown. An unmounted cell is
     * never visible -- it is not in the scene graph to be picked.
     *
     * @param cellId the cell to ask about
     * @return true if the cell is mounted and not hidden
     */
    public boolean isVisible(String cellId) {
        if (cellId == null || !mounted.contains(cellId)) {
            return false;
        }
        return !Boolean.FALSE.equals(visibility.get(cellId));
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
     * is being removed from the desktop. The visibility records go with it: this
     * is the world ending, not one cell reloading, so nothing may come back
     * hidden.
     */
    public void unmountAll() {
        for (String cellId : new LinkedHashSet<>(mounted)) {
            unmountCell(cellId);
        }
        visibility.clear();
    }
}
