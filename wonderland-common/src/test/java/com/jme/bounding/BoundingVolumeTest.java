/*
 * Pin for the two concrete BoundingVolume subclasses (see their class javadoc
 * for why they exist). PositionComponentServerState and the encloses / contains
 * helpers on Math3DUtils read the public extents + radius directly, so those
 * fields and the transform semantics are the surface under test here.
 */
package com.jme.bounding;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.junit.jupiter.api.Test;

class BoundingVolumeTest {

    private static final float EPS = 1e-5f;

    @Test
    void boundingBoxStoresCentreAndExtents() {
        BoundingBox box = new BoundingBox(new Vector3f(1f, 2f, 3f), 4f, 5f, 6f);
        assertThat(box.getCenter()).isEqualTo(new Vector3f(1f, 2f, 3f));
        assertThat(box.xExtent).isEqualTo(4f);
        assertThat(box.yExtent).isEqualTo(5f);
        assertThat(box.zExtent).isEqualTo(6f);
        Vector3f ext = box.getExtent(null);
        assertThat(ext).isEqualTo(new Vector3f(4f, 5f, 6f));
        Vector3f store = new Vector3f();
        assertThat(box.getExtent(store)).isSameAs(store);
        assertThat(store).isEqualTo(new Vector3f(4f, 5f, 6f));
    }

    @Test
    void boundingBoxContainsUsesAxisAlignedTest() {
        BoundingBox box = new BoundingBox(Vector3f.ZERO, 2f, 2f, 2f);
        assertThat(box.contains(new Vector3f(1f, 1f, 1f))).isTrue();
        assertThat(box.contains(new Vector3f(2f, 2f, 2f))).isTrue(); // inclusive edge
        assertThat(box.contains(new Vector3f(3f, 0f, 0f))).isFalse();
    }

    @Test
    void boundingBoxCloneIntoResultIsDeep() {
        BoundingBox box = new BoundingBox(new Vector3f(1f, 2f, 3f), 4f, 5f, 6f);
        BoundingBox copy = new BoundingBox();
        BoundingVolume result = box.clone(copy);
        assertThat(result).isSameAs(copy);
        assertThat(copy.getCenter()).isEqualTo(new Vector3f(1f, 2f, 3f));
        assertThat(copy.xExtent).isEqualTo(4f);
        copy.xExtent = 99f;
        assertThat(box.xExtent).isEqualTo(4f);
    }

    @Test
    void boundingBoxCloneWithWrongTypeAllocatesNew() {
        BoundingBox box = new BoundingBox(new Vector3f(1f, 2f, 3f), 4f, 5f, 6f);
        BoundingVolume result = box.clone(new BoundingSphere(1f, Vector3f.ZERO));
        assertThat(result).isInstanceOf(BoundingBox.class);
    }

    @Test
    void boundingBoxTransformScalesExtentsAndMovesCentre() {
        BoundingBox box = new BoundingBox(new Vector3f(1f, 0f, 0f), 1f, 1f, 1f);
        Vector3f scale = new Vector3f(2f, 3f, 4f);
        Vector3f translation = new Vector3f(10f, 0f, 0f);
        Quaternion identity = new Quaternion();
        BoundingBox result = new BoundingBox();
        box.transform(identity, translation, scale, result);
        // centre: (1,0,0)*scale -> (2,0,0) then +translation -> (12,0,0)
        assertThat(result.getCenter().x).isCloseTo(12f, within(EPS));
        assertThat(result.xExtent).isCloseTo(2f, within(EPS));
        assertThat(result.yExtent).isCloseTo(3f, within(EPS));
        assertThat(result.zExtent).isCloseTo(4f, within(EPS));
    }

    @Test
    void boundingSphereStoresRadiusAndCenter() {
        BoundingSphere s = new BoundingSphere(3f, new Vector3f(1f, 2f, 3f));
        assertThat(s.getRadius()).isEqualTo(3f);
        assertThat(s.radius).isEqualTo(3f);
        assertThat(s.getCenter()).isEqualTo(new Vector3f(1f, 2f, 3f));
    }

    @Test
    void boundingSphereContainsUsesDistance() {
        BoundingSphere s = new BoundingSphere(5f, Vector3f.ZERO);
        assertThat(s.contains(new Vector3f(3f, 4f, 0f))).isTrue();   // exactly on surface
        assertThat(s.contains(new Vector3f(6f, 0f, 0f))).isFalse();
    }

    @Test
    void boundingSphereTransformScalesRadiusAndMovesCentre() {
        BoundingSphere s = new BoundingSphere(2f, new Vector3f(1f, 0f, 0f));
        Vector3f scale = new Vector3f(3f, 3f, 3f);
        Vector3f translation = new Vector3f(10f, 0f, 0f);
        BoundingSphere result = new BoundingSphere();
        s.transform(new Quaternion(), translation, scale, result);
        assertThat(result.radius).isCloseTo(6f, within(EPS));
        assertThat(result.getCenter().x).isCloseTo(13f, within(EPS));
    }

    @Test
    void getCenterStoreIsCopiedAndReused() {
        BoundingBox box = new BoundingBox(new Vector3f(1f, 2f, 3f), 1f, 1f, 1f);
        Vector3f store = new Vector3f();
        Vector3f returned = box.getCenter(store);
        assertThat(returned).isSameAs(store).isEqualTo(new Vector3f(1f, 2f, 3f));
        assertThat(box.getCenter(null)).isNotSameAs(box.getCenter()).isEqualTo(new Vector3f(1f, 2f, 3f));
    }

    @Test
    void rotationIsAppliedBeforeTranslation() {
        // Rotate +X by 90 deg around Z, then translate by (10,0,0): centre should
        // end up at (10, 1, 0), not (11, 0, 0).
        BoundingBox box = new BoundingBox(new Vector3f(1f, 0f, 0f), 0f, 0f, 0f);
        Quaternion ninety = new Quaternion().fromAngleAxis((float) (Math.PI / 2), new Vector3f(0f, 0f, 1f));
        BoundingBox result = new BoundingBox();
        box.transform(ninety, new Vector3f(10f, 0f, 0f), new Vector3f(1f, 1f, 1f), result);
        assertThat(result.getCenter().x).isCloseTo(10f, within(EPS));
        assertThat(result.getCenter().y).isCloseTo(1f, within(EPS));
        assertThat(result.getCenter().z).isCloseTo(0f, within(EPS));
    }
}
