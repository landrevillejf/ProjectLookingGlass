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
 * A damped spring that ties one {@link Particle} to a fixed anchor point - the
 * string a cradle ball hangs from.
 *
 * <p>This is a direct planar transcription of the native-3D engine's
 * {@code Spring.apply()}. The 3D version joins two moving bodies and pushes
 * equal-and-opposite forces onto both; here the "left" end is an immovable
 * anchor (the {@code FixedPoint} of the 3D demo, whose inverse mass is zero and
 * which never integrates), so only the force on the ball is accumulated. The
 * force law itself is unchanged: a Hooke stretch term plus a damping term
 * projected onto the spring axis,</p>
 *
 * <pre>
 *   d        = anchor - ball            // axis, ball -> anchor
 *   stretch  = |d|
 *   vRel     = anchorVel(0) - ballVel   // = -ballVel
 *   scale    = ( k * (stretch - rest) + damping * (vRel . d / stretch) ) / stretch
 *   force    = d * scale                // pulls the ball toward the anchor
 * </pre>
 *
 * <p>With the demo's heavily over-damped constants the radial term dies almost
 * immediately, so the string holds its length and the ball swings as a
 * pendulum - exactly the constraint the 3D cradle gets from its paired
 * {@code z}-offset anchors, which a planar model needs only one anchor to
 * reproduce.</p>
 */
public final class Spring {

    /** The immovable anchor the string hangs from. */
    final Vec2 anchor;

    /** The ball the string pulls on. */
    final Particle particle;

    /** The relaxed (unstretched) string length, in metres. */
    final float restLength;

    /** Hooke spring constant {@code k}, in newtons per metre. */
    final float springConstant;

    /** Damping constant, in newton-seconds per metre. */
    final float dampingConstant;

    /**
     * Constructs a spring from a fixed anchor to a particle.
     *
     * @param anchor     the fixed anchor point (copied; never mutated)
     * @param particle   the ball the spring acts on
     * @param restLength the relaxed length in metres
     * @param springConstant  the Hooke constant {@code k}
     * @param dampingConstant the damping constant
     */
    Spring(Vec2 anchor, Particle particle, float restLength,
            float springConstant, float dampingConstant) {
        this.anchor = anchor.copy();
        this.particle = particle;
        this.restLength = restLength;
        this.springConstant = springConstant;
        this.dampingConstant = dampingConstant;
    }

    /**
     * Accumulates this spring's force onto its particle. The anchor is fixed,
     * so (unlike the 3D two-body form) no reaction force is applied anywhere.
     */
    void apply() {
        // Axis from the ball to the anchor.
        float dx = anchor.x - particle.position.x;
        float dy = anchor.y - particle.position.y;
        float stretch = (float) Math.sqrt(dx * dx + dy * dy);
        if (stretch <= 0f) {
            // Degenerate: the ball is exactly on the anchor; no defined axis.
            return;
        }
        // Relative velocity of the two ends; the anchor never moves, so this is
        // simply the negated ball velocity (matching left.vel - right.vel).
        float rvx = -particle.velocity.x;
        float rvy = -particle.velocity.y;
        float radial = (rvx * dx + rvy * dy) / stretch;
        float scale = (springConstant * (stretch - restLength)
                + dampingConstant * radial) / stretch;
        particle.force.x += dx * scale;
        particle.force.y += dy * scale;
    }
}
