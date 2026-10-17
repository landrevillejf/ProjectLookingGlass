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
 * A single simulated disc: one ball of the cradle.
 *
 * <p>This is the planar counterpart of the native-3D engine's
 * {@code PhysicsSphere}. It keeps the same state the 3D body carries for the
 * degrees of freedom a cradle actually exercises - centre of mass, velocity,
 * accumulated force, mass and restitution - but drops the attitude quaternion,
 * inertia tensor and angular velocity: the balls hang in a plane and meet
 * head-on along the line of centres, so the textbook linear impulse is the
 * whole of the collision and a 2D spin term would neither show nor change the
 * result.</p>
 *
 * <p>The state vectors are package-private and mutable so
 * {@link NewtonCradleModel} can integrate them directly in its hot loop; every
 * outside reader (the panel, the tests) goes through the immutable scalar
 * accessors below.</p>
 */
public final class Particle {

    /** Centre of mass in world coordinates (metres; {@code y} is up). */
    final Vec2 position;

    /** Linear velocity in metres per second. */
    final Vec2 velocity;

    /** Force accumulated for the current step, in newtons. */
    final Vec2 force;

    /** Mass in kilograms. */
    final float mass;

    /** Cached {@code 1 / mass}, the factor the integrator and impulse use. */
    final float invMass;

    /** Disc radius in metres. */
    final float radius;

    /** Coefficient of restitution (1 = perfectly elastic, 0 = perfectly inelastic). */
    final float restitution;

    /**
     * Constructs a disc.
     *
     * @param position    the initial centre of mass (copied)
     * @param mass        the mass in kilograms; must be positive
     * @param radius      the radius in metres; must be positive
     * @param restitution the coefficient of restitution, clamped to {@code [0, 1]}
     */
    Particle(Vec2 position, float mass, float radius, float restitution) {
        this.position = position.copy();
        this.velocity = new Vec2();
        this.force = new Vec2();
        this.mass = mass;
        this.invMass = (mass > 0f) ? 1f / mass : 0f;
        this.radius = radius;
        this.restitution = Math.max(0f, Math.min(1f, restitution));
    }

    /**
     * Returns the horizontal centre of mass.
     *
     * @return the world {@code x} in metres
     */
    public float getX() {
        return position.x;
    }

    /**
     * Returns the vertical centre of mass.
     *
     * @return the world {@code y} in metres
     */
    public float getY() {
        return position.y;
    }

    /**
     * Returns the horizontal velocity.
     *
     * @return {@code vx} in metres per second
     */
    public float getVelocityX() {
        return velocity.x;
    }

    /**
     * Returns the vertical velocity.
     *
     * @return {@code vy} in metres per second
     */
    public float getVelocityY() {
        return velocity.y;
    }

    /**
     * Returns the radius.
     *
     * @return the radius in metres
     */
    public float getRadius() {
        return radius;
    }

    /**
     * Returns the mass.
     *
     * @return the mass in kilograms
     */
    public float getMass() {
        return mass;
    }

    /**
     * Returns the coefficient of restitution.
     *
     * @return the restitution in {@code [0, 1]}
     */
    public float getRestitution() {
        return restitution;
    }

    /**
     * Returns the speed (velocity magnitude).
     *
     * @return the speed in metres per second
     */
    public float getSpeed() {
        return velocity.length();
    }
}
