/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P2.4: the PVS wired as the world's occlusion answer.
 *
 * Roadmap P0.2 built the kernel -- an octant grid that answers "what is near
 * this eye" -- and deliberately stopped at the answer, because a culler that
 * nothing consults culls nothing. P2.4 is the consultation. This class is the
 * small stateful owner a live shell hangs on: cells register as they attach
 * and unregister as they detach, a frame asks {@code visibleFrom} once with
 * the camera's own position, and everything not in the answer is a cell whose
 * scene graph the frame does not need to touch -- the same PotentialVisibleSet
 * P0.2 measured, now with a lifecycle.
 *
 * It is the occlusion half of P2.4's "occlusion via the PVS from P0.2". The
 * conservative promise is kept on purpose: a bounding sphere around each cell
 * means a cell whose sphere clips the eye radius stays visible even when a
 * wall is between it and the eye -- real depth occlusion needs the shadow-map
 * pipeline the roadmap lists as the upgrade, and a culler that SOMETIMES keeps
 * a hidden cell is a frame-rate cost, while one that sometimes DROPS a visible
 * one is a bug that eats the world. Every judgement here is pure bookkeeping
 * and runs headless.
 */
package org.jdesktop.wonderland.client.cull;

import java.util.LinkedHashMap;
import java.util.Map;

/** The lifecycle owner around one {@link OctantCuller} (roadmap P2.4). */
public final class WorldPVS {

    /** How far a cell's bounding sphere pads its own radius: a frame of slack. */
    private static final float SLACK_M = 2.0f;

    private final OctantCuller culler;
    private final float viewRadius;

    /** The cells the world holds, each exactly as the kernel is pointed at it. */
    private final Map<String, Placement> placements = new LinkedHashMap<>();

    /** One cell's registration as the kernel sees it, padded name included. */
    private record Placement(String id, float x, float y, float z, float radius) {
    }

    /**
     * @param cellSize   the octant edge length handed to the kernel (metres;
     *                   the same number a world's cells are spaced by)
     * @param viewRadius how far from the eye a cell still counts as near enough
     *                   to draw -- a world's diagonal is a honest default
     */
    public WorldPVS(float cellSize, float viewRadius) {
        this.culler = new OctantCuller(cellSize);
        this.viewRadius = Math.max(0f, viewRadius);
    }

    /**
     * Attach a cell. A re-registration of a name already placed REPLACES it
     * (the 2006 client could re-attach a moved cell without an explicit
     * detach), so the map cannot grow a ghost per teleport -- and because the
     * kernel answers "registering the same id twice adds two volumes", the
     * mirror is rebuilt from the map on every change. Registration is a
     * world-load-frequency event and the rebuild is one pass over the cells;
     * a frame never pays for it, and {@link #size} then means exactly what it
     * says: the cells the world holds, each counted once.
     */
    public void register(String id, float x, float y, float z, float radius) {
        if (id == null) {
            throw new NullPointerException("a cullable cell must be named");
        }
        placements.put(id, new Placement(id, x, y, z, Math.max(0f, radius) + SLACK_M));
        rebuildKernel();
    }

    /** Detach a cell; an unknown name is a no-op, the way a frame sees it. */
    public void unregister(String id) {
        if (placements.remove(id) != null) {
            rebuildKernel();
        }
    }

    /** Repoint the kernel at exactly the tracked cells, each counted once. */
    private void rebuildKernel() {
        culler.clear();
        placements.values().forEach(p ->
                culler.register(p.id(), p.x(), p.y(), p.z(), p.radius()));
    }

    /** How many cells the world currently holds. */
    public int size() {
        return placements.size();
    }

    /**
     * The cells a frame at this eye should consider: everything whose padded
     * sphere reaches the view radius (see the conservative promise above).
     */
    public PotentialVisibleSet visibleFrom(float eyeX, float eyeY, float eyeZ) {
        return culler.query(eyeX, eyeY, eyeZ, viewRadius);
    }

    /**
     * The shell's per-frame shorthand: true when a named cell belongs in the
     * draw set. An unregistered cell answers false -- a frame cannot be asked
     * to draw what the world no longer holds.
     */
    public boolean isVisible(String id, float eyeX, float eyeY, float eyeZ) {
        return visibleFrom(eyeX, eyeY, eyeZ).contains(id);
    }
}
