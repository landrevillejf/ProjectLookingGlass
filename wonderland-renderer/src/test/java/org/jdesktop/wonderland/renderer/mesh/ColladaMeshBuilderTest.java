/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the pure primitive-to-flat-mesh walk.
 *
 * Every branch the 2006 jmecolladaloader took on the way to a jogamp
 * GeometryArray -- POSITION + optional NORMAL + optional TEXCOORD inputs
 * resolved through their <source> refs, stride-packed <p> tokens, per-kind
 * triangle generation (triangles / polylist / trifans / tristrips / lines),
 * and the tolerance paths (no POSITION, short <p>, empty mesh, null geometry)
 * -- is exercised here. This class is the coverage carrier for the module:
 * the probe-only Java 3D assembly on the other side of the seam never runs in
 * the headless suite, so the geometry expansion -- where every real bug would
 * live -- is fully asserted.
 */
package org.jdesktop.wonderland.renderer.mesh;

import java.util.List;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ColladaMeshBuilderTest {

    private final ColladaMeshBuilder builder = new ColladaMeshBuilder();

    private static ColladaGeometry.Source src(String id, int stride, double... data) {
        return new ColladaGeometry.Source(id, data, stride, data.length / Math.max(1, stride));
    }

    private static ColladaGeometry.Input in(String semantic, String ref, long offset, String set) {
        return new ColladaGeometry.Input(semantic, ref, offset, set);
    }

    private static ColladaGeometry geom(ColladaGeometry.Mesh mesh) {
        return new ColladaGeometry("g", "g", mesh);
    }

    @Test
    void aTrianglesPrimitiveExpandsPositionSourceIntoUnsharedVertices() {
        // 3 referenced vertices, stride 1 (POSITION only), pointing at indices 0/1/2
        ColladaGeometry.Source pos = src("pos", 3,
                0, 0, 0,   // vertex A
                1, 0, 0,   // vertex B
                0, 1, 0);  // vertex C
        ColladaGeometry.Primitive tri = new ColladaGeometry.Primitive(
                "triangles", "mat0", 1,
                List.of(in("POSITION", "#pos", 0, null)),
                new long[]{0, 1, 2});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos), new ColladaGeometry.Vertices("vtx", List.of()), List.of(tri));

        List<ColladaMeshData> out = builder.build(geom(mesh));

        assertThat(out).hasSize(1);
        ColladaMeshData data = out.get(0);
        assertThat(data.vertexCount()).isEqualTo(3);
        assertThat(data.triangleCount()).isEqualTo(1);
        assertThat(data.coordinates()).containsExactly(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        assertThat(data.triangleIndices()).containsExactly(0, 1, 2);
        assertThat(data.material()).isEqualTo("mat0");
        assertThat(data.hasNormals()).isFalse();
        assertThat(data.hasTexCoords()).isFalse();
    }

    @Test
    void normalsAndTexcoordsAreReadThroughTheirOwnStrideOffset() {
        // stride 3: POSITION at 0, NORMAL at 1, TEXCOORD at 2 -- the classic packed layout
        ColladaGeometry.Source pos = src("pos", 3, 0, 0, 0, 1, 0, 0, 0, 1, 0);
        ColladaGeometry.Source nrm = src("nrm", 3, 0, 0, 1, 0, 0, 1, 0, 0, 1);
        ColladaGeometry.Source uv = src("uv", 2, 0, 0, 1, 0, 0, 1);
        ColladaGeometry.Primitive tri = new ColladaGeometry.Primitive(
                "triangles", "mat0", 1,
                List.of(
                        in("POSITION", "#pos", 0, null),
                        in("NORMAL", "#nrm", 1, null),
                        in("TEXCOORD", "#uv", 2, "0")),
                // 3 vertices x stride 3 tokens = 9 entries
                new long[]{
                        0, 0, 0,   // vertex 0 refers pos[0], nrm[0], uv[0]
                        1, 1, 1,   // vertex 1 refers pos[1], nrm[1], uv[1]
                        2, 2, 2}); // vertex 2 refers pos[2], nrm[2], uv[2]
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos, nrm, uv), null, List.of(tri));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        assertThat(data.hasNormals()).isTrue();
        assertThat(data.hasTexCoords()).isTrue();
        assertThat(data.normals()).containsExactly(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f);
        assertThat(data.texCoords()).containsExactly(0f, 0f, 1f, 0f, 0f, 1f);
    }

    @Test
    void aPolylistFallsBackToConsecutiveTriplesWhenVcountIsAbsent() {
        // 6 vertices in a polylist, no <vcount> preserved by the M5 reader; the
        // shipped Google Earth / SketchUp exports triangulate at authoring time
        // so consecutive triples reconstruct them correctly. Documented in the
        // class comment: a genuine n-gon would need <vcount> threaded through
        // ColladaGeometry.Primitive first.
        ColladaGeometry.Source pos = src("pos", 3,
                0, 0, 0, 1, 0, 0, 0, 1, 0,   // triangle 1
                1, 1, 0, 1, 0, 1, 0, 0, 1);  // triangle 2
        ColladaGeometry.Primitive poly = new ColladaGeometry.Primitive(
                "polylist", "mat0", 2,
                List.of(in("POSITION", "#pos", 0, null)),
                new long[]{0, 1, 2, 3, 4, 5});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos), null, List.of(poly));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        assertThat(data.vertexCount()).isEqualTo(6);
        assertThat(data.triangleCount()).isEqualTo(2);
        assertThat(data.triangleIndices()).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    void aTrifanFansFromVertexZero() {
        // trifan of 4 vertices -> triangles (0,1,2) (0,2,3)
        ColladaGeometry.Source pos = src("pos", 3, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
        ColladaGeometry.Primitive fan = new ColladaGeometry.Primitive(
                "trifans", "mat0", 2,
                List.of(in("POSITION", "#pos", 0, null)),
                new long[]{0, 1, 2, 3});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos), null, List.of(fan));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        assertThat(data.triangleCount()).isEqualTo(2);
        assertThat(data.triangleIndices()).containsExactly(0, 1, 2, 0, 2, 3);
    }

    @Test
    void aTristripAlternatesWindingEveryOtherTriangle() {
        // tristrip of 4 vertices -> (0,1,2) then (1,3,2) -- the second triangle
        // swaps the last two entries so the winding stays consistent
        ColladaGeometry.Source pos = src("pos", 3, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1);
        ColladaGeometry.Primitive strip = new ColladaGeometry.Primitive(
                "tristrips", "mat0", 2,
                List.of(in("POSITION", "#pos", 0, null)),
                new long[]{0, 1, 2, 3});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos), null, List.of(strip));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        assertThat(data.triangleCount()).isEqualTo(2);
        assertThat(data.triangleIndices()).containsExactly(0, 1, 2, 1, 3, 2);
    }

    @Test
    void linesEmitNoTriangles() {
        // line / linestrip primitives are not triangles -- the 2006 loader
        // dropped them from the Shape3D build, so we emit coordinates but no
        // index list and let the caller skip them (or use a LineArray later)
        ColladaGeometry.Source pos = src("pos", 3, 0, 0, 0, 1, 1, 1);
        ColladaGeometry.Primitive line = new ColladaGeometry.Primitive(
                "lines", "mat0", 1,
                List.of(in("POSITION", "#pos", 0, null)),
                new long[]{0, 1});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos), null, List.of(line));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        assertThat(data.vertexCount()).isEqualTo(2);
        assertThat(data.triangleCount()).isZero();
        assertThat(data.triangleIndices()).isEmpty();
    }

    @Test
    void aPrimitiveWithoutPositionYieldsEmptyArraysNotAThrow() {
        // a NORMAL-only primitive (authoring-tool export gone weird): the loader
        // has nothing to place in 3D space, so we get zero vertices -- the
        // 2006 code likewise skipped such draw elements
        ColladaGeometry.Source nrm = src("nrm", 3, 0, 1, 0);
        ColladaGeometry.Primitive nrmOnly = new ColladaGeometry.Primitive(
                "triangles", "mat0", 1,
                List.of(in("NORMAL", "#nrm", 0, null)),
                new long[]{0});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(nrm), null, List.of(nrmOnly));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        assertThat(data.coordinates()).isEmpty();
        assertThat(data.triangleIndices()).isEmpty();
        assertThat(data.material()).isEqualTo("mat0");
    }

    @Test
    void anUnresolvableSourceReferenceIsTreatedAsZeroComponents() {
        // POSITION input points at #missing: sourceData returns empty and the
        // readComponents path fills zeros, matching the 2006 loader's tolerance
        // for a broken input/source binding -- a warning, not an abort
        ColladaGeometry.Primitive tri = new ColladaGeometry.Primitive(
                "triangles", "mat0", 1,
                List.of(in("POSITION", "#missing", 0, null)),
                new long[]{0, 1, 2});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(), null, List.of(tri));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        // the POSITION input is found (semantic matches) so vertexCount is 3,
        // but every component reads out as zero because the source is absent
        assertThat(data.vertexCount()).isEqualTo(3);
        assertThat(data.coordinates()).containsExactly(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    @Test
    void aTexcoordWithoutSetPrefersTheUnsetOverASetNumberedOne() {
        // Two TEXCOORD inputs, one with set="1" (secondary UV) and one unset
        // (the primary). The builder must pick the unset one for the shape's
        // single texture-coordinate set -- the 2006 loader bound the primary UV.
        ColladaGeometry.Source pos = src("pos", 3, 0, 0, 0, 1, 0, 0, 0, 1, 0);
        ColladaGeometry.Source uv0 = src("uv0", 2, 0.25, 0.75, 0.5, 0.5, 0.75, 0.25);
        ColladaGeometry.Source uv1 = src("uv1", 2, 9, 9, 9, 9, 9, 9);
        ColladaGeometry.Primitive tri = new ColladaGeometry.Primitive(
                "triangles", "mat0", 1,
                List.of(
                        in("POSITION", "#pos", 0, null),
                        in("TEXCOORD", "#uv1", 1, "1"),
                        in("TEXCOORD", "#uv0", 2, null)),
                new long[]{
                        0, 0, 0,
                        1, 1, 1,
                        2, 2, 2});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos, uv0, uv1), null, List.of(tri));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        assertThat(data.texCoords()).containsExactly(0.25f, 0.75f, 0.5f, 0.5f, 0.75f, 0.25f);
    }

    @Test
    void aDanglingTailBelowThreeVerticesIsDroppedNotRead() {
        // 4 tokens in a triangles primitive -- one vertex short of a full facet.
        // The 2006 loader would have thrown; here we walk 3 vertices, emit one
        // triangle, and leave the tail out (the loop bound i + 2 < vertexCount).
        ColladaGeometry.Source pos = src("pos", 3, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1);
        ColladaGeometry.Primitive tri = new ColladaGeometry.Primitive(
                "triangles", "mat0", 1,
                List.of(in("POSITION", "#pos", 0, null)),
                new long[]{0, 1, 2, 3});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos), null, List.of(tri));

        ColladaMeshData data = builder.build(geom(mesh)).get(0);

        // all 4 vertices still expand into the coordinate array (they are all
        // referenced), but only the complete triple becomes a triangle
        assertThat(data.vertexCount()).isEqualTo(4);
        assertThat(data.triangleCount()).isEqualTo(1);
        assertThat(data.triangleIndices()).containsExactly(0, 1, 2);
    }

    @Test
    void anEmptyGeometryOrNullYieldsEmptyLists() {
        ColladaGeometry emptyMesh = geom(new ColladaGeometry.Mesh(
                List.of(), null, List.of()));
        assertThat(builder.build(emptyMesh)).isEmpty();
        assertThat(builder.build(null)).isEmpty();
    }

    @Test
    void multiplePrimitivesOnOneMeshEmitOneDataEachPreservingMaterialBinding() {
        ColladaGeometry.Source pos = src("pos", 3, 0, 0, 0, 1, 0, 0, 0, 1, 0);
        ColladaGeometry.Primitive a = new ColladaGeometry.Primitive(
                "triangles", "matA", 1,
                List.of(in("POSITION", "#pos", 0, null)), new long[]{0, 1, 2});
        ColladaGeometry.Primitive b = new ColladaGeometry.Primitive(
                "triangles", "matB", 1,
                List.of(in("POSITION", "#pos", 0, null)), new long[]{0, 1, 2});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos), null, List.of(a, b));

        List<ColladaMeshData> out = builder.build(geom(mesh));

        assertThat(out).hasSize(2);
        assertThat(out).extracting(ColladaMeshData::material)
                .containsExactly("matA", "matB");
    }

    @Test
    void triangulateIsKindDrivenAndToleratesUnknownKindsAsConsecutiveTriples() {
        // Directly exercise the package-visible triangulate for a synthetic
        // unknown kind; the fallback path emits complete triples just like
        // triangles/polylist/polygons.
        assertThat(ColladaMeshBuilder.triangulate("triangles", 3)).containsExactly(0, 1, 2);
        assertThat(ColladaMeshBuilder.triangulate("polygons", 6)).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(ColladaMeshBuilder.triangulate("mystery", 3)).containsExactly(0, 1, 2);
        assertThat(ColladaMeshBuilder.triangulate(null, 3)).containsExactly(0, 1, 2);
        assertThat(ColladaMeshBuilder.triangulate("lines", 3)).isEmpty();
        assertThat(ColladaMeshBuilder.triangulate("linestrips", 3)).isEmpty();
    }
}
