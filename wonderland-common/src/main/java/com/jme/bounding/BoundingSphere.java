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
    protected void mergeLocalInto(BoundingVolume toMerge) {
        if (!(toMerge instanceof BoundingSphere)) {
            throw new IllegalArgumentException("Cannot merge " + toMerge.getClass().getName()
                    + " into a BoundingSphere");
        }
        BoundingSphere other = (BoundingSphere) toMerge;
        if (other.radius > radius) {
            // start from the larger volume so the enclosing cap is measured from it
            other.mergeLocalInto(this);
            center.set(other.center);
            radius = other.radius;
            return;
        }
        float distance = center.distance(other.center);
        if (distance + other.radius <= radius) {
            return; // other already enclosed
        }
        // smallest covering sphere along the centre axis: the two far caps define the
        // new diameter -- midpoint of the caps is the new centre, half the span the
        // new radius. Computed into fresh vectors; the centre field is written last.
        Vector3f axis = new Vector3f(other.center);
        axis.subtractLocal(center);
        if (distance > 0) {
            axis.normalizeLocal();
        }
        Vector3f high = new Vector3f(axis);
        high.multLocal(radius);
        high.addLocal(center); // this sphere's far cap
        Vector3f low = new Vector3f(axis);
        low.multLocal(-other.radius);
        low.addLocal(other.center); // other sphere's far cap
        Vector3f mid = new Vector3f(high);
        mid.addLocal(low);
        mid.multLocal(0.5f);
        center.set(mid);
        // reach the further far-cap AND fully swallow the other sphere
        radius = Math.max(center.distance(high), other.radius + center.distance(other.center));
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
