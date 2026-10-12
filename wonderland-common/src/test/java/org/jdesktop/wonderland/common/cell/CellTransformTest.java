/*
 * Pin for the decompiled CellTransform, exercising the paths that go through the
 * in-tree jME shim (rotation.multLocal, translation.addLocal/multLocal,
 * Quaternion.clone/Vector3f.clone) and the paths that go through real vecmath
 * (mul / invert, which build a Matrix4d internally). If any shim method drifts
 * the transform pipeline these break first.
 */
package org.jdesktop.wonderland.common.cell;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;

import com.jme.bounding.BoundingBox;
import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.junit.jupiter.api.Test;

class CellTransformTest {

    private static final float EPS = 1e-4f;

    @Test
    void defaultCtorIsIdentityTransform() {
        CellTransform t = new CellTransform();
        assertThat(t.getScaling()).isEqualTo(1f);
        assertThat(t.getTranslation(null)).isEqualTo(new Vector3f(0f, 0f, 0f));
        assertThat(t.getRotation(null)).isEqualTo(new Quaternion());
    }

    @Test
    void copyOnCtorClonesInputsSoLaterMutationDoesNotAlias() {
        Vector3f tr = new Vector3f(1f, 2f, 3f);
        Quaternion rot = new Quaternion();
        CellTransform t = new CellTransform(rot, tr, 2f);
        tr.set(99f, 99f, 99f);
        assertThat(t.getTranslation(null)).isEqualTo(new Vector3f(1f, 2f, 3f));
        assertThat(t.getScaling()).isEqualTo(2f);
    }

    @Test
    void transformAppliesScaleThenRotationThenTranslation() {
        Quaternion ninety = new Quaternion().fromAngleAxis((float) (Math.PI / 2), new Vector3f(0f, 0f, 1f));
        CellTransform t = new CellTransform(ninety, new Vector3f(10f, 0f, 0f), 2f);
        // +X scaled by 2 -> (2,0,0); rot 90 around Z -> (0,2,0); +translation -> (10,2,0)
        Vector3f p = new Vector3f(1f, 0f, 0f);
        t.transform(p);
        assertThat(p.x).isCloseTo(10f, within(EPS));
        assertThat(p.y).isCloseTo(2f, within(EPS));
        assertThat(p.z).isCloseTo(0f, within(EPS));
    }

    @Test
    void getLookAtReturnsOriginPlusZDirection() {
        CellTransform t = new CellTransform(new Quaternion(), new Vector3f(0f, 0f, 0f), 1f);
        Vector3f position = new Vector3f();
        Vector3f look = new Vector3f();
        t.getLookAt(position, look);
        assertThat(position).isEqualTo(new Vector3f(0f, 0f, 0f));
        // identity rotation, no translation: +Z stays +Z after normalise
        assertThat(look.z).isCloseTo(1f, within(EPS));
        assertThat(look.x).isCloseTo(0f, within(EPS));
        assertThat(look.y).isCloseTo(0f, within(EPS));
    }

    @Test
    void mulComposesThisInPlaceWithGivenTransform() {
        // this = translate(1,0,0), in = translate(0,2,0), so this*in = translate(1,2,0)
        CellTransform a = new CellTransform(new Quaternion(), new Vector3f(1f, 0f, 0f), 1f);
        CellTransform b = new CellTransform(new Quaternion(), new Vector3f(0f, 2f, 0f), 1f);
        a.mul(b);
        Vector3f tr = a.getTranslation(null);
        assertThat(tr.x).isCloseTo(1f, within(EPS));
        assertThat(tr.y).isCloseTo(2f, within(EPS));
        assertThat(tr.z).isCloseTo(0f, within(EPS));
    }

    @Test
    void invertCancelsAPureTranslation() {
        CellTransform t = new CellTransform(new Quaternion(), new Vector3f(3f, 4f, 5f), 1f);
        t.invert();
        Vector3f tr = t.getTranslation(null);
        assertThat(tr.x).isCloseTo(-3f, within(EPS));
        assertThat(tr.y).isCloseTo(-4f, within(EPS));
        assertThat(tr.z).isCloseTo(-5f, within(EPS));
    }

