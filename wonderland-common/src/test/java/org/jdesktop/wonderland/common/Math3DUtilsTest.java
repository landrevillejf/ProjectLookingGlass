/*
 * Pin for the decompiled Math3DUtils, which is what the ported data model uses
 * for proximity tests (encloses) and for the tolerance comparisons in
 * CellTransform.epsilonEquals. The encloses(BoundingVolume, BoundingVolume)
 * overload's dispatch table and its UnsupportedOperationException path are part
 * of the contract callers depend on.
 */
package org.jdesktop.wonderland.common;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;

import com.jme.bounding.BoundingBox;
import com.jme.bounding.BoundingSphere;
import com.jme.bounding.BoundingVolume;
import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.junit.jupiter.api.Test;

class Math3DUtilsTest {

    @Test
    void floatEpsilonEqualsUsesDefaultTolerance() {
        assertThat(Math3DUtils.epsilonEquals(1f, 1f)).isTrue();
        // Default tolerance is 1.1920929E-7F; 1e-7 sits inside, 1e-4 sits outside.
        assertThat(Math3DUtils.epsilonEquals(1f, 1.0000001f)).isTrue();
        assertThat(Math3DUtils.epsilonEquals(1f, 1.0001f)).isFalse();
        assertThat(Math3DUtils.epsilonEquals(1f, 2f)).isFalse();
        // Explicit tolerance widens the check.
        assertThat(Math3DUtils.epsilonEquals(1f, 1.0001f, 0.01f)).isTrue();
    }

    @Test
    void vectorEpsilonEqualsHandlesNulls() {
        assertThat(Math3DUtils.epsilonEquals((Vector3f) null, (Vector3f) null)).isTrue();
        assertThat(Math3DUtils.epsilonEquals(new Vector3f(), (Vector3f) null)).isFalse();
        assertThat(Math3DUtils.epsilonEquals((Vector3f) null, new Vector3f())).isFalse();
        assertThat(Math3DUtils.epsilonEquals(new Vector3f(1f, 2f, 3f), new Vector3f(1.0001f, 2f, 3f), 0.01f)).isTrue();
        assertThat(Math3DUtils.epsilonEquals(new Vector3f(1f, 2f, 3f), new Vector3f(2f, 2f, 3f), 0.01f)).isFalse();
    }

    @Test
    void quaternionEpsilonEqualsComparesAngles() {
        Quaternion a = new Quaternion().fromAngleAxis(1f, new Vector3f(0f, 0f, 1f));
        Quaternion b = new Quaternion().fromAngleAxis(1f + 1e-4f, new Vector3f(0f, 0f, 1f));
        assertThat(Math3DUtils.epsilonEquals(a, b, 0.001f)).isTrue();
        assertThat(Math3DUtils.epsilonEquals(a, b, 1e-6f)).isFalse();
        assertThat(Math3DUtils.epsilonEquals((Quaternion) null, (Quaternion) null)).isTrue();
        assertThat(Math3DUtils.epsilonEquals(a, (Quaternion) null)).isFalse();
    }

    @Test
    void boundingBoxEnclosesBoundingBox() {
        BoundingBox parent = new BoundingBox(Vector3f.ZERO, 10f, 10f, 10f);
        BoundingBox inside = new BoundingBox(new Vector3f(1f, 1f, 1f), 1f, 1f, 1f);
        BoundingBox outside = new BoundingBox(new Vector3f(9f, 0f, 0f), 5f, 1f, 1f);
        assertThat(Math3DUtils.encloses(parent, inside)).isTrue();
        assertThat(Math3DUtils.encloses(parent, outside)).isFalse();
    }

    @Test
    void boundingBoxEnclosesBoundingSphere() {
        BoundingBox box = new BoundingBox(Vector3f.ZERO, 5f, 5f, 5f);
        BoundingSphere inside = new BoundingSphere(1f, Vector3f.ZERO);
        BoundingSphere poking = new BoundingSphere(2f, new Vector3f(4f, 0f, 0f));
        assertThat(Math3DUtils.encloses(box, inside)).isTrue();
        assertThat(Math3DUtils.encloses(box, poking)).isFalse();
    }

    @Test
    void boundingSphereEnclosesBoundingBox() {
        BoundingSphere sphere = new BoundingSphere(10f, Vector3f.ZERO);
        BoundingBox inside = new BoundingBox(Vector3f.ZERO, 1f, 1f, 1f);
        BoundingBox outside = new BoundingBox(new Vector3f(9f, 0f, 0f), 5f, 1f, 1f);
        assertThat(Math3DUtils.encloses(sphere, inside)).isTrue();
        assertThat(Math3DUtils.encloses(sphere, outside)).isFalse();
    }

    @Test
    void boundingSphereEnclosesBoundingSphere() {
        BoundingSphere parent = new BoundingSphere(10f, Vector3f.ZERO);
        BoundingSphere child = new BoundingSphere(1f, Vector3f.ZERO);
        BoundingSphere poking = new BoundingSphere(2f, new Vector3f(9.5f, 0f, 0f));
        assertThat(Math3DUtils.encloses(parent, child)).isTrue();
        assertThat(Math3DUtils.encloses(parent, poking)).isFalse();
    }

    @Test
    void enclosesDispatchesThroughAbstractOverload() {
        BoundingVolume parent = new BoundingBox(Vector3f.ZERO, 10f, 10f, 10f);
        BoundingVolume child = new BoundingSphere(1f, Vector3f.ZERO);
        assertThat(Math3DUtils.encloses(parent, child)).isTrue();
    }

    @Test
    void pointLineDistanceMatchesPerpendicularFormula() {
        // Line along +X from origin, point at (3, 4, 0): perpendicular distance is 4.
        float d = Math3DUtils.pointLineDistance(
                new Vector3f(0f, 0f, 0f), new Vector3f(10f, 0f, 0f), new Vector3f(3f, 4f, 0f));
        assertThat(d).isCloseTo(4f, within(1e-4f));
    }

    @Test
    void toEulerOnIdentityMatrixGivesZeroAngles() {
        com.jme.math.Matrix3f identity = new com.jme.math.Matrix3f();
        Vector3f euler = new Vector3f();
        Math3DUtils.toEuler(identity, euler);
        assertThat(euler.x).isCloseTo(0f, within(1e-4f));
        assertThat(euler.y).isCloseTo(0f, within(1e-4f));
        assertThat(euler.z).isCloseTo(0f, within(1e-4f));
    }
}
