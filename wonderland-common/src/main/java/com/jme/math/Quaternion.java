/*
 * In-tree replacement for jMonkeyEngine's com.jme.math.Quaternion.
 *
 * See com.jme.math.Vector3f for why this shim exists (jME was fetched over Java
 * Web Start and is not in the Wonderland bundle). This reimplements the surface
 * the ported data model uses: the public x/y/z/w fields CellTransform reads, the
 * angle/axis conversions the JAXB adapter and CellTransform.toString rely on,
 * the vector-rotation used to transform a point by a transform, and euler
 * extraction for epsilonEquals. Pinned by com.jme.math.QuaternionTest.
 */
package com.jme.math;

import java.io.Serializable;

public class Quaternion implements Serializable, Cloneable {

    public float x;
    public float y;
    public float z;
    public float w;

    public Quaternion() {
        w = 1.0F;
    }

    public Quaternion(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public Quaternion(Quaternion copy) {
        this.x = copy.x;
        this.y = copy.y;
        this.z = copy.z;
        this.w = copy.w;
    }

    public void set(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public Quaternion set(Quaternion quat) {
        this.x = quat.x;
        this.y = quat.y;
        this.z = quat.z;
        this.w = quat.w;
        return this;
    }

    public Quaternion subtract(Quaternion quat) {
        x -= quat.x;
        y -= quat.y;
        z -= quat.z;
        w -= quat.w;
        return this;
    }

    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    public Quaternion normalize() {
        float len = (float) Math.sqrt(lengthSquared());
        if (len > FastMath.EPSILON) {
            x /= len;
            y /= len;
            z /= len;
            w /= len;
        }
        return this;
    }

    public Quaternion normalizeLocal() {
        return normalize();
    }

    /** Rotate the given vector in place by this quaternion (jME multLocal semantics). */
    public Vector3f multLocal(Vector3f v) {
        float vx = v.x;
        float vy = v.y;
        float vz = v.z;
        float tx = 2.0F * (y * vz - z * vy);
        float ty = 2.0F * (z * vx - x * vz);
        float tz = 2.0F * (x * vy - y * vx);
        v.x = vx + w * tx + (y * tz - z * ty);
        v.y = vy + w * ty + (z * tx - x * tz);
        v.z = vz + w * tz + (x * ty - y * tx);
        return v;
    }

    public Quaternion mult(Quaternion quat) {
        return mult(quat, this);
    }

    public Quaternion mult(Quaternion q, Quaternion result) {
        // Save every operand: callers use `q.mult(q2)` and `q.mult(q2, q)` where
        // result == this, so writing result.x before reading this.x would corrupt
        // the Hamilton product.
        float qw = q.w;
        float qx = q.x;
        float qy = q.y;
        float qz = q.z;
        float x0 = x;
        float y0 = y;
        float z0 = z;
        float w0 = w;
        result.x = x0 * qw + y0 * qz - z0 * qy + w0 * qx;
        result.y = -x0 * qz + y0 * qw + z0 * qx + w0 * qy;
        result.z = x0 * qy - y0 * qx + z0 * qw + w0 * qz;
        result.w = -x0 * qx - y0 * qy - z0 * qz + w0 * qw;
        return result;
    }

    public Quaternion fromAngleAxis(float angle, Vector3f axis) {
        Vector3f norm = new Vector3f(axis);
        norm.normalize();
        float halfAngle = angle * 0.5F;
        w = (float) Math.cos(halfAngle);
        float s = (float) Math.sin(halfAngle);
        x = norm.x * s;
        y = norm.y * s;
        z = norm.z * s;
        return this;
    }

    public float toAngleAxis(Vector3f axis) {
        normalize();
        float angle;
        if (w > 1.0F) {
            angle = 0.0F;
            axis.set(0.0F, 0.0F, 1.0F);
            return angle;
        }
        if (w < -1.0F) {
            angle = (float) Math.PI;
            axis.x = 1.0F;
            axis.y = 0.0F;
            axis.z = 0.0F;
            return angle;
        }
        angle = 2.0F * (float) Math.acos(w);
        float denom = (float) Math.sqrt(1.0F - w * w);
        if (denom >= FastMath.EPSILON) {
            axis.x = x / denom;
            axis.y = y / denom;
            axis.z = z / denom;
        } else {
            axis.set(0.0F, 0.0F, 1.0F);
        }
        return angle;
    }

    /** Euler XYZ extraction, matching the aerospace convention jME used. */
    public float[] toAngles(float[] angles) {
        if (angles == null) {
            angles = new float[3];
        }
        float sqx = x * x;
        float sqy = y * y;
        float sqz = z * z;
        float sqw = w * w;
        // roll (x), pitch (y), yaw (z)
        angles[0] = FastMath.atan2(2.0F * (x * w + y * z), (-sqz - sqy + sqx + sqw));
        angles[1] = FastMath.asin(-2.0F * (x * z - w * y));
        angles[2] = FastMath.atan2(2.0F * (z * w + x * y), (-sqz + sqy - sqx + sqw));
        return angles;
    }

    @Override
    public Quaternion clone() {
        return new Quaternion(x, y, z, w);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Quaternion)) {
            return false;
        }
        Quaternion that = (Quaternion) o;
        return Float.compare(x, that.x) == 0
                && Float.compare(y, that.y) == 0
                && Float.compare(z, that.z) == 0
                && Float.compare(w, that.w) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(x);
        result = 31 * result + Float.floatToIntBits(y);
        result = 31 * result + Float.floatToIntBits(z);
        result = 31 * result + Float.floatToIntBits(w);
        return result;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ", " + w + ")";
    }
}
