/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: fold a scene transform into a Java 3D Transform3D feed.
 *
 * The deferred M6 renderer needs, per node, the world matrix M5 already computed
 * (a {@code org.jogamp.vecmath.Matrix4d}), and per cell the live CellTransform the
 * comms layer pushes. Both become the 16-double row-major array the Java 3D
 * {@code Transform3D} is built from. vecmath is constructible in a headless JVM, so
 * this whole class is pure and unit-tested; the only Java 3D touch is the factory
 * wrapping the array in a Transform3D (probe-only).
 */
package org.jdesktop.wonderland.renderer.transform;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.jogamp.vecmath.Matrix4d;
import org.jogamp.vecmath.Quat4d;
import org.jogamp.vecmath.Vector3d;

/** Turns a world matrix or a {@link CellTransform} into a row-major {@code double[16]}. */
public final class WorldTransformMath {

    private WorldTransformMath() {
    }

    /** The identity, row-major. */
    public static double[] identity() {
        return new double[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1,
        };
    }

    /** The node's computed world matrix as a row-major 16-array. */
    public static double[] fromWorldMatrix(ColladaNode.WorldMatrix worldMatrix) {
        return worldMatrix == null ? identity() : toRowMajor(worldMatrix.matrix());
    }

    /** A {@link CellTransform}'s rotation-then-translation-then-scale as a 16-array. */
    public static double[] fromCellTransform(CellTransform transform) {
        if (transform == null) {
            return identity();
        }
        Quaternion rotation = transform.getRotation((Quaternion) null);
        Vector3f translation = transform.getTranslation((Vector3f) null);
        float scale = transform.getScaling();

        Quat4d q = new Quat4d(rotation.x, rotation.y, rotation.z, rotation.w);
        Vector3d t = new Vector3d(translation.x, translation.y, translation.z);
        Matrix4d m = new Matrix4d(q, t, scale);
        return toRowMajor(m);
    }

    /** Copy a {@link Matrix4d} into row-major order (the {@code Transform3D} mat layout). */
    public static double[] toRowMajor(Matrix4d m) {
        if (m == null) {
            return identity();
        }
        return new double[] {
                m.m00, m.m01, m.m02, m.m03,
                m.m10, m.m11, m.m12, m.m13,
                m.m20, m.m21, m.m22, m.m23,
                m.m30, m.m31, m.m32, m.m33,
        };
    }

    /**
     * Whether a row-major 4x4 may be handed to a {@code TransformGroup}: every
     * element of the top three rows finite, and nothing below the 3x3 -- the bottom
     * row must be exactly 0 0 0 1.
     *
     * <p>Java 3D applies this test and throws {@code BadTransformException} when it
     * fails, so a caller that does not ask first loses more than the offending node:
     * the throw escapes while the model is being built, and takes the cell, and in a
     * world browser the whole world, with it.</p>
     *
     * <p>What the test is was measured, not assumed
     * (build-gradle/scratch/WlProbe19): a transform whose determinant is zero -- an
     * axis flattened to nothing, or the whole 3x3 collapsed to 0.0 -- is accepted,
     * so singularity is not what Java 3D objects to, and a first guard written on
     * that assumption caught nothing. A NaN is refused, and so is a matrix with a
     * number in its bottom row, which is what the row-major {@code <matrix>} exports
     * in the shipped town and combined worlds produce (see
     * {@code ColladaTransform.matrixTokens}).</p>
     */
    public static boolean isAffine(double[] rowMajor) {
        if (rowMajor == null || rowMajor.length < 16) {
            return false;
        }
        for (int i = 0; i < 12; i++) { // m00..m03, m10..m13, m20..m23
            if (!Double.isFinite(rowMajor[i])) {
                return false;
            }
        }
        return rowMajor[12] == 0.0 && rowMajor[13] == 0.0 && rowMajor[14] == 0.0
                && rowMajor[15] == 1.0;
    }

    /**
     * The translation vector of a row-major matrix. vecmath and Java 3D both
     * keep the offset in the last COLUMN -- elements (0,3), (1,3), (2,3) --
     * which in the flat row-major layout lands at indices 3, 7 and 11.
     */
    public static double[] translationOf(double[] rowMajor) {
        if (rowMajor == null || rowMajor.length < 16) {
            return new double[] {0, 0, 0};
        }
        return new double[] {rowMajor[3], rowMajor[7], rowMajor[11]};
    }
}
