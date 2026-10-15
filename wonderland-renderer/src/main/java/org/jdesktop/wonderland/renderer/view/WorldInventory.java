/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland roadmap P1.1 and P0.2, the input neither of them had.
 *
 * Both kernels were written, tested and left uncalled for the same structural
 * reason: each one consumes a fact the live path never produced. WalkerCollision
 * and AvatarCollider need OBSTACLES, WorldPVS and OctantCuller need registered
 * PLACEMENTS -- and nothing between "the Collada file loaded" and "the frame drew"
 * ever said where a cell sits or how big it is. WorldScene knew: it walks every
 * Entry, reads its position, yaw, scale and footprint, and then threw all of it
 * away after hanging the geometry. This class is what it stops throwing away.
 *
 * It is pure -- float arithmetic and records, no Java 3D -- for the standing rule
 * of this module (decision classes are tested headless, Jogamp assembly is
 * probe-only), and it is deliberately built from the numbers WorldScene already
 * trusts rather than from a scene-graph bounds read, which needs capabilities on
 * live nodes and returns nothing useful on a detached branch.
 *
 * What a footprint of 0 means is decided here once: an art file that declares no
 * footprint gets the model's own horizontal extent, and if that is unknown too it
 * gets CELL_FALLBACK_FOOTPRINT_M -- a room-sized volume, not a zero. A zero radius
 * would register a cell that can never be seen (PVS answers "nothing is visible")
 * and, worse, would make a walker walk through every wall in the world. Guessing
 * big costs a little freedom of movement; guessing zero costs the feature.
 */
package org.jdesktop.wonderland.renderer.view;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.collision.AvatarCollider;
import org.jdesktop.wonderland.client.collision.Obstacle;
import org.jdesktop.wonderland.client.cull.WorldPVS;

/**
 * The placed cells of one world as arithmetic: names, positions and sizes, with
 * the two questions the collision and culling kernels ask already answered.
 */
public final class WorldInventory {

    /**
     * Half-extent given to a cell that declares no footprint and whose model
     * reported no extent: two metres, a small room. See the class comment for
     * why a silent zero is the wrong default.
     */
    public static final float CELL_FALLBACK_FOOTPRINT_M = 2.0f;

    /**
     * How tall a cell blocks a walker. A footprint is a horizontal measure, and
     * the worlds this was authored for are single-storey rooms whose walls are
     * taller than a person; a two-metre-high volume means a walker cannot
     * step over a wall by standing on the floor next to it.
     */
    public static final float CELL_BLOCKING_HEIGHT_M = 2.0f;

    /**
     * One placed cell. Yaw and scale are already folded into the footprint by
     * the caller that built this (WorldScene's own placement math), so the box
     * here is axis-aligned -- what WalkerCollision and WorldPVS both want, and
     * what a rotated obstacle would need and does not have.
     *
     * @param name     the cell's name, the id the PVS answers with and the collider
     *                 reports as the thing that refused a move
     * @param x        centre x in world metres
     * @param y        centre y in world metres (the floor line of the volume)
     * @param z        centre z in world metres
     * @param footprint horizontal half-extent in metres, always positive
     * @param height   vertical half-extent in metres, always positive
     */
    public record Placement(String name, float x, float y, float z,
            float footprint, float height) {

        public Placement {
            if (name == null || name.isBlank()) {
                name = "unnamed";
            }
            if (!isSane(footprint) || footprint <= 0f) {
                footprint = CELL_FALLBACK_FOOTPRINT_M;
            }
            if (!isSane(height) || height <= 0f) {
                height = CELL_BLOCKING_HEIGHT_M;
            }
            if (!isSane(x) || !isSane(y) || !isSane(z)) {
                x = y = z = 0f;
            }
        }

        private static boolean isSane(float v) {
            return !Float.isNaN(v) && !Float.isInfinite(v);
        }
    }

    private final List<Placement> placements;

    public WorldInventory(List<Placement> placements) {
        this.placements = placements == null
                ? List.of() : List.copyOf(placements);
    }

    /** An inventory of nothing; the identity every degenerate world falls back to. */
    public static WorldInventory empty() {
        return new WorldInventory(List.of());
    }

    public List<Placement> placements() {
        return placements;
    }

    public int size() {
        return placements.size();
    }

    /**
     * The blocking volumes, one per placed cell, in the vocabulary the collision
     * kernel takes. The y is the centre of the blocking box, so a volume's lower
     * edge sits at the cell's floor line minus half its blocking height.
     */
    public List<Obstacle> obstacles() {
        List<Obstacle> out = new ArrayList<>(placements.size());
        for (Placement p : placements) {
            out.add(Obstacle.of(p.name(), p.x(), p.y(), p.z(),
                    p.footprint(), p.height(), p.footprint()));
        }
        return out;
    }

    /**
     * A collider seeded where the walker stands, loaded with this world's walls.
     *
     * <p>This is the whole of P1.1's wiring: the kernel was written long before
     * anything could hand it a world. The radius and height are the walker's own
     * (the collider's defaults, a human shoulder-broad and door-frame tall), so
     * a cell's volume blocks a person rather than a projectile.</p>
     *
     * @param startX walker x
     * @param startZ walker z
     * @return a live collider; empty worlds give one that lets everything through
     */
    public AvatarCollider colliderAt(float startX, float startZ) {
        AvatarCollider collider = new AvatarCollider(startX, startZ);
        collider.setObstacles(obstacles());
        return collider;
    }

    /**
     * The potential-visible-set engine for this world, every cell registered.
     *
     * @param cellSize the grid step the kernel buckets by; a world's own reach is
     *                 the honest denominator, since a grid finer than the largest
     *                 cell answers "what is near me" by checking almost everything
     * @param viewRadius how far a person can see in this world, in metres
     * @return a populated PVS, ready for {@code visibleFrom(eye)}
     */
    public WorldPVS pvs(float cellSize, float viewRadius) {
        WorldPVS pvs = new WorldPVS(cellSize > 0f ? cellSize : reach(), viewRadius);
        for (Placement p : placements) {
            pvs.register(p.name(), p.x(), p.y(), p.z(), p.footprint());
        }
        return pvs;
    }

    /** The distance from the middle of everything to the furthest cell. */
    public float reach() {
        if (placements.isEmpty()) {
            return 1f;
        }
        float cx = 0f;
        float cz = 0f;
        for (Placement p : placements) {
            cx += p.x();
            cz += p.z();
        }
        cx /= placements.size();
        cz /= placements.size();
        float far = 0f;
        for (Placement p : placements) {
            float dx = p.x() - cx;
            float dz = p.z() - cz;
            float d = (float) Math.sqrt(dx * dx + dz * dz) + p.footprint();
            if (d > far) {
                far = d;
            }
        }
        return far > 0f ? far : 1f;
    }
}
