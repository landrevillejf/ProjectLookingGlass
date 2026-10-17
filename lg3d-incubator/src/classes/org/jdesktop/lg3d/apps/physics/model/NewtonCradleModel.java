/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.physics.model;

/**
 * An AWT-free, Java 3D-free 2D simulation of a Newton's cradle.
 *
 * <p>This is the desktop-independent heart of the resurrected Physics app. The
 * native-3D {@code NewtonCradleDemo} builds the same scene from the in-package
 * rigid-body engine ({@code PhysicsThread}, {@code PhysicsSphere},
 * {@code Spring}, {@code Gravity}, {@code ImpulseResolver}) and steps it on a
 * background thread that copies transforms into the scene graph. That engine
 * cannot be reused directly here: {@code PhysicsObject} imports
 * {@code org.jogamp.java3d.*} and {@code org.jdesktop.lg3d.wg.Component3D} at
 * class level, so touching it would drag Java 3D into the 2D/Swing desktop,
 * which must never load the scene graph. Instead this model reproduces the
 * demo's dynamics in the plane, with the same algorithm and the same physical
 * constants, over the dependency-free {@link Vec2}/{@link Particle}/{@link
 * Spring} core.</p>
 *
 * <p>One {@link #step(float)} advances the simulation by a wall-clock delta,
 * split into fixed sub-steps small enough for the stiff, heavily damped
 * strings. Each sub-step runs the {@code PhysicsThread.run()} sequence reduced
 * to planar, synchronous form:</p>
 * <ol>
 *  <li>accumulate forces - gravity ({@code -g * mass} on {@code y}) plus every
 *      string's damped spring force;</li>
 *  <li>leapfrog the velocity ({@code v += F * invMass * h});</li>
 *  <li>detect disc-disc contacts and resolve them with a Newton-Coulomb impulse
 *      along the line of centres, iterated so the momentum transfers cleanly
 *      through the resting chain (one ball in, one ball out);</li>
 *  <li>leapfrog the position ({@code p += v * h});</li>
 *  <li>push apart any residual overlap, mass-weighted, as the 3D resolver's
 *      {@code separate()} pass does.</li>
 * </ol>
 *
 * <p>The whole class is deterministic given the same sequence of {@code step}
 * deltas, which is what lets the headless suite assert on the motion (energy
 * decay, momentum transfer, balls staying within the frame) without a display.</p>
 */
public final class NewtonCradleModel {

    /** Number of balls in the cradle (the demo uses seven). */
    public static final int BALL_COUNT = 7;

    /** Ball radius in metres. */
    public static final float BALL_RADIUS = 0.012f;

    /** Centre-to-centre spacing at rest; two radii, so the balls just touch. */
    public static final float BALL_SPACING = 2f * BALL_RADIUS;

    /** Ball mass in kilograms (the demo uses 1.1). */
    public static final float BALL_MASS = 1.1f;

    /** Coefficient of restitution for a ball-ball impact (the demo uses 0.95). */
    public static final float RESTITUTION = 0.95f;

    /** Gravitational acceleration in m/s^2 (the demo uses 5.81). */
    public static final float GRAVITY = 5.81f;

    /** Relaxed string length in metres. */
    public static final float STRING_REST = 0.14f;

    /** String spring constant {@code k} (the demo uses 205.9). */
    public static final float SPRING_K = 205.9f;

    /** String damping constant (the demo uses 206.2, well over critical). */
    public static final float SPRING_DAMPING = 206.2f;

    /** Angle, in degrees, the first ball is pulled back to and released from. */
    public static final float PULL_ANGLE_DEG = 22f;

    /** Height of the anchor bar above the world origin (anchors sit at y = 0). */
    public static final float ANCHOR_Y = 0f;

    // --- integration / solver tuning (not physical constants) ---------------

    /** Largest sub-step; caps the integration interval for the stiff strings. */
    private static final float MAX_SUBSTEP = 0.0005f;

    /** Largest wall-clock delta one {@link #step(float)} will honour (0.05 s). */
    private static final float MAX_FRAME_DT = 0.05f;

    /** Impulse passes per sub-step; lets the chain transfer in a single step. */
    private static final int COLLISION_ITERS = 8;

    /** Positional-correction passes per sub-step. */
    private static final int SEPARATION_ITERS = 4;

    /** Detection generosity so a just-touching resting pair still registers. */
    private static final float CONTACT_SKIN = 1e-4f;