    @Test
    void mulThenInvertReturnsRoughlyOriginalTranslation() {
        CellTransform original = new CellTransform(new Quaternion(), new Vector3f(1f, 2f, 3f), 1f);
        CellTransform other = new CellTransform(new Quaternion(), new Vector3f(10f, 0f, 0f), 1f);
        CellTransform combined = new CellTransform().mul(original, other);
        combined.invert();
        // combined was (11,2,3); inverted translation is roughly (-11, -2, -3)
        Vector3f tr = combined.getTranslation(null);
        assertThat(tr.x).isCloseTo(-11f, within(EPS));
        assertThat(tr.y).isCloseTo(-2f, within(EPS));
        assertThat(tr.z).isCloseTo(-3f, within(EPS));
    }

    @Test
    void subIsComponentwiseAndIndependentOfVecmathPath() {
        CellTransform a = new CellTransform(new Quaternion(1f, 2f, 3f, 4f), new Vector3f(10f, 20f, 30f), 5f);
        CellTransform b = new CellTransform(new Quaternion(1f, 1f, 1f, 1f), new Vector3f(1f, 2f, 3f), 1f);
        a.sub(b);
        assertThat(a.getTranslation(null)).isEqualTo(new Vector3f(9f, 18f, 27f));
        assertThat(a.getScaling()).isEqualTo(4f);
    }

    @Test
    void cloneIntoResultCopiesEveryComponent() {
        CellTransform src = new CellTransform(new Quaternion(0f, 0f, 0.7071f, 0.7071f), new Vector3f(1f, 2f, 3f), 2f);
        CellTransform result = src.clone(new CellTransform());
        assertThat(result.getTranslation(null)).isEqualTo(src.getTranslation(null));
        assertThat(result.getRotation(null)).isEqualTo(src.getRotation(null));
        assertThat(result.getScaling()).isEqualTo(src.getScaling());
        assertThat(src.clone(null)).isNotSameAs(src);
    }

    @Test
    void transformOfBoundingBoxMovesItsCentre() {
        CellTransform t = new CellTransform(new Quaternion(), new Vector3f(10f, 0f, 0f), 1f);
        BoundingBox box = new BoundingBox(Vector3f.ZERO, 1f, 1f, 1f);
        t.transform(box);
        assertThat(box.getCenter().x).isCloseTo(10f, within(EPS));
    }

    @Test
    void epsilonEqualsIgnoresTinyDifferencesAndRejectsNull() {
        CellTransform a = new CellTransform(new Quaternion(), new Vector3f(1f, 2f, 3f), 1f);
        CellTransform b = new CellTransform(new Quaternion(), new Vector3f(1.0001f, 2f, 3f), 1f);
        assertThat(a.epsilonEquals(b)).isTrue();
        assertThat(a.epsilonEquals(null)).isFalse();
        CellTransform c = new CellTransform(new Quaternion(), new Vector3f(99f, 2f, 3f), 1f);
        assertThat(a.epsilonEquals(c)).isFalse();
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        CellTransform a = new CellTransform(new Quaternion(), new Vector3f(1f, 2f, 3f), 2f);
        CellTransform b = new CellTransform(new Quaternion(), new Vector3f(1f, 2f, 3f), 2f);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo("nope");
        assertThat(a).isNotEqualTo(new CellTransform(new Quaternion(), new Vector3f(1f, 2f, 3f), 3f));
    }

    @Test
    void toStringIncludesTranslationRotationAndScale() {
        CellTransform t = new CellTransform(new Quaternion(), new Vector3f(1f, 2f, 3f), 4f);
        String s = t.toString();
        assertThat(s).contains("tx=1.0").contains("ty=2.0").contains("tz=3.0").contains("[s=4.0]");
    }

    @Test
    void setTranslationAndRotationCopyIntoExistingFields() {
        CellTransform t = new CellTransform();
        t.setTranslation(new Vector3f(4f, 5f, 6f));
        t.setRotation(new Quaternion(0f, 0f, 0.7071f, 0.7071f));
        assertThat(t.getTranslation(null)).isEqualTo(new Vector3f(4f, 5f, 6f));
        assertThat(t.getRotation(null)).isEqualTo(new Quaternion(0f, 0f, 0.7071f, 0.7071f));
    }

    @Test
    void getScalingVectorVersionExpandsScalarToThreeAxes() {
        CellTransform t = new CellTransform(new Quaternion(), new Vector3f(), 3f);
        Vector3f sv = t.getScaling(new Vector3f());
        assertThat(sv).isEqualTo(new Vector3f(3f, 3f, 3f));
    }
}
