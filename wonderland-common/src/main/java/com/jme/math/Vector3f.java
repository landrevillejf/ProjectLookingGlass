/*
 * In-tree replacement for jMonkeyEngine's com.jme.math.Vector3f.
 *
 * OpenWonderland's data model is written against jMonkeyEngine 1.x's
 * com.jme.math.Vector3f, but jME was fetched over Java Web Start at launch and
 * is NOT in the Wonderland bundle (unlike the Java 3D / vecmath types, there is
 * no bytecode to port). This class reimplements the exact surface the ported
 * data model uses - public x/y/z fields, mutating arithmetic, cross/dot,
 * length, normalize and angleBetween - with the same float semantics jME had, so
 * CellTransform, the movable messages and the bounding volumes behave as before.
 * Correctness is pinned by com.jme.math.Vector3fTest, not asserted.
 */
package com.jme.math;

import java.io.Serializable;

public class Vector3f implements Serializable, Cloneable {

    public static final Vector3f ZERO = new Vector3f(0.0F, 0.0F, 0.0F);
    public static final Vector3f UNIT_X = new Vector3f(1.0F, 0.0F, 0.0F);
    public static final Vector3f UNIT_Y = new Vector3f(0.0F, 1.0F, 0.0F);
    public static final Vector3f UNIT_Z = new Vector3f(0.0F, 0.0F, 1.0F);

    public float x;
    public float y;
    public float z;

    public Vector3f() {
    }

    public Vector3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f(Vector3f copy) {
        this.x = copy.x;
        this.y = copy.y;
        this.z = copy.z;
    }

    public void set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f set(Vector3f xyz) {
        this.x = xyz.x;
        this.y = xyz.y;
        this.z = xyz.z;
        return this;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZ() {
        return z;
    }

    public Vector3f add(Vector3f rhs) {
        x += rhs.x;
        y += rhs.y;
        z += rhs.z;
        return this;
    }

    public Vector3f addLocal(Vector3f rhs) {
        return add(rhs);
    }

    public Vector3f add(float x, float y, float z) {
        this.x += x;
        this.y += y;
        this.z += z;
        return this;
    }

    public Vector3f subtract(Vector3f rhs) {
        x -= rhs.x;
        y -= rhs.y;
        z -= rhs.z;
        return this;
    }

    public Vector3f subtractLocal(Vector3f rhs) {
        return subtract(rhs);
    }

    public Vector3f mult(float scalar) {
        x *= scalar;
        y *= scalar;
        z *= scalar;
        return this;
    }

    public Vector3f multLocal(float scalar) {
        return mult(scalar);
    }

    public Vector3f multLocal(float xs, float ys, float zs) {
        x *= xs;
        y *= ys;
        z *= zs;
        return this;
    }

    public Vector3f multLocal(Vector3f rhs) {
        x *= rhs.x;
        y *= rhs.y;
        z *= rhs.z;
        return this;
    }

    public Vector3f divide(float scalar) {
        x /= scalar;
        y /= scalar;
        z /= scalar;
        return this;
    }

    public float dot(Vector3f rhs) {
        return x * rhs.x + y * rhs.y + z * rhs.z;
    }

    public Vector3f cross(Vector3f rhs) {
        return cross(this, rhs);
    }

    /** this = v1 x v2, matching jME's two-argument cross used by the scanner code. */
    public Vector3f cross(Vector3f v1, Vector3f v2) {
        // Save the results: when this == v1 (the single-arg form) writing x then
        // reading it for y / z would corrupt the cross product.
        float cx = v1.y * v2.z - v1.z * v2.y;
        float cy = v1.z * v2.x - v1.x * v2.z;
        float cz = v1.x * v2.y - v1.y * v2.x;
        x = cx;
        y = cy;
        z = cz;
        return this;
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public Vector3f negate() {
        x = -x;
        y = -y;
        z = -z;
        return this;
    }

    public Vector3f negateLocal() {
        return negate();
    }

    public Vector3f normalize() {
        float len = length();
        if (len != 0.0F) {
            divide(len);
        }
        return this;
    }

    public Vector3f normalizeLocal() {
        return normalize();
    }

    public float distance(Vector3f other) {
        float dx = x - other.x;
        float dy = y - other.y;
        float dz = z - other.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Smallest angle between this and rhs, independent of length (jME semantics). */
    public float angleBetween(Vector3f other) {
        float dotProduct = dot(other);
        float angle = (float) Math.acos(dotProduct / (length() * other.length()));
        return angle;
    }

    public Vector3f zero() {
        x = 0.0F;
        y = 0.0F;
        z = 0.0F;
        return this;
    }

    @Override
    public Vector3f clone() {
        return new Vector3f(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Vector3f)) {
            return false;
        }
        Vector3f that = (Vector3f) o;
        return Float.compare(x, that.x) == 0
                && Float.compare(y, that.y) == 0
                && Float.compare(z, that.z) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(x);
        result = 31 * result + Float.floatToIntBits(y);
        result = 31 * result + Float.floatToIntBits(z);
        return result;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
