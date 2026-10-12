/*
 * In-tree replacement for jMonkeyEngine's com.jme.bounding.BoundingBox.
 *
 * Axis-aligned box as used by the Wonderland data model: public centre + per-axis
 * extents (PositionComponentServerState reads xExtent/yExtent/zExtent directly),
 * the getExtent(Vector3f) accessor the encloses/contains helpers call, point
 * containment, clone-into-result and the scale-rotate-translate transform.
 */
package com.jme.bounding;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;

public class BoundingBox extends BoundingVolume {

    public float xExtent;
    public float yExtent;
    public float zExtent;

    public BoundingBox() {
    }

    public BoundingBox(Vector3f center, float xExtent, float yExtent, float zExtent) {
        this.center.set(center);
        this.xExtent = xExtent;
        this.yExtent = yExtent;
        this.zExtent = zExtent;
    }

    public Vector3f getExtent(Vector3f ext) {
        if (ext == null) {
            ext = new Vector3f();
        }
        ext.set(xExtent, yExtent, zExtent);
        return ext;
    }

    public void setExtents(float xExtent, float yExtent, float zExtent) {
        this.xExtent = xExtent;
        this.yExtent = yExtent;
        this.zExtent = zExtent;
    }

    @Override
    public boolean contains(Vector3f point) {
        return Math.abs(point.x - center.x) <= xExtent
                && Math.abs(point.y - center.y) <= yExtent
                && Math.abs(point.z - center.z) <= zExtent;
    }

    @Override
    public BoundingVolume clone(BoundingVolume result) {
        if (!(result instanceof BoundingBox)) {
            result = new BoundingBox();
        }
        BoundingBox box = (BoundingBox) result;
        box.center.set(center);
        box.xExtent = xExtent;
        box.yExtent = yExtent;
        box.zExtent = zExtent;
        return box;
    }

    @Override
    public void transform(Quaternion rotation, Vector3f translation, Vector3f scale,
            BoundingVolume result) {
        if (!(result instanceof BoundingBox)) {
            result = new BoundingBox();
        }
        BoundingBox box = (BoundingBox) result;
        transformCenter(rotation, translation, scale, box.center);
        // An oriented box's world extent grows with the rotated axes; the data model
        // keeps boxes axis-aligned, so scale each axis directly.
        box.xExtent = xExtent * Math.abs(scale.x);
        box.yExtent = yExtent * Math.abs(scale.y);
        box.zExtent = zExtent * Math.abs(scale.z);
    }
}
