/*
 * In-tree reimplementation of the small subset of the j3d-contrib-utils
 * Math3D helper used by LG3D, ported to the Jogamp Java 3D 1.7 API
 * (org.jogamp.vecmath). See the traverser package for the full rationale:
 * the original binary was compiled against javax.vecmath and is incompatible
 * with the Jogamp package rename. Only pointLineDistance() is consumed by
 * lg3d-core, so that is the method reproduced here.
 */
package org.jdesktop.j3d.utils.math;

import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Point3f;
import org.jogamp.vecmath.Vector3d;
import org.jogamp.vecmath.Vector3f;

/**
 * Assorted 3D math helpers.
 */
public class Math3D {

    private Math3D() {
    }

    /**
     * Perpendicular distance from the point {@code p} to the infinite line
     * passing through {@code a} and {@code b}.
     *
     * <p>Computed as {@code |ap x ab| / |ab|}. If {@code a} and {@code b}
     * coincide the distance from {@code a} to {@code p} is returned.
     */
    public static float pointLineDistance(Point3f a, Point3f b, Point3f p) {
        Vector3f ab = new Vector3f();
        ab.sub(b, a);
        float len = ab.length();

        Vector3f ap = new Vector3f();
        ap.sub(p, a);

        if (len == 0.0f)
            return ap.length();

        Vector3f cross = new Vector3f();
        cross.cross(ap, ab);
        return cross.length() / len;
    }

    /**
     * Double-precision variant of {@link #pointLineDistance(Point3f, Point3f,
     * Point3f)}.
     */
    public static double pointLineDistance(Point3d a, Point3d b, Point3d p) {
        Vector3d ab = new Vector3d();
        ab.sub(b, a);
        double len = ab.length();

        Vector3d ap = new Vector3d();
        ap.sub(p, a);

        if (len == 0.0)
            return ap.length();

        Vector3d cross = new Vector3d();
        cross.cross(ap, ab);
        return cross.length() / len;
    }
}
