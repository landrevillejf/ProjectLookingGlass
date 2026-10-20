/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: the model behind the desktop's world HUD.
 *
 * The HUD answers one question -- "what is in this world, and what of it is
 * actually in front of me" -- and it can only answer it by joining two truths
 * that arrive from different places: the server's cell inventory (the world as
 * authored) and the bridge's mounted set (the world as scene graphs). Joining
 * them here, in pure code, is what lets the same rows drive the 3D SwingNode HUD
 * and the 2D MDI panel, and lets CI assert the join -- including the awkward
 * cases (a mounted cell the inventory never mentioned, a parent chain that loops,
 * a cell with no name) that a hand-checked screenshot would not.
 */
package org.jdesktop.wonderland.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges a {@link WorldInventory} with the {@link CellFrameBridge}'s mount and
 * visibility state into one ordered list of rows.
 */
public final class WorldHudModel {

    /** Where a cell stands between "authored" and "on screen". */
    public enum CellState {
        /** In the scene graph and shown. */
        MOUNTED,
        /** In the scene graph and hidden. */
        HIDDEN,
        /** Published by the world, not loaded into it. */
        PENDING
    }

    /**
     * One HUD line.
     *
     * @param cellId    the cell id
     * @param label     what the HUD prints (name, else class, else id)
     * @param className the registered cell class, or null when unknown
     * @param depth     how many ancestors the cell has (a root is 0)
     * @param state     the joined mount state
     */
    public record Row(String cellId, String label, String className, int depth,
            CellState state) {
    }

    private final CellFrameBridge bridge;
    private volatile WorldInventory inventory;

    /**
     * @param bridge the mount / visibility truth; must not be null
     */
    public WorldHudModel(CellFrameBridge bridge) {
        this(bridge, null);
    }

    /**
     * @param bridge    the mount / visibility truth; must not be null
     * @param inventory the world's published cells, or null until one arrives
     */
    public WorldHudModel(CellFrameBridge bridge, WorldInventory inventory) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        this.bridge = bridge;
        this.inventory = inventory;
    }

    /**
     * Replaces the published inventory (a reload, or the first read after the
     * panel connects). Null clears it, which leaves the HUD showing only what is
     * actually mounted.
     */
    public void setInventory(WorldInventory inventory) {
        this.inventory = inventory;
    }

    /** The inventory the rows were last built from, or null. */
    public WorldInventory getInventory() {
        return inventory;
    }

    /**
     * The HUD rows: the published inventory in publication order, then any
     * mounted cell the inventory never listed. A mounted cell is never missing
     * from the HUD, because that would hide scene-graph state the user is
     * looking at.
     *
     * @return an unmodifiable, deterministically ordered snapshot
     */
    public List<Row> rows() {
        WorldInventory current = inventory;
        List<WorldInventory.Cell> published =
                (current == null) ? Collections.emptyList() : current.cells();
        if (published == null) {
            published = Collections.emptyList();
        }

        // id -> parent id, read once so the depth walk and the "already listed"
        // test agree on one snapshot of the world.
        Map<String, String> parents = new LinkedHashMap<>();
        for (WorldInventory.Cell cell : published) {
            if (cell != null && cell.id() != null) {
                parents.put(cell.id(), cell.parentId());
            }
        }
        Set<String> listed = new HashSet<>(parents.keySet());

        List<Row> out = new ArrayList<>(published.size());
        for (WorldInventory.Cell cell : published) {
            if (cell == null || cell.id() == null) {
                continue;
            }
            out.add(new Row(cell.id(), labelFor(cell), cell.className(),
                    depthOf(cell.id(), parents), stateOf(cell.id())));
        }
        for (String cellId : bridge.mountedCellIds()) {
            // mountedCellIds() never yields null -- CellFrameBridge rejects a
            // null id at mountCell -- so the only skip here is a cell the
            // inventory already listed above. The old `cellId == null ||` arm
            // guarded a state the final bridge cannot produce.
            if (listed.contains(cellId)) {
                continue;
            }
            // A cell the world did not publish but the session loaded still has
            // to appear -- the alternative is a scene graph the user sees and
            // cannot account for. The inventory has no name, class or parent for
            // it, so the id stands in as the label and it reads as a root.
            out.add(new Row(cellId, cellId, null,
                    depthOf(cellId, parents), stateOf(cellId)));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * How many rows sit in {@code state}. Computed from {@link #rows()} so the
     * summary and the list can never disagree.
     */
    public int count(CellState state) {
        int total = 0;
        for (Row row : rows()) {
            if (row.state() == state) {
                total++;
            }
        }
        return total;
    }

    /**
     * The one-line status strip the HUD prints, for example
     * {@code "4 cells: 2 shown, 1 hidden, 1 pending"}.
     *
     * @return the summary the HUD prints
     */
    public String summary() {
        List<Row> all = rows();
        return all.size() + " cells: "
                + countIn(all, CellState.MOUNTED) + " shown, "
                + countIn(all, CellState.HIDDEN) + " hidden, "
                + countIn(all, CellState.PENDING) + " pending";
    }

    private static int countIn(List<Row> rows, CellState state) {
        int total = 0;
        for (Row row : rows) {
            if (row.state() == state) {
                total++;
            }
        }
        return total;
    }

    private CellState stateOf(String cellId) {
        if (!bridge.isMounted(cellId)) {
            return CellState.PENDING;
        }
        return bridge.isVisible(cellId) ? CellState.MOUNTED : CellState.HIDDEN;
    }

    /**
     * The name the HUD prints. An empty name is not a name, so the class stands
     * in, and with neither the id is the only honest label.
     */
    private static String labelFor(WorldInventory.Cell cell) {
        String name = cell.name();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String className = cell.className();
        if (className != null && !className.isBlank()) {
            return className.trim();
        }
        return cell.id();
    }

    /**
     * Counts the ancestors of {@code cellId}. A world file that points a cell at
     * itself, or a cycle two cells deep, must not hang the HUD -- the walk keeps
     * the ids it has already seen and stops, which caps the depth at the size of
     * the inventory instead of looping forever.
     */
    private static int depthOf(String cellId, Map<String, String> parents) {
        int depth = 0;
        Set<String> seen = new HashSet<>();
        seen.add(cellId);
        String parent = parents.get(cellId);
        while (parent != null && seen.add(parent)) {
            depth++;
            parent = parents.get(parent);
        }
        return depth;
    }
}
