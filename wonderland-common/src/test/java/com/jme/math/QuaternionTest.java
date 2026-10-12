/*
 * Correctness pin for the in-tree jME Quaternion shim. multLocal (vector
 * rotation), the fromAngleAxis / toAngleAxis round-trip and mult (quaternion
 * composition) are the three operations the ported data model relies on for
 * CellTransform.mul, CellTransform.invert and Math3DUtils.epsilonEquals.
 */
package com.jme.math;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuaternionTest {

    private static final float EPS = 1e-5f;

    @Test
    void defaultConstructorIsIdentity() {
        Quaternion q = new Quaternion();
        assertThat(q.x).isEqualTo(0f);
        assertThat(q.y).isEqualTo(0f);
        assertThat(q.z).isEqualTo(0f);
        assertThat(q.w).isEqualTo(1f);
    }

    @Test
    void copyConstructorAndSetAreDeep() {
        Quaternion src = new Quaternion(1f, 2f, 3f, 4f);
        Quaternion copy = new Quaternion(src);
        src.set(9f, 9f, 9f, 9f);
        assertThat(copy).isEqualTo(new Quaternion(1f, 2f, 3f, 4f));
        copy.set(new Quaternion(5f, 6f, 7f, 8f));
        assertThat(copy).isEqualTo(new Quaternion(5f, 6f, 7f, 8f));
    }

    @Test
    void cloneReturnsConcreteType() {
        Quaternion q = new Quaternion(1f, 2f, 3f, 4f);
        Quaternion c = q.clone();
        assertThat(c).isEqualTo(q).isNotSameAs(q);
    }

    @Test
    void identityRotationLeavesVectorUnchanged() {
        Quaternion q = new Quaternion();
        Vector3f v = new Vector3f(1f, 2f, 3f);
        q.multLocal(v);
        assertThat(v.x).isCloseTo(1f, within(EPS));
        assertThat(v.y).isCloseTo(2f, within(EPS));
        assertThat(v.z).isCloseTo(3f, within(EPS));
    }

    @Test
    void ninetyDegAroundZMapsUnitXToUnitY() {
        Quaternion q = new Quaternion().fromAngleAxis((float) (Math.PI / 2), new Vector3f(0f, 0f, 1f));
        Vector3f v = new Vector3f(1f, 0f, 0f);
        q.multLocal(v);
        assertThat(v.x).isCloseTo(0f, within(EPS));
        assertThat(v.y).isCloseTo(1f, within(EPS));
        assertThat(v.z).isCloseTo(0f, within(EPS));
    }

    @Test
    void oneEightyDegAroundYInvertsUnitZ() {
        Quaternion q = new Quaternion().fromAngleAxis((float) Math.PI, new Vector3f(0f, 1f, 0f));
        Vector3f v = new Vector3f(0f, 0f, 1f);
        q.multLocal(v);
        assertThat(v.x).isCloseTo(0f, within(EPS));
        assertThat(v.y).isCloseTo(0f, within(EPS));
        assertThat(v.z).isCloseTo(-1f, within(EPS));
    }

    @Test
    void fromAngleAxisThenToAngleAxisRoundTrips() {
        Vector3f axisIn = new Vector3f(1f, 2f, 3f).normalizeLocal();
        float angleIn = 1.234f;
        Quaternion q = new Quaternion().fromAngleAxis(angleIn, axisIn);
        Vector3f axisOut = new Vector3f();
        float angleOut = q.toAngleAxis(axisOut);
        assertThat(angleOut).isCloseTo(angleIn, within(1e-4f));
        assertThat(axisOut.x).isCloseTo(axisIn.x, within(1e-4f));
        assertThat(axisOut.y).isCloseTo(axisIn.y, within(1e-4f));
        assertThat(axisOut.z).isCloseTo(axisIn.z, within(1e-4f));
    }

    @Test
    void toAngleAxisNormalisesDirtyInput() {
        Quaternion q = new Quaternion(0f, 0f, 2f, 2f); // 90 deg around Z but non-unit
        Vector3f axis = new Vector3f();
        float angle = q.toAngleAxis(axis);
        assertThat(angle).isCloseTo((float) (Math.PI / 2), within(1e-4f));
        assertThat(axis.z).isCloseTo(1f, within(1e-4f));
    }

    @Test
    void multComposesTwoRotations() {
        float ninety = (float) (Math.PI / 2);
        Quaternion rz = new Quaternion().fromAngleAxis(ninety, new Vector3f(0f, 0f, 1f));
        Quaternion combined = new Quaternion(rz).mult(rz);
        // two 90 deg rotations around Z == one 180 deg rotation around Z
        Vector3f v = new Vector3f(1f, 0f, 0f);
        combined.multLocal(v);
        assertThat(v.x).isCloseTo(-1f, within(EPS));
        assertThat(v.y).isCloseTo(0f, within(EPS));
    }

    @Test
    void subtractIsComponentwise() {
        Quaternion a = new Quaternion(4f, 5f, 6f, 7f);
        Quaternion b = new Quaternion(1f, 2f, 3f, 4f);
        a.subtract(b);
        assertThat(a).isEqualTo(new Quaternion(3f, 3f, 3f, 3f));
    }

    @Test
    void normalizeMakesUnitLength() {
        Quaternion q = new Quaternion(0f, 0f, 3f, 4f); // length 5
        q.normalizeLocal();
        assertThat(q.lengthSquared()).isCloseTo(1f, within(EPS));
        assertThat(q.z).isCloseTo(0.6f, within(EPS));
        assertThat(q.w).isCloseTo(0.8f, within(EPS));
    }

    @Test
    void toAnglesReturnsThreeValuesInRollPitchYawOrder() {
        // 90 deg around Z: yaw = pi/2 (roll/pitch zero for a pure-z rotation)
        Quaternion q = new Quaternion().fromAngleAxis((float) (Math.PI / 2), new Vector3f(0f, 0f, 1f));
        float[] angles = q.toAngles(null);
        assertThat(angles).hasSize(3);
        assertThat(angles[2]).isCloseTo((float) (Math.PI / 2), within(1e-4f));
        assertThat(angles[0]).isCloseTo(0f, within(1e-4f));
        assertThat(angles[1]).isCloseTo(0f, within(1e-4f));
    }

    @Test
    void equalsHashCodeAndToStringMatchByValue() {
        Quaternion a = new Quaternion(1f, 2f, 3f, 4f);
        Quaternion b = new Quaternion(1f, 2f, 3f, 4f);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).isEqualTo("(1.0, 2.0, 3.0, 4.0)");
        assertThat(a).isNotEqualTo("nope");
    }
}
