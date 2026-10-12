/*
 * In-tree replacement for jMonkeyEngine's com.jme.bounding.BoundingSphere.
 *
 * Radius-sphere as used by the Wonderland data model: the public radius field
 * (PositionComponentServerState reads it directly), the (radius, center) constructor
 * used everywhere, point containment by distance, clone-into-result and the
 * scale-rotate-translate transform (radius scales with the transform).
 */
package com.jme.bounding;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;

public class BoundingSphere extends BoundingVolume {

    public float radius;

    public BoundingSphere() {
    }

    public BoundingSphere(float radius, Vector3f center) {
        this.radius = radius;
        this.center.set(center);
    }

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = radius;
    }

    @Override
    public boolean contains(Vector3f point) {
        return center.distance(point) <= radius;
    }

    @Override
    public BoundingVolume clone(BoundingVolume result) {
        if (!(result instanceof BoundingSphere)) {
            result = new BoundingSphere();
        }
        BoundingSphere sphere = (BoundingSphere) result;
        sphere.center.set(center);
        sphere.radius = radius;
        return sphere;
    }

    @Override
    public void transform(Quaternion rotation, Vector3f translation, Vector3f scale,
            BoundingVolume result) {
        if (!(result instanceof BoundingSphere)) {
            result = new BoundingSphere();
        }
        BoundingSphere sphere = (BoundingSphere) result;
        transformCenter(rotation, translation, scale, sphere.center);
        sphere.radius = radius * Math.abs(scale.x);
    }
}
