/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P1.1: one thing a walker must not pass through.
 *
 * The port inherited the 2006 client's collision as a message:
 * MovableAvatarComponent folded a (height, boolean) pair back into the avatar
 * cell, but nothing in the tree ever *decided* that boolean -- the walker's
 * move request was accepted wherever it asked to go. This record is the
 * decision made data: the horizontal footprint a cell presents to the world,
 * its vertical band, and nothing else. Keeping it pure (floats and a name) is
 * what lets the overlap math be unit-tested headless; who populates the boxes
 * is a live concern, wired where the cell bounds already exist.
 *
 * A footprint the world never measured is not an obstacle: the caller hands
 * only what it knows (the authored footprint for a model cell, the decoded
 * geometry's extent where that was measured), so an unmeasured cell lets the
 * walker through rather than building an invisible wall nobody authored.
 */
package org.jdesktop.wonderland.client.collision;

/** One cell's blocking volume: an axis-aligned box in world metres. */
public record Obstacle(String id, float minX, float maxX,
        float minY, float maxY, float minZ, float maxZ) {

    /** A box from its centre and half-extents -- the shape every bounds measurement makes. */
    public static Obstacle of(String id, float centerX, float centerY, float centerZ,
            float halfX, float halfY, float halfZ) {
        return new Obstacle(id, centerX - halfX, centerX + halfX,
                centerY - halfY, centerY + halfY,
                centerZ - halfZ, centerZ + halfZ);
    }

    /** Whether this band reaches high enough to block a walker standing at {@code feetY}. */
    public boolean blocksHeight(float feetY, float headY) {
        return minY < headY && maxY > feetY;
    }

    /**
     * Whether a walker-disc at (x, z) overlaps this box's footprint. The box is
     * grown by the disc's radius rather than the disc tested against it: a
     * circle-vs-AABB center test done this way is one rectangle overlap, and the
     * error it makes (blocking a corner slightly early) is in the walker's favor
     * -- noses do not enter walls.
     */
    public boolean overlaps(float x, float z, float radius) {
        return x > minX - radius && x < maxX + radius
                && z > minZ - radius && z < maxZ + radius;
    }
}
