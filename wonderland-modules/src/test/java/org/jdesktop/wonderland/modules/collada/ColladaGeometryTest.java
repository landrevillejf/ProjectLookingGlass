/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): the geometry data model + position walk.
 * Exercises the headless half of what the 2006 loader fed to the Shape3D build:
 * source stride/count, the mesh source lookup, and Primitive.positions() across
 * every reachable branch -- no POSITION binding, a missing source, a zero-stride
 * primitive, a short <p> array, and an index that reads past the float data.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ColladaGeometryTest {

    private static ColladaGeometry.Source source(String id, double[] data, int stride) {
        return new ColladaGeometry.Source(id, data, stride, data.length / stride);
    }

    private static ColladaGeometry.Input input(String semantic, String source, int offset) {
        return new ColladaGeometry.Input(semantic, source, offset, null);
    }

    @Test
    void aNullMeshAndNullSourceArrayNormaliseToEmpty() {
        ColladaGeometry geometry = new ColladaGeometry("g", "n", null);
        assertThat(geometry.id()).isEqualTo("g");
        assertThat(geometry.mesh().sources()).isEmpty();
        assertThat(geometry.mesh().primitives()).isEmpty();
        assertThat(geometry.mesh().vertices()).isNull();

        ColladaGeometry.Source nullData = new ColladaGeometry.Source("s", null, 3, 0);
        assertThat(nullData.data()).isEmpty();
    }

    @Test
    void sourceDataIsCopiedDefensively() {
        double[] raw = {1, 2, 3};
        ColladaGeometry.Source src = source("s", raw, 3);
        src.data()[0] = 99; // mutating the returned copy must not touch the source
        assertThat(src.data()[0]).isEqualTo(1.0);
        assertThat(src.count()).isEqualTo(1);
    }

    @Test
    void meshResolvesSourcesById() {
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(source("POSITION-data", new double[]{0, 0, 0, 1, 1, 1}, 3)),
                null, List.of());
        assertThat(mesh.sourceById("POSITION-data")).isNotNull();
        assertThat(mesh.sourceById("absent")).isNull();
    }

    @Test
    void strideIsTheLargestInputOffsetPlusOne() {
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("triangles", "MAT", 1,
                List.of(input("VERTEX", "#v", 0), input("TEXCOORD", "#t", 1)), new long[0]);
        assertThat(prim.stride()).isEqualTo(2);
        assertThat(new ColladaGeometry.Primitive("triangles", null, 0, List.of(), new long[0]).stride())
                .isEqualTo(0);
    }

    @Test
    void positionsReadThePackedIndexStream() {
        ColladaGeometry.Source pos = source("pos", new double[]{10, 20, 30, 40, 50, 60}, 3);
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(List.of(pos), null, List.of());
        // one input, offset 0, stride 1: the <p> tokens are position indices directly
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("triangles", "MAT", 2,
                List.of(input("POSITION", "#pos", 0)), new long[]{0, 1});

        assertThat(prim.positions(mesh)).containsExactly(10f, 20f, 30f, 40f, 50f, 60f);
        assertThat(prim.indices()).containsExactly(0, 1);
        assertThat(prim.inputs()).hasSize(1);
        assertThat(prim.material()).isEqualTo("MAT");
        assertThat(prim.kind()).isEqualTo("triangles");
        assertThat(prim.count()).isEqualTo(2);
    }

    @Test
    void positionsWalkPastTheFacetCountBecauseATrianglesCountIsFacetsNotVertices() {
        ColladaGeometry.Source pos = source("pos", new double[]{0, 0, 0, 1, 1, 1, 2, 2, 2}, 3);
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(List.of(pos), null, List.of());
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("triangles", null, 1,
                List.of(input("POSITION", "#pos", 0)), new long[]{0, 1, 2});

        assertThat(prim.positions(mesh)).hasSize(9); // 3 vertices, count says 1 facet
    }

    @Test
    void positionsAreEmptyWithoutAPositionBinding() {
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(source("pos", new double[]{1, 2, 3}, 3)), null, List.of());
        ColladaGeometry.Primitive noPosition = new ColladaGeometry.Primitive("triangles", null, 1,
                List.of(input("NORMAL", "#n", 0)), new long[]{0});
        assertThat(noPosition.positions(mesh)).isEmpty();
    }

    @Test
    void positionsAreEmptyWhenTheSourceIsAbsentOrMeshIsNull() {
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("triangles", null, 1,
                List.of(input("POSITION", "#missing", 0)), new long[]{0});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(List.of(), null, List.of());

        assertThat(prim.positions(mesh)).isEmpty();               // sourceById null
        assertThat(prim.positions(null)).isEmpty();               // mesh null

        ColladaGeometry.Primitive noInputs = new ColladaGeometry.Primitive("triangles", null, 1,
                List.of(), new long[]{0});
        assertThat(noInputs.positions(mesh)).isEmpty();           // no POSITION input
    }

    @Test
    void aShortIndexStreamStopsRatherThanThrowing() {
        ColladaGeometry.Source pos = source("pos", new double[]{1, 2, 3, 4, 5, 6}, 3);
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(List.of(pos), null, List.of());
        // stride 2 (POSITION offset 0, TEXCOORD offset 1) but only 3 tokens: entries
        // floors 3 / 2 to one full vertex group, so the trailing partial group is
        // simply never walked (the token index can never reach indices.length).
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("triangles", null, 1,
                List.of(input("POSITION", "#pos", 0), input("TEXCOORD", "#t", 1)),
                new long[]{0, 0, 1});

        assertThat(prim.positions(mesh)).containsExactly(1f, 2f, 3f);
    }

    @Test
    void positionsAreEmptyWhenANegativeOffsetCollapsesTheStride() {
        // stride() is max(offset + 1); a sole POSITION input authored with a negative
        // offset="-1" (a malformed export) collapses it to zero, and the walk stops
        // before dividing by it rather than reading a stream that has no groups.
        ColladaGeometry.Source pos = source("pos", new double[]{1, 2, 3}, 3);
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(List.of(pos), null, List.of());
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("triangles", null, 1,
                List.of(input("POSITION", "#pos", -1)), new long[]{0});

        assertThat(prim.stride()).isZero();
        assertThat(prim.positions(mesh)).isEmpty();
    }

    @Test
    void aNegativePositionOffsetStopsTheWalkBeforeIndexingTheStream() {
        // A POSITION input at offset="-1" beside a TEXCOORD at offset 0 keeps stride()
        // at 1 (so the zero-stride guard passes) but makes the first token index -1;
        // the walk stops there rather than indexing the packed <p> array out of bounds.
        ColladaGeometry.Source pos = source("pos", new double[]{1, 2, 3}, 3);
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(List.of(pos), null, List.of());
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("triangles", null, 1,
                List.of(input("POSITION", "#pos", -1), input("TEXCOORD", "#t", 0)),
                new long[]{0, 0});

        assertThat(prim.stride()).isEqualTo(1);
        assertThat(prim.positions(mesh)).isEmpty();
    }

    @Test
    void anIndexPastTheFloatDataPadsWithZero() {
        ColladaGeometry.Source pos = source("pos", new double[]{1, 2, 3}, 3);
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(List.of(pos), null, List.of());
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("triangles", null, 1,
                List.of(input("POSITION", "#pos", 0)), new long[]{5}); // index 5 -> past data

        assertThat(prim.positions(mesh)).containsExactly(0f, 0f, 0f);
    }

    @Test
    void verticesExposeTheirIdAndInputs() {
        ColladaGeometry.Input in = input("POSITION", "#pos", 0);
        ColladaGeometry.Vertices verts = new ColladaGeometry.Vertices("vtx", List.of(in));
        assertThat(verts.id()).isEqualTo("vtx");
        assertThat(verts.inputs()).containsExactly(in);
        assertThat(in.semantic()).isEqualTo("POSITION");
        assertThat(in.source()).isEqualTo("#pos");
        assertThat(in.offset()).isZero();
        assertThat(in.set()).isNull();
    }

    @Test
    void localReferencesAreStrippedOfTheirHash() {
        assertThat(ColladaGeometry.Primitive.stripRef("#id")).isEqualTo("id");
        assertThat(ColladaGeometry.Primitive.stripRef("plain")).isEqualTo("plain");
        assertThat(ColladaGeometry.Primitive.stripRef(null)).isNull();
    }

    @Test
    void nullPrimitiveIndicesReadAsEmpty() {
        ColladaGeometry.Primitive prim = new ColladaGeometry.Primitive("lines", null, 0,
                List.of(), null);
        assertThat(prim.indices()).isEmpty();
    }
}