    /** Penetration tolerated before positional correction kicks in. */
    private static final float CONTACT_SLOP = 1e-5f;

    /** Fraction of the (over-slop) penetration removed per correction pass. */
    private static final float SEPARATION_PERCENT = 0.4f;

    /** Approach speed below which restitution is dropped to kill micro-bounce. */
    private static final float REST_EPSILON = 1e-3f;

    private final Particle[] balls;
    private final Vec2[] anchors;
    private final Spring[] springs;

    /** Equilibrium hang distance: relaxed length plus the static gravity sag. */
    private final float stringLength;

    /** Simulated seconds elapsed since the last {@link #reset()}. */
    private float time;

    // Static world bounds (from geometry + the pull angle), so the viewer's
    // fit-to-world transform never jitters as the balls swing.
    private final float minX;
    private final float maxX;
    private final float minY;
    private final float maxY;

    /**
     * Builds a seven-ball cradle and places it at its initial state: six balls
     * hanging at rest in a touching row, the first pulled back to
     * {@link #PULL_ANGLE_DEG} and held still, ready to be released by the first
     * {@link #step(float)}.
     */
    public NewtonCradleModel() {
        this.balls = new Particle[BALL_COUNT];
        this.anchors = new Vec2[BALL_COUNT];
        this.springs = new Spring[BALL_COUNT];
        // The static sag of a mass on the damped spring: k * sag = m * g.
        this.stringLength = STRING_REST + (BALL_MASS * GRAVITY) / SPRING_K;

        float centre = (BALL_COUNT - 1) / 2f;
        for (int i = 0; i < BALL_COUNT; i++) {
            float ax = (i - centre) * BALL_SPACING;
            anchors[i] = new Vec2(ax, ANCHOR_Y);
            balls[i] = new Particle(
                    new Vec2(ax, ANCHOR_Y - stringLength),
                    BALL_MASS, BALL_RADIUS, RESTITUTION);
            springs[i] = new Spring(
                    anchors[i], balls[i], STRING_REST, SPRING_K, SPRING_DAMPING);
        }

        float halfSpan = centre * BALL_SPACING;
        float pull = (float) Math.toRadians(PULL_ANGLE_DEG);
        float swing = stringLength * (float) Math.sin(pull);
        this.minX = -halfSpan - swing - BALL_RADIUS;
        this.maxX = halfSpan + swing + BALL_RADIUS;
        this.maxY = ANCHOR_Y + BALL_RADIUS;
        this.minY = ANCHOR_Y - stringLength - BALL_RADIUS;

        reset();
    }

    /**
     * Restores the initial state: six balls hanging at rest, the first pulled
     * back to {@link #PULL_ANGLE_DEG}, all velocities zero, the clock at zero.
     */
    public void reset() {
        time = 0f;
        float pull = (float) Math.toRadians(PULL_ANGLE_DEG);
        float sin = (float) Math.sin(pull);
        float cos = (float) Math.cos(pull);
        for (int i = 0; i < BALL_COUNT; i++) {
            Particle ball = balls[i];
            ball.velocity.set(0f, 0f);
            ball.force.set(0f, 0f);
            if (i == 0) {
                // Pull the first ball out to the release angle on its string.
                ball.position.set(
                        anchors[i].x - stringLength * sin,
                        ANCHOR_Y - stringLength * cos);
            } else {
                ball.position.set(anchors[i].x, ANCHOR_Y - stringLength);
            }
        }
    }

    /**
     * Advances the simulation by {@code dt} seconds, split into fixed sub-steps.
     * A non-positive delta is ignored; a delta larger than {@value #MAX_FRAME_DT}
     * is clamped so a stalled viewer cannot make one frame do unbounded work.
     *
     * @param dt the wall-clock seconds to advance
     */
    public void step(float dt) {
        if (dt <= 0f) {
            return;
        }
        float remaining = Math.min(dt, MAX_FRAME_DT);
        while (remaining > 0f) {
            float h = Math.min(MAX_SUBSTEP, remaining);
            substep(h);
            remaining -= h;
            time += h;
        }
    }

