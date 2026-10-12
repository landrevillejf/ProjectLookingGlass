/*
 * In-tree replacement for jMonkeyEngine's com.jme.bounding.BoundingVolume.
 *
 * The ported data model carries bounding volumes as *data*: cell bounds hints, the
 * proximity listener's local/world bounds, and the JAXB BoundingVolumeAdapter that
 * marshals a SPHERE or BOX by its centre + extent/radius. It never runs the full jME
 * collision pipeline here. This abstract type therefore provides the centre accessors,
 * point containment, clone-into-result and the scale-rotate-translate transform the
 * data model calls; concrete geometry lives in BoundingBox / BoundingSphere and is
 * pinned by com.jme.bounding.BoundingVolumeTest.
 */
package com.jme.bounding;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.io.Serializable;

public abstract class BoundingVolume implements Serializable {

    protected Vector3f center = new Vector3f();

    public Vector3f getCenter() {
        return center;
    }

    public Vector3f getCenter(Vector3f store) {
        if (store == null) {
            store = new Vector3f();
        }
        store.set(center);
        return store;
    }

    public void setCenter(Vector3f center) {
        this.center.set(center);
    }

    /** True when the world-space point lies inside this volume. */
    public abstract boolean contains(Vector3f point);

    /** Deep-copy into {@code result} (a new volume when result is null or the wrong kind). */
    public abstract BoundingVolume clone(BoundingVolume result);

    /** Apply scale, then rotation, then translation of {@code this} into {@code result}. */
    public abstract void transform(Quaternion rotation, Vector3f translation, Vector3f scale,
            BoundingVolume result);

    /**
     * Grow this volume in place so it encloses {@code toMerge} (jME's mergeLocal, the
     * cell tree's world-bounds aggregation). A null argument is a no-op: the tree update
     * merges children that carry no bounds hint, and the 2006 code leaned on the same
     * silent behaviour. Merging across volume kinds is rejected -- the tree only ever
     * aggregates like-for-like hints.
     */
    public BoundingVolume mergeLocal(BoundingVolume toMerge) {
        if (toMerge != null) {
            mergeLocalInto(toMerge);
        }
        return this;
    }

    /** Kind-specific in-place union; {@code toMerge} is non-null and same-class. */
    protected abstract void mergeLocalInto(BoundingVolume toMerge);

    /** Shared centre transform: result = rotation * (center * scale) + translation. */
    protected final void transformCenter(Quaternion rotation, Vector3f translation, Vector3f scale,
            Vector3f target) {
        target.set(center);
        target.multLocal(scale.x, scale.y, scale.z);
        rotation.multLocal(target);
        target.addLocal(translation);
    }
}
