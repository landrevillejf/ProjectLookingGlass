/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P1.1: the stateful adapter between a move request and the
 * pure {@link WalkerCollision} kernel.
 *
 * The kernel is a function of (from, to, boxes) and stays pure for the headless
 * test. But the client's move path -- ViewCell.localMoveRequest -- is handed
 * only the *target* transform, never the walker's current position, and a
 * per-axis slide needs both. This class is the missing memory: it holds where
 * the walker legally stands, resolves the new target against the registered
 * obstacles, and rewrites the target's horizontal coordinates with the answer
 * while leaving the y (the walk stays on its floor) and the rotation untouched.
 *
 * It is deliberately a collaborator that the host may choose not to give the
 * view cell: with no obstacles registered it hands every request straight
 * through, which is exactly the pre-P1.1 behaviour, so wiring it in cannot
 * regress a world that has not authored collision yet.
 */
package org.jdesktop.wonderland.client.collision;

import com.jme.math.Vector3f;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jdesktop.wonderland.common.cell.CellTransform;

/** Remembers a walker's legal position and clamps each requested move to it. */
public final class AvatarCollider {

    private final List<Obstacle> obstacles = new ArrayList<>();
    private final float radius;
    private final float feetY;
    private final float headY;
    private float curX;
    private float curZ;

    /** A collision-free collider seeded at the walker's start -- the pass-through default. */
    public AvatarCollider(float startX, float startZ) {
        this(startX, startZ, WalkerCollision.WALKER_RADIUS_M, 0f, WalkerCollision.WALKER_HEIGHT_M);
    }

    public AvatarCollider(float startX, float startZ, float radius, float feetY, float headY) {
        this.curX = startX;
        this.curZ = startZ;
        this.radius = radius;
        this.feetY = feetY;
        this.headY = headY;
    }

    /** Register the world's blocking volumes; replaces anything registered before. */
    public void setObstacles(Collection<Obstacle> world) {
        obstacles.clear();
        if (world != null) {
            obstacles.addAll(world);
        }
    }

    public boolean isEmpty() {
        return obstacles.isEmpty();
    }

    /** Where the collider believes the walker currently stands. */
    public float x() {
        return curX;
    }

    public float z() {
        return curZ;
    }

    /**
     * Resolve {@code requested} against the obstacles and land the walker in the
     * position the collision answer allows. Only the horizontal coordinates are
     * ever rewritten -- the requested rotation and the y (the walk stays on its
     * floor) are preserved. The rewrite is applied to the passed transform, so
     * the caller forwards the very object it handed in. The resolved position is
     * also remembered as the new current position, so the next step slides from
     * there.
     *
     * @return the last obstacle that refused part of the move, or null if none did
     */
    public String clamp(CellTransform requested) {
        Vector3f t = requested.getTranslation(null);
        if (t == null) {
            return null;
        }
        float toX = t.x;
        float toZ = t.z;
        if (obstacles.isEmpty()) {
            curX = toX;
            curZ = toZ;
            return null;
        }
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(curX, curZ, toX, toZ, obstacles, radius, feetY, headY);
        curX = r.x();
        curZ = r.z();
        if (r.x() != toX || r.z() != toZ) {
            Vector3f clamped = new Vector3f(r.x(), t.y, r.z());
            requested.setTranslation(clamped);
        }
        return r.hitId();
    }
}
