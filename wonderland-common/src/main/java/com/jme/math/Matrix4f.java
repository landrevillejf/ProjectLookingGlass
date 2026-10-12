/*
 * In-tree replacement for jMonkeyEngine's com.jme.math.Matrix4f.
 *
 * The ported data model builds a view-platform transform by assigning the m00..m33
 * columns directly (AvatarBoundsHelper.computeViewPlatformTransform), so only the
 * public field set and the identity default are needed here. Real matrix algebra
 * for rendering lands with the client renderer (milestone M2).
 */
package com.jme.math;

import java.io.Serializable;

public class Matrix4f implements Serializable {

    public float m00 = 1.0F;
    public float m01 = 0.0F;
    public float m02 = 0.0F;
    public float m03 = 0.0F;
    public float m10 = 0.0F;
    public float m11 = 1.0F;
    public float m12 = 0.0F;
    public float m13 = 0.0F;
    public float m20 = 0.0F;
    public float m21 = 0.0F;
    public float m22 = 1.0F;
    public float m23 = 0.0F;
    public float m30 = 0.0F;
    public float m31 = 0.0F;
    public float m32 = 0.0F;
    public float m33 = 1.0F;

    public Matrix4f() {
    }
}
