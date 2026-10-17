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
 * A tiny mutable 2D vector for the desktop-independent physics core.
 *
 * <p>This is the planar counterpart of the {@code org.jogamp.vecmath.Vector3f}
 * the native-3D engine uses. It is deliberately dependency-free (plain
 * {@code java.lang}) so the whole {@code physics.model} package - and therefore
 * the 2D {@code NewtonCradlePanel} that renders it - can be loaded by the
 * 2D/Swing desktop without ever pulling in AWT, Swing or Java 3D.</p>
 *
 * <p>Mutators return {@code this} so the integration and collision maths reads
 * as a fluent chain, mirroring the original engine's vector style. The
 * components are public for the same reason {@code Vector3f}'s are: this is an
 * internal arithmetic helper, not a value object with invariants to guard.</p>
 */
public final class Vec2 {

    /** The horizontal component. */
    public float x;

    /** The vertical component (positive up; gravity acts along {@code -y}). */
    public float y;

    /** Constructs the zero vector. */
    public Vec2() {
        this(0f, 0f);
    }

    /**
     * Constructs a vector with the given components.
     *
     * @param x the horizontal component
     * @param y the vertical component
     */
    public Vec2(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Copy-constructs a vector.
     *
     * @param other the vector to copy
     */
    public Vec2(Vec2 other) {
        this(other.x, other.y);
    }

    /**
     * Sets both components.
     *
     * @param x the new horizontal component
     * @param y the new vertical component
     * @return this vector, for chaining
     */
    public Vec2 set(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    /**
     * Copies another vector's components into this one.
     *
     * @param other the vector to copy
     * @return this vector, for chaining
     */
    public Vec2 set(Vec2 other) {
        this.x = other.x;
        this.y = other.y;
        return this;
    }

    /**
     * Adds another vector in place.
     *
     * @param other the vector to add
     * @return this vector, for chaining
     */
    public Vec2 add(Vec2 other) {
        this.x += other.x;
        this.y += other.y;
        return this;
    }

    /**
     * Subtracts another vector in place.
     *
     * @param other the vector to subtract
     * @return this vector, for chaining
     */
    public Vec2 sub(Vec2 other) {
        this.x -= other.x;
        this.y -= other.y;
        return this;
    }

    /**
     * Scales both components in place.
     *
     * @param scale the factor to multiply by
     * @return this vector, for chaining
     */
    public Vec2 scale(float scale) {
        this.x *= scale;
        this.y *= scale;
        return this;
    }

    /**
     * Adds a scaled vector in place ({@code this += scale * other}).
     *
     * @param other the vector to add
     * @param scale the factor to scale {@code other} by before adding
     * @return this vector, for chaining
     */
    public Vec2 addScaled(Vec2 other, float scale) {
        this.x += other.x * scale;
        this.y += other.y * scale;
        return this;
    }

    /**
     * Returns the dot product with another vector.
     *
     * @param other the other vector
     * @return the scalar dot product
     */
    public float dot(Vec2 other) {
        return x * other.x + y * other.y;
    }

    /**
     * Returns the squared length, avoiding a square root where only a
     * comparison is needed.
     *
     * @return the squared magnitude
     */
    public float lengthSquared() {
        return x * x + y * y;
    }

    /**
     * Returns the length (magnitude).
     *
     * @return the magnitude
     */
    public float length() {
        return (float) Math.sqrt(x * x + y * y);
    }

    /**
     * Normalizes this vector in place. A zero-length vector is left unchanged
     * (there is no meaningful direction to give it).
     *
     * @return this vector, for chaining
     */
    public Vec2 normalize() {
        float len = length();
        if (len > 0f) {
            x /= len;
            y /= len;
        }
        return this;
    }

    /**
     * Returns an independent copy.
     *
     * @return a new vector with the same components
     */
    public Vec2 copy() {
        return new Vec2(x, y);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
