/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): a COLLADA 1.4.1 node transform.
 *
 * A <node> carries zero or more of the six standard transform elements, applied
 * in document order. This models one such element as its kind plus the raw
 * numeric token list, and folds it to a vecmath Matrix4d -- the same row/column
 * convention COLLADA's <matrix> uses and the identity the 2006 jme loader
 * consumed. Pure maths, no scene graph, so it runs and is covered headless.
 */
package org.jdesktop.wonderland.modules.collada;

import org.jogamp.vecmath.Matrix4d;

/** One COLLADA transform element (matrix / translate / rotate / scale / lookat). */
public final class ColladaTransform {

    /** The transform kinds COLLADA 1.4.1 defines on a node, in the order the spec lists them. */
    public enum Kind {
        /** a {@code <matrix>} element, 16 column-major floats. */
        MATRIX,
        /** a {@code <translate>} element, 3 floats. */
        TRANSLATE,
        /** a {@code <rotate>} element, 4 floats (axis xyz, angle degrees). */
        ROTATE,
        /** a {@code <scale>} element, 3 floats. */
        SCALE,
        /** a {@code <uniform_scale>} element, 1 float. */
        UNIFORM_SCALE,
        /** a {@code <lookat>} element, 6 floats (eye, center, up). */
        LOOKAT
    }

    private final Kind kind;
    private final String sid;
    private final double[] values;

