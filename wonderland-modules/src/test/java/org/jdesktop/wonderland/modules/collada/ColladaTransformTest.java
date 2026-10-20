/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): the node transform fold.
 * Pins every COLLADA 1.4.1 transform to its vecmath Matrix4d -- the column-major
 * <matrix>, the component-defaulted translate/scale, the degrees->radians rotate,
 * the lookat basis (and its degenerate eye==center identity) -- plus the arity
 * guards. Pure maths, no scene graph, so every branch is reachable headless.
 */
package org.jdesktop.wonderland.modules.collada;

import org.jogamp.vecmath.Matrix4d;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ColladaTransformTest {

    private static ColladaTransform of(ColladaTransform.Kind kind, double... values) {
        return new ColladaTransform(kind, "sid", values);
    }

    @Test
    void aNullKindIsRejectedAndNullValuesReadAsEmpty() {
        assertThatThrownBy(() -> new ColladaTransform(null, "s", new double[0]))
                .isInstanceOf(IllegalArgumentException.class);

        ColladaTransform empty = new ColladaTransform(ColladaTransform.Kind.TRANSLATE, null, null);
        assertThat(empty.kind()).isEqualTo(ColladaTransform.Kind.TRANSLATE);
        assertThat(empty.sid()).isNull();
        assertThat(empty.values()).isEmpty();
    }

    @Test
    void matrixIsColumnMajor() {
        double[] v = new double[16];
        for (int i = 0; i < 16; i++) {
            v[i] = i;
        }
        Matrix4d m = of(ColladaTransform.Kind.MATRIX, v).toMatrix();

        // token[col*4+row] -> element(row, col): M00=v[0], M10=v[1], M01=v[4]
        assertThat(m.getElement(0, 0)).isCloseTo(0.0, within(1e-9));
        assertThat(m.getElement(1, 0)).isCloseTo(1.0, within(1e-9));
        assertThat(m.getElement(0, 1)).isCloseTo(4.0, within(1e-9));
        // the translation column reads tokens 12..15
        assertThat(m.getElement(0, 3)).isCloseTo(12.0, within(1e-9));
        assertThat(m.getElement(1, 3)).isCloseTo(13.0, within(1e-9));
        assertThat(m.getElement(2, 3)).isCloseTo(14.0, within(1e-9));
    }

    @Test
    void aShortMatrixIsAnError() {
        assertThatThrownBy(() -> of(ColladaTransform.Kind.MATRIX, 1, 2, 3).toMatrix())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs 16 values");
    }

    @Test
    void aRowMajorAffineMatrixIsChosenWhenTheColumnMajorReadingIsNot() {
        // The shipped town/combined <matrix> tokens carry their translation at 3, 7,
        // 11 (row-major); read column-major the bottom row is 5 6 7 1 -- not affine --
        // so the fold picks the row-major reading, whose bottom row is the canonical
        // 0 0 0 1 and whose translation lands back in the fourth column.
        double[] rowMajorTranslation = {
                1, 0, 0, 5,
                0, 1, 0, 6,
                0, 0, 1, 7,
                0, 0, 0, 1};
        Matrix4d m = of(ColladaTransform.Kind.MATRIX, rowMajorTranslation).toMatrix();

        assertThat(m.getElement(3, 0)).isCloseTo(0.0, within(1e-9)); // chosen reading is affine
        assertThat(m.getElement(0, 3)).isCloseTo(5.0, within(1e-9));
        assertThat(m.getElement(1, 3)).isCloseTo(6.0, within(1e-9));
        assertThat(m.getElement(2, 3)).isCloseTo(7.0, within(1e-9));
    }

    @Test
    void aMatrixAffineInNeitherReadingFallsBackToTheColumnMajorReading() {
        // isAffine() probes the bottom row term by term (0, then 0, then 0, then 1);
        // a matrix that fails at each successive term -- and whose row-major transpose
        // also fails -- is genuinely non-affine, so the fold keeps the spec-conformant
        // column-major reading rather than guessing. Each variant fails at a different
        // term, and each returns the column-major reading (element(3,n) is token 4n+3).
        double[] failsSecondTerm = {   // column-major bottom row 0,9,0,1
                1, 0, 0, 0,
                0, 1, 0, 9,
                0, 0, 1, 0,
                9, 0, 0, 1};
        double[] failsThirdTerm = {    // column-major bottom row 0,0,8,1
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 8,
                0, 7, 0, 1};
        double[] failsFourthTerm = {   // column-major bottom row 0,0,0,2
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 6, 2};

        assertThat(of(ColladaTransform.Kind.MATRIX, failsSecondTerm).toMatrix()
                .getElement(3, 1)).isCloseTo(9.0, within(1e-9));
        assertThat(of(ColladaTransform.Kind.MATRIX, failsThirdTerm).toMatrix()
                .getElement(3, 2)).isCloseTo(8.0, within(1e-9));
        assertThat(of(ColladaTransform.Kind.MATRIX, failsFourthTerm).toMatrix()
                .getElement(3, 3)).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void translateUsesComponentDefaultsForMissingAxes() {
        Matrix4d m = of(ColladaTransform.Kind.TRANSLATE, 5.0).toMatrix();
        assertThat(m.getElement(0, 3)).isCloseTo(5.0, within(1e-9));
        assertThat(m.getElement(1, 3)).isCloseTo(0.0, within(1e-9)); // at(1) absent -> 0
        assertThat(m.getElement(2, 3)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void rotateFoldsDegreesToRadiansAboutTheAxis() {
        Matrix4d m = of(ColladaTransform.Kind.ROTATE, 0, 0, 1, 90).toMatrix();
        // 90 deg about Z maps +X to +Y
        assertThat(m.getElement(0, 0)).isCloseTo(0.0, within(1e-9));
        assertThat(m.getElement(1, 0)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void aShortRotateIsAnError() {
        assertThatThrownBy(() -> of(ColladaTransform.Kind.ROTATE, 0, 0, 1).toMatrix())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs 4 values");
    }

    @Test
    void scaleAndUniformScalePopulateTheDiagonal() {
        Matrix4d scale = of(ColladaTransform.Kind.SCALE, 2, 3, 4).toMatrix();
        assertThat(scale.getElement(0, 0)).isCloseTo(2.0, within(1e-9));
        assertThat(scale.getElement(1, 1)).isCloseTo(3.0, within(1e-9));
        assertThat(scale.getElement(2, 2)).isCloseTo(4.0, within(1e-9));

        Matrix4d uniform = of(ColladaTransform.Kind.UNIFORM_SCALE, 7).toMatrix();
        assertThat(uniform.getElement(0, 0)).isCloseTo(7.0, within(1e-9));
        assertThat(uniform.getElement(1, 1)).isCloseTo(7.0, within(1e-9));
        assertThat(uniform.getElement(2, 2)).isCloseTo(7.0, within(1e-9));
    }

    @Test
    void lookatBuildsARightHandedFrame() {
        // eye +Z looking at origin, up +Y -> the frame's -Z points eye->center
        Matrix4d m = of(ColladaTransform.Kind.LOOKAT,
                0, 0, 10,   // eye
                0, 0, 0,    // center
                0, 1, 0).toMatrix(); // up
        assertThat(m.getElement(2, 2)).isCloseTo(1.0, within(1e-9)); // dir = (eye-center) normalised = +Z
        assertThat(m.getElement(1, 1)).isCloseTo(1.0, within(1e-9)); // newUp ~ +Y
    }

    @Test
    void aDegenerateLookatCollapsesToIdentity() {
        Matrix4d m = of(ColladaTransform.Kind.LOOKAT,
                1, 2, 3, 1, 2, 3, 0, 1, 0).toMatrix(); // eye == center
        Matrix4d identity = new Matrix4d();
        identity.setIdentity();
        assertThat(m).isEqualTo(identity);
    }

    @Test
    void aShortLookatIsAnError() {
        assertThatThrownBy(() -> of(ColladaTransform.Kind.LOOKAT, 1, 2, 3).toMatrix())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs 6 values");
    }
}
