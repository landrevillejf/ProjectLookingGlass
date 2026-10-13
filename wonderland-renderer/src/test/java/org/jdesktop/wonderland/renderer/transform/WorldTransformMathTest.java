/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the transform fold from world / cell state to
 * Java 3D's Transform3D feed. Pure vecmath, so it runs and is covered in the
 * headless suite -- the probe-only classes on the other side of the seam just
 * hand the 16-array to Jogamp; every arithmetic decision is made here.
 */
package org.jdesktop.wonderland.renderer.transform;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.util.List;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.jdesktop.wonderland.modules.collada.ColladaTransform;
import org.jogamp.vecmath.Matrix4d;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorldTransformMathTest {

    @Test
    void identityIsTheSixteenDoubleRowMajorIdentity() {
        assertThat(WorldTransformMath.identity()).containsExactly(
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1);
    }

    @Test
    void toRowMajorLaysOutMatrix4dEntriesRowByRow() {
        Matrix4d m = new Matrix4d();
        m.setIdentity();
        m.setElement(0, 1, 7.0);
        m.setElement(2, 3, -2.5);
        m.setElement(3, 0, 11.0);

        double[] row = WorldTransformMath.toRowMajor(m);

        // row 0: [1, 7, 0, 0]; row 1: [0, 1, 0, 0]; row 2: [0, 0, 1, -2.5]; row 3: [11, 0, 0, 1]
        assertThat(row).containsExactly(
                1, 7, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, -2.5,
                11, 0, 0, 1);
    }

    @Test
    void toRowMajorNullIsIdentity() {
        assertThat(WorldTransformMath.toRowMajor(null)).isEqualTo(WorldTransformMath.identity());
    }

    @Test
    void fromWorldMatrixNullIsIdentity() {
        assertThat(WorldTransformMath.fromWorldMatrix(null))
                .isEqualTo(WorldTransformMath.identity());
    }

    @Test
    void fromWorldMatrixCopiesTheUnderlyingMatrixRowMajor() {
        // Build a node carrying a translate of (3, 4, 5), let ColladaNode's
        // world-matrix walk produce one WorldMatrix, and check that folding it
        // through WorldTransformMath matches a direct toRowMajor on the same
        // Matrix4d -- so the two entry points to the fold agree.
        ColladaNode node = new ColladaNode("n", "n", "NODE",
                List.of(new ColladaTransform(ColladaTransform.Kind.TRANSLATE, null,
                        new double[]{3, 4, 5})),
                List.of(), List.of(), List.of());
        java.util.List<ColladaNode.WorldMatrix> wms = new java.util.ArrayList<>();
        node.collectWorldMatrices(null, wms);

        ColladaNode.WorldMatrix wm = wms.get(0);
        double[] viaWorldMatrix = WorldTransformMath.fromWorldMatrix(wm);
        double[] direct = WorldTransformMath.toRowMajor(wm.matrix());

        assertThat(viaWorldMatrix).isEqualTo(direct);
        assertThat(WorldTransformMath.translationOf(viaWorldMatrix))
                .containsExactly(3.0, 4.0, 5.0);
    }

    @Test
    void fromCellTransformNullIsIdentity() {
        assertThat(WorldTransformMath.fromCellTransform(null))
                .isEqualTo(WorldTransformMath.identity());
    }

    @Test
    void fromCellTransformCarriesTranslationThroughToRowMajorLayout() {
        // The 2006 ClientContextJME read a cell's CellTransform and pushed it
        // into the jME scene as a Matrix4d(Quat4d, Vector3d, scale); the same
        // construction is what the probe-only ModelRenderer feeds to a
        // Jogamp Transform3D, and its arithmetic is what this pins here.
        // jME's Quaternion default constructor is the identity (x=y=z=0, w=1)
        Quaternion rotation = new Quaternion();
        Vector3f translation = new Vector3f(2f, -3f, 4f);
        CellTransform ct = new CellTransform(rotation, translation, 1.0f);

        double[] row = WorldTransformMath.fromCellTransform(ct);

        // pure translation, so the last row is the offset and the diagonal is 1
        assertThat(row).hasSize(16);
        assertThat(row[0]).isEqualTo(1.0);
        assertThat(row[5]).isEqualTo(1.0);
        assertThat(row[10]).isEqualTo(1.0);
        assertThat(row[15]).isEqualTo(1.0);
        assertThat(WorldTransformMath.translationOf(row))
                .containsExactly(2.0, -3.0, 4.0);
    }

    @Test
    void fromCellTransformHonoursUniformScaleOnTheDiagonal() {
        Quaternion rotation = new Quaternion();
        Vector3f translation = new Vector3f(0f, 0f, 0f);
        CellTransform scaled = new CellTransform(rotation, translation, 2.5f);

        double[] row = WorldTransformMath.fromCellTransform(scaled);

        // uniform scale multiplies the 3x3 linear part; last row stays (0,0,0,1)
        assertThat(row[0]).isCloseTo(2.5, within(1e-9));
        assertThat(row[5]).isCloseTo(2.5, within(1e-9));
        assertThat(row[10]).isCloseTo(2.5, within(1e-9));
        assertThat(row[15]).isEqualTo(1.0);
        assertThat(WorldTransformMath.translationOf(row)).containsExactly(0.0, 0.0, 0.0);
    }

    @Test
    void translationOfGuardsShortAndNullArrays() {
        assertThat(WorldTransformMath.translationOf(null)).containsExactly(0.0, 0.0, 0.0);
        assertThat(WorldTransformMath.translationOf(new double[]{1, 2, 3}))
                .containsExactly(0.0, 0.0, 0.0);
    }

    @Test
    void translationOfReadsTheLastColumnEntries() {
        double[] m = new double[16];
        for (int i = 0; i < 16; i++) {
            m[i] = i;
        }
        // indices 3, 7, 11 hold the translation in vecmath's row-major layout
        // ((0,3), (1,3), (2,3) -> the last column of the 4x4)
        assertThat(WorldTransformMath.translationOf(m)).containsExactly(3.0, 7.0, 11.0);
    }
}