    /**
     * @param kind   the transform kind
     * @param sid    the element's {@code sid}, may be null
     * @param values the whitespace-separated numeric tokens already parsed
     */
    public ColladaTransform(Kind kind, String sid, double[] values) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
        this.sid = sid;
        this.values = values == null ? new double[0] : values.clone();
    }

    public Kind kind() {
        return kind;
    }

    /** The element's {@code sid}, or null when the authoring tool omitted it. */
    public String sid() {
        return sid;
    }

    public double[] values() {
        return values.clone();
    }

    /**
     * Fold this single transform to a matrix. A token list whose length does not
     * match the kind is treated as fully-identity-preserving for the missing
     * components (a {@code translate} of fewer than three values only moves the
     * axes it names), matching COLLADA's component defaults.
     *
     * @return a fresh matrix; never mutates this
     */
    public Matrix4d toMatrix() {
        Matrix4d m = new Matrix4d();
        m.setIdentity();
        switch (kind) {
            case MATRIX:
                if (values.length < 16) {
                    throw new IllegalArgumentException(
                            "<matrix> needs 16 values, found " + values.length);
                }
                m = matrixTokens();
                break;
            case TRANSLATE:
                m.setTranslation(new org.jogamp.vecmath.Vector3d(
                        at(0), at(1), at(2)));
                break;
            case ROTATE:
                if (values.length < 4) {
                    throw new IllegalArgumentException(
                            "<rotate> needs 4 values, found " + values.length);
                }
                // COLLADA angles are degrees; vecmath's rotation setter expects radians.
                // The AxisAngle4d 4-scalar constructor is (x, y, z, angle) -- the axis
                // first, the angle last.
                m.setRotation(new org.jogamp.vecmath.AxisAngle4d(
                        values[0], values[1], values[2], Math.toRadians(values[3])));
                break;
            case SCALE:
                m.setElement(0, 0, at(0));
                m.setElement(1, 1, at(1));
                m.setElement(2, 2, at(2));
                break;
            case UNIFORM_SCALE:
                double s = at(0);
                m.setElement(0, 0, s);
                m.setElement(1, 1, s);
                m.setElement(2, 2, s);
                break;
            case LOOKAT:
                m = lookAt();
                break;
            default:
                throw new IllegalStateException("unhandled kind " + kind);
        }
        return m;
    }

    private double at(int index) {
        return index < values.length ? values[index] : 0.0;
    }

    /**
     * The 4x4 a {@code <matrix>} token list denotes.
     *
     * <p>COLLADA specifies column-major tokens and the parser read them that way,
     * until the shipped town and combined worlds were walked node by node
     * (build-gradle/scratch/WlProbe20): <strong>468 of 468</strong> {@code <matrix>}
     * elements across their four models carry their translation at tokens 3, 7 and
     * 11 -- row-major -- and none carries it at 12, 13 and 14. Read per spec, the
     * translation lands in the matrix's bottom row, the result is non-affine, and
     * Java 3D throws {@code BadTransformException} for every such node (98 of the
     * town's 412), which took the whole world down rather than the nodes.</p>
     *
     * <p>The layout is therefore chosen per matrix by the only test that separates
     * the two readings: an affine 4x4 has 0 0 0 1 across its bottom row, and at
     * most one reading of a given token list has it. The spec-conformant reading is
     * tried first, so a conformant file is never second-guessed, and a matrix that
     * is affine either way -- a pure rotation, say -- is genuinely ambiguous and
     * keeps the column-major reading the spec mandates.</p>
     */
    private Matrix4d matrixTokens() {
        Matrix4d columnMajor = readTokens(false);
        if (isAffine(columnMajor)) {
            return columnMajor;
        }
        Matrix4d rowMajor = readTokens(true);
        return isAffine(rowMajor) ? rowMajor : columnMajor;
    }

    /** The tokens laid out one way or the other; the two differ by a transpose. */
    private Matrix4d readTokens(boolean rowMajor) {
        Matrix4d m = new Matrix4d();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                m.setElement(row, col, rowMajor
                        ? values[row * 4 + col] : values[col * 4 + row]);
            }
        }
        return m;
    }

    /** Whether nothing below the 3x3 survives: the bottom row is 0 0 0 1. */
    private static boolean isAffine(Matrix4d m) {
        return Math.abs(m.getElement(3, 0)) < 1e-9
                && Math.abs(m.getElement(3, 1)) < 1e-9
                && Math.abs(m.getElement(3, 2)) < 1e-9
                && Math.abs(m.getElement(3, 3) - 1.0) < 1e-9;
    }

    /**
     * The {@code <lookat>} basis: eye / center / up produce an orthonormal frame
     * whose -Z points from eye to center, the right-handed convention COLLADA
     * specifies. A degenerate eye==center collapses to the identity (a point has
     * no view direction).
     */
    private Matrix4d lookAt() {
        if (values.length < 6) {
            throw new IllegalArgumentException("<lookat> needs 6 values, found " + values.length);
        }
        org.jogamp.vecmath.Vector3d eye = new org.jogamp.vecmath.Vector3d(at(0), at(1), at(2));
        org.jogamp.vecmath.Vector3d center = new org.jogamp.vecmath.Vector3d(at(3), at(4), at(5));
        org.jogamp.vecmath.Vector3d up = new org.jogamp.vecmath.Vector3d(at(6), at(7), at(8));

        org.jogamp.vecmath.Vector3d dir = new org.jogamp.vecmath.Vector3d();
        dir.sub(eye, center);
        if (dir.lengthSquared() < 1e-12) {
            Matrix4d identity = new Matrix4d();
            identity.setIdentity();
            return identity; // identity: no view direction
        }
        dir.normalize();
        org.jogamp.vecmath.Vector3d right = new org.jogamp.vecmath.Vector3d();
        right.cross(up, dir);
        right.normalize();
        org.jogamp.vecmath.Vector3d newUp = new org.jogamp.vecmath.Vector3d();
        newUp.cross(dir, right);

        Matrix4d m = new Matrix4d();
        m.setIdentity();
        m.setElement(0, 0, right.x);
        m.setElement(0, 1, newUp.x);
        m.setElement(0, 2, dir.x);
        m.setElement(1, 0, right.y);
        m.setElement(1, 1, newUp.y);
        m.setElement(1, 2, dir.y);
        m.setElement(2, 0, right.z);
        m.setElement(2, 1, newUp.z);
        m.setElement(2, 2, dir.z);
        return m;
    }
}
