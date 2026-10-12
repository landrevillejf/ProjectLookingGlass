/*
 * Correctness pin for the in-tree jME Vector3f shim (see the class javadoc for
 * why the shim exists). These assertions are the reason downstream ported code
 * (CellTransform, the movable messages, the bounding volumes) can be trusted to
 * behave as it did under the original jMonkeyEngine 1.x jar.
 */
package com.jme.math;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Vector3fTest {

    @Test
    void staticsHaveTheExpectedComponents() {
        assertThat(Vector3f.ZERO).isEqualTo(new Vector3f(0f, 0f, 0f));
        assertThat(Vector3f.UNIT_X).isEqualTo(new Vector3f(1f, 0f, 0f));
        assertThat(Vector3f.UNIT_Y).isEqualTo(new Vector3f(0f, 1f, 0f));
        assertThat(Vector3f.UNIT_Z).isEqualTo(new Vector3f(0f, 0f, 1f));
    }

    @Test
    void copyConstructorAndSetAreDeep() {
        Vector3f src = new Vector3f(1f, 2f, 3f);
        Vector3f copy = new Vector3f(src);
        src.addLocal(new Vector3f(10f, 0f, 0f));
        assertThat(copy).isEqualTo(new Vector3f(1f, 2f, 3f));
        copy.set(new Vector3f(4f, 5f, 6f));
        assertThat(copy).isEqualTo(new Vector3f(4f, 5f, 6f));
    }

    @Test
    void cloneReturnsConcreteTypeSoCallersDontC() {
        Vector3f src = new Vector3f(1f, 2f, 3f);
        Vector3f clone = src.clone();
        assertThat(clone).isEqualTo(src).isNotSameAs(src);
    }

    @Test
    void addSubtractAndScalarMultiplyMutateInPlace() {
        Vector3f v = new Vector3f(1f, 2f, 3f);
        v.addLocal(new Vector3f(10f, 20f, 30f));
        assertThat(v).isEqualTo(new Vector3f(11f, 22f, 33f));
        v.subtractLocal(new Vector3f(1f, 2f, 3f));
        assertThat(v).isEqualTo(new Vector3f(10f, 20f, 30f));
        v.multLocal(0.1f);
        assertThat(v.x).isCloseTo(1f, within(1e-5f));
        assertThat(v.y).isCloseTo(2f, within(1e-5f));
        assertThat(v.z).isCloseTo(3f, within(1e-5f));
    }

    @Test
    void multLocalPerAxisAndByVectorAreComponentwise() {
        Vector3f v = new Vector3f(2f, 3f, 4f);
        v.multLocal(2f, 3f, 4f);
        assertThat(v).isEqualTo(new Vector3f(4f, 9f, 16f));
        v.multLocal(new Vector3f(1f, 2f, 3f));
        assertThat(v).isEqualTo(new Vector3f(4f, 18f, 48f));
    }

    @Test
    void negateAndZero() {
        Vector3f v = new Vector3f(1f, -2f, 3f);
        v.negateLocal();
        assertThat(v).isEqualTo(new Vector3f(-1f, 2f, -3f));
        v.zero();
        assertThat(v).isEqualTo(Vector3f.ZERO);
    }

    @Test
    void dotProductMatchesHandComputation() {
        Vector3f a = new Vector3f(1f, 2f, 3f);
        Vector3f b = new Vector3f(4f, -5f, 6f);
        // 1*4 + 2*(-5) + 3*6 = 4 - 10 + 18 = 12
        assertThat(a.dot(b)).isCloseTo(12f, within(1e-6f));
    }

    @Test
    void crossProductFollowsRightHandRule() {
        Vector3f x = new Vector3f(1f, 0f, 0f);
        Vector3f y = new Vector3f(0f, 1f, 0f);
        Vector3f z = new Vector3f();
        z.cross(x, y);
        assertThat(z).isEqualTo(new Vector3f(0f, 0f, 1f));
        // single-arg form uses "this" as v1
        Vector3f w = new Vector3f(1f, 0f, 0f);
        w.cross(y);
        assertThat(w).isEqualTo(new Vector3f(0f, 0f, 1f));
    }

    @Test
    void lengthNormalizeAndDistance() {
        Vector3f v = new Vector3f(3f, 4f, 0f);
        assertThat(v.lengthSquared()).isCloseTo(25f, within(1e-6f));
        assertThat(v.length()).isCloseTo(5f, within(1e-6f));
        v.normalizeLocal();
        assertThat(v.length()).isCloseTo(1f, within(1e-6f));
        assertThat(v.x).isCloseTo(0.6f, within(1e-6f));
        assertThat(v.y).isCloseTo(0.8f, within(1e-6f));
        assertThat(new Vector3f(0f, 0f, 0f).distance(new Vector3f(3f, 4f, 0f)))
                .isCloseTo(5f, within(1e-6f));
    }

    @Test
    void normalizeOfZeroVectorStaysZero() {
        Vector3f v = new Vector3f(0f, 0f, 0f);
        v.normalizeLocal();
        assertThat(v).isEqualTo(Vector3f.ZERO);
    }

    @Test
    void angleBetweenUsesLengthIndependentFormula() {
        Vector3f a = new Vector3f(1f, 0f, 0f);
        Vector3f b = new Vector3f(0f, 1f, 0f);
        assertThat(a.angleBetween(b)).isCloseTo((float) (Math.PI / 2), within(1e-6f));
        // scaling one side must not change the angle
        Vector3f bLong = new Vector3f(0f, 100f, 0f);
        assertThat(a.angleBetween(bLong)).isCloseTo((float) (Math.PI / 2), within(1e-6f));
        // parallel vectors: 0
        assertThat(a.angleBetween(new Vector3f(5f, 0f, 0f))).isCloseTo(0f, within(1e-6f));
    }

    @Test
    void equalsHashCodeAndToStringMatchByValue() {
        Vector3f a = new Vector3f(1f, 2f, 3f);
        Vector3f b = new Vector3f(1f, 2f, 3f);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).isEqualTo("(1.0, 2.0, 3.0)");
        assertThat(a).isNotEqualTo("not-a-vector");
    }

    @Test
    void gettersReturnFields() {
        Vector3f v = new Vector3f(7f, 8f, 9f);
        assertThat(v.getX()).isEqualTo(7f);
        assertThat(v.getY()).isEqualTo(8f);
        assertThat(v.getZ()).isEqualTo(9f);
    }
}