    /** One fixed integration step of the whole cradle. */
    private void substep(float h) {
        // 1. Accumulate forces: gravity, then every string.
        for (Particle ball : balls) {
            ball.force.set(0f, -GRAVITY * ball.mass);
        }
        for (Spring spring : springs) {
            spring.apply();
        }
        // 2. Leapfrog the velocity from the accumulated force.
        for (Particle ball : balls) {
            ball.velocity.x += ball.force.x * ball.invMass * h;
            ball.velocity.y += ball.force.y * ball.invMass * h;
        }
        // 3. Resolve contacts, iterated so the impulse walks the resting chain.
        for (int iter = 0; iter < COLLISION_ITERS; iter++) {
            resolveCollisions();
        }
        // 4. Leapfrog the position from the (now post-impulse) velocity.
        for (Particle ball : balls) {
            ball.position.x += ball.velocity.x * h;
            ball.position.y += ball.velocity.y * h;
        }
        // 5. Remove any residual overlap so the balls never visibly interpenetrate.
        for (int iter = 0; iter < SEPARATION_ITERS; iter++) {
            separate();
        }
    }

    /**
     * Applies a Newton-Coulomb impulse along the line of centres to every
     * approaching, touching pair. Equal masses and a restitution near one make
     * the classic exchange: the moving ball stops and the far ball of the chain
     * leaves with (almost) all of its speed.
     */
    private void resolveCollisions() {
        for (int i = 0; i < BALL_COUNT; i++) {
            Particle a = balls[i];
            for (int j = i + 1; j < BALL_COUNT; j++) {
                Particle b = balls[j];
                float dx = a.position.x - b.position.x;
                float dy = a.position.y - b.position.y;
                float distSq = dx * dx + dy * dy;
                float reach = a.radius + b.radius + CONTACT_SKIN;
                if (distSq > reach * reach || distSq == 0f) {
                    continue;
                }
                float dist = (float) Math.sqrt(distSq);
                // Unit normal from b toward a.
                float nx = dx / dist;
                float ny = dy / dist;
                float rvx = a.velocity.x - b.velocity.x;
                float rvy = a.velocity.y - b.velocity.y;
                float vn = rvx * nx + rvy * ny;
                if (vn >= 0f) {
                    // Already separating (or exactly at rest); no impulse.
                    continue;
                }
                // Drop restitution for a near-zero approach so a resting chain
                // settles instead of buzzing.
                float e = (-vn < REST_EPSILON) ? 0f : combinedRestitution(a, b);
                float impulse = -(1f + e) * vn / (a.invMass + b.invMass);
                a.velocity.x += impulse * a.invMass * nx;
                a.velocity.y += impulse * a.invMass * ny;
                b.velocity.x -= impulse * b.invMass * nx;
                b.velocity.y -= impulse * b.invMass * ny;
            }
        }
    }

    /**
     * Pushes overlapping pairs apart, mass-weighted, so a heavy ball moves less
     * than a light one - the planar form of the 3D resolver's {@code separate}.
     */
    private void separate() {
        for (int i = 0; i < BALL_COUNT; i++) {
            Particle a = balls[i];
            for (int j = i + 1; j < BALL_COUNT; j++) {
                Particle b = balls[j];
                float dx = a.position.x - b.position.x;
                float dy = a.position.y - b.position.y;
                float distSq = dx * dx + dy * dy;
                if (distSq == 0f) {
                    continue;
                }
                float dist = (float) Math.sqrt(distSq);
                float penetration = (a.radius + b.radius) - dist;
                if (penetration <= CONTACT_SLOP) {
                    continue;
                }
                float nx = dx / dist;
                float ny = dy / dist;
                float correction =
                        (penetration - CONTACT_SLOP) * SEPARATION_PERCENT
                        / (a.invMass + b.invMass);
                a.position.x += correction * a.invMass * nx;
                a.position.y += correction * a.invMass * ny;
                b.position.x -= correction * b.invMass * nx;
                b.position.y -= correction * b.invMass * ny;
            }
        }
    }

    /** The restitution a contact uses: the mean of the two bodies', as in 3D. */
    private static float combinedRestitution(Particle a, Particle b) {
        return (a.restitution + b.restitution) * 0.5f;
    }

    // ------------------------------------------------------------------
    // Read API: everything the viewer and the headless suite need.
    // ------------------------------------------------------------------

    /**
     * Returns the number of balls.
     *
     * @return the ball count
     */
    public int getBallCount() {
        return BALL_COUNT;
    }

    /**
     * Returns a ball's horizontal centre.
     *
     * @param index the ball index
     * @return its world {@code x} in metres
     */
    public float getBallX(int index) {
        return balls[index].position.x;
    }

