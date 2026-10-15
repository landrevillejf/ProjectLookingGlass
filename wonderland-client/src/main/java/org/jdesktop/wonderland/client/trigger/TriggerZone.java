/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P1.2: one behaviour zone.
 *
 * Where an {@link org.jdesktop.wonderland.client.collision.Obstacle} says "do
 * not enter", a trigger zone says "when you cross my boundary, tell someone".
 * The 2006 client already had the concept -- AvatarActionTrigger / the trigger
 * int on MovableAvatarComponent -- but, like collision, nothing in the port
 * decided the transition; the walk-in never fired it. This is the zone geometry
 * and nothing else, so the enter/exit decision stays a pure function of two
 * positions and can be proven headless.
 *
 * A zone is a horizontal footprint with an optional vertical band, exactly the
 * shape the phase asks for: the doorway of the clinic (a thin box the walker
 * passes through), a podium (a flat pad you step onto). An unset band means the
 * zone reaches from floor to sky, so a walker on a staircase still triggers it.
 */
package org.jdesktop.wonderland.client.trigger;

/** One trigger volume: an id, a horizontal footprint and a vertical band. */
public record TriggerZone(String id, float minX, float maxX,
        float minZ, float maxZ, float minY, float maxY) {

    /** A zone from centre and half-extents; the band is floor (-inf) to sky (+inf). */
    public static TriggerZone open(String id, float centerX, float centerZ,
            float halfX, float halfZ) {
        return new TriggerZone(id, centerX - halfX, centerX + halfX,
                centerZ - halfZ, centerZ + halfZ,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
    }

    /** A zone from centre and half-extents with a real vertical band. */
    public static TriggerZone of(String id, float centerX, float centerY, float centerZ,
            float halfX, float halfY, float halfZ) {
        return new TriggerZone(id, centerX - halfX, centerX + halfX,
                centerZ - halfZ, centerZ + halfZ, centerY - halfY, centerY + halfY);
    }

    /** Whether the pose (x, y, z) is inside this zone's volume. */
    public boolean contains(float x, float y, float z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
    }
}
