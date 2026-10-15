/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.4: the shadow a light direction actually casts.
 *
 * ShadowArt draws the pool every figure has sat in since M8: a radial blob,
 * because a light straight overhead puts a shadow directly under a person and
 * that is the one shape a textureless port could fake honestly. A rig with a
 * DIRECTIONAL light (P0.1's) does not -- its shadows fall away from the light,
 * longer the lower the sun and the taller the caster -- and a round pool under
 * a noon building is the tell that breaks the scene. This class is the
 * projector maths that replaces the fake: pure arithmetic on the same box the
 * blob used, deciding where each shadow lands, how long it runs and which way
 * it points, so the Java 3D seat (a translate plus a yaw on the existing
 * textured quad) is the only probe-side code left.
 *
 * It is a decal projector, not a shadow MAP: one quad per caster, projected
 * flat onto the floor. The roadmap lists the map as the upgrade this replaces
 * when a depth-texture pipeline exists; until then this is the honest shape
 * of "real shadows", and every number in it is testable with no display.
 */
package org.jdesktop.wonderland.renderer.view;

/** Where one caster's shadow falls under a directional light (roadmap P2.4). */
public final class ShadowProjector {

    /** How far a shadow's stretch may run, in caster-footprint lengths. */
    private static final double MAX_STRETCH = 6.0;

    /** A sun this low is effectively horizon light; the cap keeps pools honest. */
    private static final double MIN_SIN_ELEVATION = 1.0 / MAX_STRETCH;

    private ShadowProjector() {
    }

    /**
     * One caster's projected shadow: its ground centre, its two half-extents
     * (the along-light one already stretched), and the yaw in degrees that
     * turns a floor-aligned quad so its Z axis runs ALONG the light -- the
     * only rotation the seat transform needs.
     */
    public record ProjectedShadow(double centerX, double centerY, double centerZ,
            double halfFootX, double halfFootZ, double halfLengthZ, double yawDeg) {

        /** The centre as a plain triple, for a Transform3D.setTranslation. */
        public double[] translation() {
            return new double[] {centerX, centerY, centerZ};
        }

        /** The two horizontal half-extents, {across-light, along-light}. */
        public double[] halves() {
            return new double[] {halfFootX, halfFootZ, halfLengthZ};
        }
    }

    /**
     * Project one box's footprint along the light.
     *
     * <p>The model is the textbook parallel projection: a point at height
     * {@code h} above the floor, lit by a ray pointing {@code (lx, ly, lz)}
     * (the direction light TRAVELS, any length, {@code ly < 0} being a sun),
     * lands where the ray meets {@code y = floor}: offset {@code h * (lx, lz)
     * / -ly}. The pool lies OPPOSITE the side the light comes from, in the
     * direction it travels: a sun in the +X sky (a ray heading -X) puts the
     * shadow at -X. The centre is the caster's centre pushed half that
     * span, and its length along the light is the footprint scaled by how far
     * the TOP of the box travels relative to its feet -- a box, not a point,
     * and the difference between a leaning smear and a block's shadow. Elevation is
     * clamped so a horizon "sun" cannot cast a shadow across the world, and a
     * light with no horizontal run (straight down, or none at all) returns
     * null: the caller keeps the round pool, which for overhead light is not
     * a fallback but the physically right answer.</p>
     *
     * @param lightX the light ray's X component (direction it travels)
     * @param lightY its Y component; negative is a sun, positive is a floor lamp
     * @param lightZ its Z component
     * @param centerX the caster's centre X
     * @param footY the floor the shadow lies on (the caster's bottom)
     * @param centerZ the caster's centre Z
     * @param height the caster's full height above the floor
     * @param halfX the footprint's X half-extent
     * @param halfZ the footprint's Z half-extent
     * @return the projection, or null when the light is vertical or absent
     */
    public static ProjectedShadow project(double lightX, double lightY, double lightZ,
            double centerX, double footY, double centerZ,
            double height, double halfX, double halfZ) {
        double horizontal = Math.sqrt(lightX * lightX + lightZ * lightZ);
        double down = -lightY; // positive for a sun
        if (horizontal < 1e-9 || down < 1e-9 || height <= 0) {
            return null; // no run, an uplight, or a flat thing: the round pool is right
        }
        // sin of the elevation: the ratio that sets the length. Clamped low so
        // a horizon sun stretches a shadow MAX_STRETCH footprints and no more.
        double sinElevation = down / Math.sqrt(horizontal * horizontal + down * down);
        double stretch = Math.min(1.0 / Math.max(sinElevation, MIN_SIN_ELEVATION),
                MAX_STRETCH);
        // the centre drifts by the box's own height travelled along the ray
        double shift = height * (1.0 / Math.max(sinElevation, MIN_SIN_ELEVATION));
        shift = Math.min(shift, height * MAX_STRETCH);
        double unitX = lightX / horizontal;
        double unitZ = lightZ / horizontal;
        double groundY = footY; // pools lie on the floor they were collected with

        // The yaw turns +Z onto the light's run direction, so halfLengthZ is
        // "along the light". atan2 of the run vector, degrees, Java 3D's sign.
        double yawDeg = Math.toDegrees(Math.atan2(unitX, unitZ));
        // the shadow lies along the way the light Travels: feet stay put, the
        // top travels shift along (unitX, unitZ), and the pool's centre is
        // midway along that span -- a ray heading -X puts the shadow at -X
        double alongX = centerX + unitX * shift * 0.5;
        double alongZ = centerZ + unitZ * shift * 0.5;

        return new ProjectedShadow(alongX, groundY, alongZ,
                halfX, halfZ, halfZ * stretch, yawDeg);
    }
}
