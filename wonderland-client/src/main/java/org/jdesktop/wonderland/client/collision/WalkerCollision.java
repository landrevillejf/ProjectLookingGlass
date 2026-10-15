/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P1.1: the collision decision the stub never made.
 *
 * One step of walking, asked of a list of boxes: may the walker go from here
 * to there, and if not, where does the step actually land? The 2006 client
 * carried collision as a (height, boolean) message with no decider behind it
 * (docs/wonderland-roadmap.md, "Current state"); this kernel is that decider,
 * pure and headless-testable, exactly the shape the roadmap reframed: the
 * horizontal separation math needs no render context, only boxes the caller
 * already measured.
 *
 * The resolution is per axis, in x-then-z order, so a step that would hit a
 * wall slides along it instead of stopping dead -- the difference between a
 * doorway you can brush past and a corridor that throws you out. The order is
 * part of the contract: sliding along the wall you were walking beside is what
 * the keyboard-walker expects, and a test that swapped the axes would show the
 * wall as sticky on one side only.
 */
package org.jdesktop.wonderland.client.collision;

import java.util.Collection;

/** The pure walk-step resolution over {@link Obstacle} boxes. */
public final class WalkerCollision {

    /** A walker's default shoulder radius in metres: a person, not a pawn tile. */
    public static final float WALKER_RADIUS_M = 0.35f;

    /** A walker's default height in metres, from their feet upward. */
    public static final float WALKER_HEIGHT_M = 1.7f;

    private WalkerCollision() {
    }

    /**
     * Where one step lands. {@code blocked} says at least one axis was refused
     * (the truth the old stub message wanted to carry), and {@code hitId} names
     * the first obstacle that refused it -- the zone a behaviour script (P5)
     * can be told about.
     */
    public record Resolution(float x, float z, boolean blocked, String hitId) {

        /** The unchanged answer: an empty world blocks nobody. */
        public static Resolution free(float x, float z) {
            return new Resolution(x, z, false, null);
        }
    }

    /**
     * Resolve a step from (fromX, fromZ) toward (toX, toZ). An empty world is
     * the caller's own position, untouched -- so registering no obstacles keeps
     * the pre-P1.1 behaviour exactly, the same additive guarantee every phase
     * of this roadmap ships with.
     *
     * The step is SWEPT, not point-tested: the whole segment is judged, so a
     * fast move can no longer tunnel through a thin wall by asking to land on
     * its far side (the endpoint alone reads "clear"). Each axis is swept in
     * x-then-z order against the surviving other axis, so a step into a wall
     * slides along it and stops at the face rather than stopping dead or passing
     * through.
     *
     * @param radius the walker's disc radius; the boxes grow by it (see {@link Obstacle})
     * @param feetY  the walker's floor height; a band that misses it is furniture to step over
     * @param headY  the walker's head height
     */
    public static Resolution resolve(float fromX, float fromZ, float toX, float toZ,
            Collection<Obstacle> world, float radius, float feetY, float headY) {
        if (world == null || world.isEmpty()) {
            return Resolution.free(toX, toZ);
        }
        boolean blocked = false;
        String hitId = null;
        // Sweep x along the old z; the answer is the furthest legal x reached.
        float x = toX;
        Sweep sx = sweep(fromX, toX, fromZ, world, radius, feetY, headY, true);
        if (sx.blocked) {
            x = sx.landed;
            blocked = true;
            hitId = sx.hitId;
        }
        // Sweep z along whatever x survived, so a diagonal step still reaches the
        // wall it can touch without crossing it.
        float z = toZ;
        Sweep sz = sweep(fromZ, toZ, x, world, radius, feetY, headY, false);
        if (sz.blocked) {
            z = sz.landed;
            blocked = true;
            if (hitId == null) {
                hitId = sz.hitId;
            }
        }
        return new Resolution(x, z, blocked, hitId);
    }

    /** One axis's swept answer: did the segment hit, where it lands, what it hit. */
    private static final class Sweep {
        boolean blocked;
        float landed;
        String hitId;
    }

    /**
     * Sweep the moving coordinate from {@code from} to {@code to} while the fixed
     * coordinate is {@code cross}. When {@code alongX} the moving axis is x and
     * the boxes' z-range is the cross; otherwise the reverse. The move is refused
     * at the first grown box face the segment crosses, clamped to the face
     * nearest {@code from} so the walker lands tangent to it, never inside.
     */
    private static Sweep sweep(float from, float to, float cross, Collection<Obstacle> world,
            float radius, float feetY, float headY, boolean alongX) {
        Sweep s = new Sweep();
        s.landed = to;
        float nearestLo = Float.POSITIVE_INFINITY; // +axis travel: smallest near-face ahead
        float nearestHi = Float.NEGATIVE_INFINITY; // -axis travel: largest far-face behind
        String loHit = null;
        String hiHit = null;
        for (Obstacle o : world) {
            if (o == null || !o.blocksHeight(feetY, headY)) {
                continue;
            }
            float crossMin = alongX ? o.minZ() - radius : o.minX() - radius;
            float crossMax = alongX ? o.maxZ() + radius : o.maxX() + radius;
            if (!(cross > crossMin && cross < crossMax)) {
                continue; // this box is not in the lane we are sweeping down
            }
            float bLo = alongX ? o.minX() - radius : o.minZ() - radius;
            float bHi = alongX ? o.maxX() + radius : o.maxZ() + radius;
            // A `from` already inside the grown box is left free, so a walker
            // that somehow began overlapping can step out instead of being welded.
            if (from > bLo && from < bHi) {
                continue;
            }
            float segLo = Math.min(from, to);
            float segHi = Math.max(from, to);
            if (segHi > bLo && segLo < bHi) { // segment crosses the open interval
                // The FIRST face met is the one that stops the walker: forward
                // travel keeps the smallest near-face, backward the largest
                // far-face. A lane of two walls then refuses at the near one --
                // stopping at the far wall's face would be a tunnel through
                // the first, the very defect the sweep exists to kill.
                if (to > from && bLo < nearestLo) {
                    nearestLo = bLo;
                    loHit = o.id();
                } else if (to < from && bHi > nearestHi) {
                    nearestHi = bHi;
                    hiHit = o.id();
                }
            }
        }
        if (to > from && loHit != null) {
            s.blocked = true;
            s.landed = nearestLo;
            s.hitId = loHit;
        } else if (to < from && hiHit != null) {
            s.blocked = true;
            s.landed = nearestHi;
            s.hitId = hiHit;
        }
        return s;
    }

    /** Whether a standing pose overlaps nothing that reaches this high. */
    public static boolean clear(float x, float z, Collection<Obstacle> world,
            float radius, float feetY, float headY) {
        if (world == null) {
            return true;
        }
        for (Obstacle cell : world) {
            if (cell != null && cell.blocksHeight(feetY, headY)
                    && cell.overlaps(x, z, radius)) {
                return false;
            }
        }
        return true;
    }
}