    /**
     * Returns a ball's vertical centre.
     *
     * @param index the ball index
     * @return its world {@code y} in metres
     */
    public float getBallY(int index) {
        return balls[index].position.y;
    }

    /**
     * Returns a ball's horizontal velocity.
     *
     * @param index the ball index
     * @return its {@code vx} in metres per second
     */
    public float getBallVelocityX(int index) {
        return balls[index].velocity.x;
    }

    /**
     * Returns a ball's vertical velocity.
     *
     * @param index the ball index
     * @return its {@code vy} in metres per second
     */
    public float getBallVelocityY(int index) {
        return balls[index].velocity.y;
    }

    /**
     * Returns a ball's speed (velocity magnitude).
     *
     * @param index the ball index
     * @return its speed in metres per second
     */
    public float getBallSpeed(int index) {
        return balls[index].getSpeed();
    }

    /**
     * Returns the shared ball radius.
     *
     * @return the radius in metres
     */
    public float getBallRadius() {
        return BALL_RADIUS;
    }

    /**
     * Returns a string anchor's horizontal position.
     *
     * @param index the ball index whose anchor to return
     * @return the anchor's world {@code x} in metres
     */
    public float getAnchorX(int index) {
        return anchors[index].x;
    }

    /**
     * Returns the shared anchor-bar height.
     *
     * @return the anchor {@code y} in metres
     */
    public float getAnchorY() {
        return ANCHOR_Y;
    }

    /**
     * Returns the equilibrium hang distance (relaxed length plus static sag),
     * the length the viewer draws each string at.
     *
     * @return the hang distance in metres
     */
    public float getStringLength() {
        return stringLength;
    }

    /**
     * Returns the simulated seconds elapsed since the last {@link #reset()}.
     *
     * @return the elapsed simulated time
     */
    public float getTime() {
        return time;
    }

    /**
     * Returns the total kinetic energy of the cradle, useful as a coarse
     * "is it still moving" measure and to assert the motion decays.
     *
     * @return the sum of {@code 0.5 * m * v^2} over all balls
     */
    public float getKineticEnergy() {
        float total = 0f;
        for (Particle ball : balls) {
            total += 0.5f * ball.mass * ball.velocity.lengthSquared();
        }
        return total;
    }

    /**
     * Returns the largest ball speed, a convenient "settled?" probe.
     *
     * @return the maximum speed over all balls, in metres per second
     */
    public float getMaxSpeed() {
        float max = 0f;
        for (Particle ball : balls) {
            float speed = ball.velocity.length();
            if (speed > max) {
                max = speed;
            }
        }
        return max;
    }

    /**
     * Returns the total mechanical energy relative to the rest hang: kinetic
     * energy plus gravitational potential measured from where each ball hangs
     * at equilibrium. Unlike kinetic energy alone (which is zero at every swing
     * extreme), this stays high while the cradle is moving and falls as the
     * impacts and string damping bleed energy off, so it is a robust measure of
     * how much motion is left. It does not fall all the way to zero quickly:
     * once the balls collapse into a common-mode sway (the whole row swinging
     * together, so no more collisions and only the string's radial damping
     * left to act) the residual energy plateaus. The viewer therefore restarts
     * the toy on a fixed cadence rather than waiting for this to reach zero.
     *
     * @return the total energy in joules (it may dip a hair below zero mid-swing
     *         as a stretched string lets a ball fall below its rest hang)
     */
    public float getTotalEnergy() {
        float restY = ANCHOR_Y - stringLength;
        float total = 0f;
        for (Particle ball : balls) {
            total += 0.5f * ball.mass * ball.velocity.lengthSquared();
            total += ball.mass * GRAVITY * (ball.position.y - restY);
        }
        return total;
    }

    /**
     * Returns the left edge of the static world bounds.
     *
     * @return the minimum world {@code x}
     */
    public float getMinX() {
        return minX;
    }

    /**
     * Returns the right edge of the static world bounds.
     *
     * @return the maximum world {@code x}
     */
    public float getMaxX() {
        return maxX;
    }

    /**
     * Returns the bottom edge of the static world bounds.
     *
     * @return the minimum world {@code y}
     */
    public float getMinY() {
        return minY;
    }

    /**
     * Returns the top edge of the static world bounds.
     *
     * @return the maximum world {@code y}
     */
    public float getMaxY() {
        return maxY;
    }
}
