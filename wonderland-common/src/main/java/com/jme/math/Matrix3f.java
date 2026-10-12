/*
 * In-tree replacement for jMonkeyEngine's com.jme.math.Matrix3f.
 *
 * The ported data model only uses it in Math3DUtils.toEuler, which rotates the
 * basis axes by matrix.mult(v, out). Row-major m[row][col] layout, identity by
 * default, exactly the surface the caller needs.
 */
package com.jme.math;

import java.io.Serializable;

public class Matrix3f implements Serializable {

    public float m00 = 1.0F;
    public float m01 = 0.0F;
    public float m02 = 0.0F;
    public float m10 = 0.0F;
    public float m11 = 1.0F;
    public float m12 = 0.0F;
    public float m20 = 0.0F;
    public float m21 = 0.0F;
    public float m22 = 1.0F;

    public Matrix3f() {
    }

    public Vector3f mult(Vector3f in, Vector3f out) {
        if (out == null) {
            out = new Vector3f();
        }
        float vx = in.x;
        float vy = in.y;
        float vz = in.z;
        out.x = m00 * vx + m01 * vy + m02 * vz;
        out.y = m10 * vx + m11 * vy + m12 * vz;
        out.z = m20 * vx + m21 * vy + m22 * vz;
        return out;
    }
}
