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

    /**
     * How far a shadow's run may extend, in caster heights. The cap is what keeps
     * a horizon "sun" from bandaging the campus: a 22-degree light runs 2.5
     * heights and never touches it, so the cap only bites below ~9.5 degrees
     * elevation, where a shadow that long would cross the room its neighbours
     * stand in.
     */
    private static final double MAX_RUN_HEIGHTS = 6.0;

    private ShadowProjector() {
    }

    /**
     * One caster's projected shadow: its ground centre, its two half-extents
     * (the along-light one already carrying the projected top), and the yaw in
     * degrees that turns a floor-aligned quad so its Z axis runs ALONG the
     * light -- the only rotation the seat transform needs.
     *
     * @param centerX the pool's centre X, on the floor
     * @param centerY the floor the pool lies on (the caster's own foot plane)
     * @param centerZ the pool's centre Z, on the floor
     * @param halfAcross the footprint's half-extent ACROSS the light, so a sun
     *                   running along X does not borrow the caster's Z width
     * @param halfFootAlong the footprint's half-extent along the light -- the
     *                      base the shadow is measured from
     * @param halfLengthAlong the whole pool's half-extent along the light, from
     *                        the near edge of the feet to the far edge of the
     *                        projected top; this is the number that makes the
     *                        shadow start AT the caster instead of near it
     * @param yawDeg the turn that puts a quad's +Z onto the light's run
     */
    public record ProjectedShadow(double centerX, double centerY, double centerZ,
            double halfAcross, double halfFootAlong, double halfLengthAlong,
            double yawDeg) {

        /** The centre as a plain triple, for a Transform3D.setTranslation. */
        public double[] translation() {
            return new double[] {centerX, centerY, centerZ};
        }

        /** The two horizontal half-extents, {across-light, along-light}. */
        public double[] halves() {
            return new double[] {halfAcross, halfFootAlong, halfLengthAlong};
        }
    }

    /**
     * Project one box's footprint along the light.
     *
     * <p>The model is the textbook parallel projection, and the arithmetic has
     * to be the textbook's rather than a plausible-looking relative of it: a
     * point at height {@code h} above the floor, lit by a ray pointing
     * {@code (lx, ly, lz)} (the direction light TRAVELS, any length, {@code ly
     * < 0} being a sun), lands where the ray meets {@code y = floor} -- an
     * offset of {@code h * (lx, lz) / -ly}, which is {@code h / tan(elevation)}
     * along the light's horizontal run. Dividing by {@code sin(elevation)}
     * instead -- the mistake this method shipped with -- reads the ray's own
     * length as its vertical drop: it put a 22-degree sun's shadow 8 % too far
     * and a 5-degree one more than twice too far, so a pool detached from the
     * feet it was supposed to leave.</p>
     *
     * <p>The pool is then the box's silhouette on the floor, not a stretched
     * footprint: the base rectangle stays where it is, the top rectangle lands
     * {@code run} away along the light, and the quad that covers both is
     * centred halfway along that run and reaches half a footprint beyond each
     * end. Its near edge therefore BEGINS at the caster's feet -- the invariant a
     * shadow has to hold for the eye to read it as belonging to that person --
     * and its width is the footprint measured ACROSS the light, so a sun
     * running along X does not borrow the caster's Z width. The run is capped
     * so a horizon "sun" cannot cast across the world, and a light with no
     * horizontal run (straight down, or none at all) returns null: the caller
     * keeps the round pool, which for overhead light is not a fallback but the
     * physically right answer.</p>
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
        // tan of the elevation is horizontal / down, so the top's ground offset
        // is height * horizontal / down. Scale-free: doubling the ray doubles
        // both terms and changes nothing.
        double run = height * horizontal / down;
        run = Math.min(run, height * MAX_RUN_HEIGHTS);
        double unitX = lightX / horizontal;
        double unitZ = lightZ / horizontal;
        double groundY = footY; // pools lie on the floor they were collected with

        // The yaw turns +Z onto the light's run direction, so halfLengthAlong is
        // "along the light". atan2 of the run vector, degrees, Java 3D's sign.
        double yawDeg = Math.toDegrees(Math.atan2(unitX, unitZ));
        // The footprint's own reach, measured on the two horizontal axes of the
        // rotated quad: the support of an axis-aligned rectangle along a unit
        // direction is the sum of its half-extents weighted by that direction's
        // components, once per axis.
        double halfFootAlong = halfX * Math.abs(unitX) + halfZ * Math.abs(unitZ);
        double halfAcross = halfX * Math.abs(unitZ) + halfZ * Math.abs(unitX);
        // The shadow lies along the way the light travels: the feet stay put, the
        // top lands `run` away, and the pool spans both -- so its centre is
        // halfway along the run and its half-length carries half a footprint
        // past each end. A ray heading -X puts the shadow at -X, starting at X 0.
        double alongX = centerX + unitX * run * 0.5;
        double alongZ = centerZ + unitZ * run * 0.5;

        return new ProjectedShadow(alongX, groundY, alongZ,
                halfAcross, halfFootAlong, halfFootAlong + run * 0.5, yawDeg);
    }
}
