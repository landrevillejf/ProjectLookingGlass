/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.4b: the JOGL world builder's pure maths, asserted
 * headless. GlWorldBuilder emits data (a GlScene draw list + a Camera) and
 * touches no GL, so every placement, framing and binding decision it makes runs
 * in a plain JVM. What is left to the probe is only GlScene.draw / GlMesh.upload,
 * exactly as for the rest of the JOGL scene core.
 */
package org.jdesktop.wonderland.renderer.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jdesktop.wonderland.modules.artimport.DeployedModel;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.jdesktop.wonderland.modules.collada.ColladaVisualScene;
import org.jdesktop.wonderland.renderer.mesh.TextureSource;
import org.jdesktop.wonderland.renderer.view.WorldScene.Box;
import org.jdesktop.wonderland.renderer.view.WorldScene.Entry;
import org.jdesktop.wonderland.renderer.view.WorldScene.Picture;
import org.jogamp.vecmath.Matrix4d;
import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Vector3d;
import org.jogamp.vecmath.Vector3f;
import org.junit.jupiter.api.Test;

class GlWorldBuilderTest {

    private static final double EPS = 1e-6;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A one-triangle COLLADA document, so a bare model has geometry to walk. */
    private static ColladaDocument triangleDoc() {
        ColladaGeometry.Source pos = new ColladaGeometry.Source("pos",
                new double[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, 3, 3);
        // The shipped Google-Earth/SketchUp exports bind POSITION on <vertices> and
        // reference it from the primitive through a VERTEX token -- the indirection
        // both the mesh builder and measuredBox resolve.
        ColladaGeometry.Vertices verts = new ColladaGeometry.Vertices("vtx",
                List.of(new ColladaGeometry.Input("POSITION", "#pos", 0, null)));
        ColladaGeometry.Primitive tri = new ColladaGeometry.Primitive(
                "triangles", "mat0", 1,
                List.of(new ColladaGeometry.Input("VERTEX", "#vtx", 0, null)),
                new long[] {0, 1, 2});
        ColladaGeometry.Mesh mesh = new ColladaGeometry.Mesh(
                List.of(pos), verts, List.of(tri));
        ColladaGeometry geom = new ColladaGeometry("g", "g", mesh);
        ColladaNode node = new ColladaNode("n", "n", "NODE", List.of(), List.of(),
                List.of(new ColladaNode.InstanceGeometry("#g", List.of())), List.of());
        ColladaVisualScene scene = new ColladaVisualScene("vs", "vs", List.of(node));
        return new ColladaDocument(null, Map.of("g", geom), Map.of(), Map.of(),
                Map.of(), Map.of("vs", scene), "vs");
    }

    private static ModelLoadResult bareModel() {
        return ModelLoadResult.bare(triangleDoc());
    }

    private static Entry entry(ModelLoadResult model, float x, float y, float z) {
        return new Entry("cell", model, x, y, z, 0f, 1f, 1f, 1f, 2f,
                TextureSource.NONE, null);
    }

    private static DeployedModel dep(Vector3f scale, Vector3f translation,
            Vector3f axis, float angle) {
        return new DeployedModel(1, "u", "l", scale, translation, axis, angle,
                null, null);
    }

    private static Matrix4d identity() {
        Matrix4d m = new Matrix4d();
        m.setIdentity();
        return m;
    }

    // ------------------------------------------------------------------
    // Placement
    // ------------------------------------------------------------------

    @Test
    void cellPoseOfAnUnturnedUnstretchedCellIsABareTranslation() {
        Entry e = new Entry("c", null, 1f, 2f, 3f, 0f, 1f, 1f, 1f, 0f,
                TextureSource.NONE, null);

        Matrix4d pose = GlWorldBuilder.cellPose(e);

        assertThat(pose.m03).isEqualTo(1.0, within(EPS));
        assertThat(pose.m13).isEqualTo(2.0, within(EPS));
        assertThat(pose.m23).isEqualTo(3.0, within(EPS));
        assertThat(pose.m00).isEqualTo(1.0, within(EPS));
        assertThat(pose.m11).isEqualTo(1.0, within(EPS));
        assertThat(pose.m22).isEqualTo(1.0, within(EPS));
    }

    @Test
    void cellPoseTurnsAboutTheWorldVerticalAxis() {
        Entry e = new Entry("c", null, 0f, 0f, 0f, (float) (Math.PI / 2.0),
                1f, 1f, 1f, 0f, TextureSource.NONE, null);

        Matrix4d pose = GlWorldBuilder.cellPose(e);

        // A 90-degree yaw about Y leaves Y fixed and turns X onto Z.
        assertThat(pose.m11).isEqualTo(1.0, within(EPS));
        assertThat(pose.m00).isEqualTo(0.0, within(EPS));
        assertThat(pose.m22).isEqualTo(0.0, within(EPS));
    }

    @Test
    void cellPoseStretchesAlongEachAxis() {
        Entry e = new Entry("c", null, 0f, 0f, 0f, 0f, 2f, 3f, 4f, 0f,
                TextureSource.NONE, null);

        Matrix4d pose = GlWorldBuilder.cellPose(e);

        assertThat(pose.m00).isEqualTo(2.0, within(EPS));
        assertThat(pose.m11).isEqualTo(3.0, within(EPS));
        assertThat(pose.m22).isEqualTo(4.0, within(EPS));
    }

    @Test
    void backgroundOfNoDeployedModelIsIdentity() {
        assertThat(isIdentity(GlWorldBuilder.backgroundOf(null))).isTrue();
    }

    @Test
    void backgroundMatrixOfABareModelIsIdentity() {
        // A bare model carries no .dep, so nothing normalises it.
        assertThat(isIdentity(GlWorldBuilder.backgroundMatrix(bareModel()))).isTrue();
    }

    @Test
    void backgroundOfScalesAlongEachAxis() {
        Matrix4d m = GlWorldBuilder.backgroundOf(
                dep(new Vector3f(2f, 3f, 4f), null, null, 0f));

        assertThat(m.m00).isEqualTo(2.0, within(EPS));
        assertThat(m.m11).isEqualTo(3.0, within(EPS));
        assertThat(m.m22).isEqualTo(4.0, within(EPS));
    }

    @Test
    void backgroundOfTranslates() {
        Matrix4d m = GlWorldBuilder.backgroundOf(
                dep(null, new Vector3f(5f, 6f, 7f), null, 0f));

        assertThat(m.m03).isEqualTo(5.0, within(EPS));
        assertThat(m.m13).isEqualTo(6.0, within(EPS));
        assertThat(m.m23).isEqualTo(7.0, within(EPS));
    }

    @Test
    void backgroundOfRotatesAboutTheRecordedAxis() {
        Matrix4d m = GlWorldBuilder.backgroundOf(
                dep(null, null, new Vector3f(0f, 1f, 0f), (float) (Math.PI / 2.0)));

        assertThat(m.m11).isEqualTo(1.0, within(EPS)); // Y is the axis, untouched
        assertThat(m.m00).isEqualTo(0.0, within(EPS));
        assertThat(m.m22).isEqualTo(0.0, within(EPS));
    }

    @Test
    void backgroundOfIgnoresANaNScaleRatherThanPoisoningTheMatrix() {
        Matrix4d m = GlWorldBuilder.backgroundOf(
                dep(new Vector3f(Float.NaN, 1f, 1f), null, null, 0f));

        assertThat(m.m00).isEqualTo(1.0, within(EPS)); // scale refused -> identity
    }

    @Test
    void backgroundOfSkipsARotationWithNoAxis() {
        Matrix4d m = GlWorldBuilder.backgroundOf(
                dep(null, null, new Vector3f(0f, 0f, 0f), 1.5f));

        assertThat(isIdentity(m)).isTrue();
    }

    private static boolean isIdentity(Matrix4d m) {
        Matrix4d id = identity();
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                double a = switch (r * 4 + c) {
                    case 0 -> m.m00; case 1 -> m.m01; case 2 -> m.m02; case 3 -> m.m03;
                    case 4 -> m.m10; case 5 -> m.m11; case 6 -> m.m12; case 7 -> m.m13;
                    case 8 -> m.m20; case 9 -> m.m21; case 10 -> m.m22; case 11 -> m.m23;
                    default -> m.m33;
                };
                double b = switch (r * 4 + c) {
                    case 0 -> id.m00; case 1 -> id.m01; case 2 -> id.m02; case 3 -> id.m03;
                    case 4 -> id.m10; case 5 -> id.m11; case 6 -> id.m12; case 7 -> id.m13;
                    case 8 -> id.m20; case 9 -> id.m21; case 10 -> id.m22; case 11 -> id.m23;
                    default -> id.m33;
                };
                if (Math.abs(a - b) > EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Row-major -> column-major conversion
    // ------------------------------------------------------------------

    @Test
    void toColumnMajorPutsTheTranslationInTheLastRow() {
        Matrix4d m = identity();
        m.m03 = 10;
        m.m13 = 20;
        m.m23 = 30;

        float[] col = GlWorldBuilder.toColumnMajor(m);

        assertThat(col[12]).isEqualTo(10f, within(1e-6f));
        assertThat(col[13]).isEqualTo(20f, within(1e-6f));
        assertThat(col[14]).isEqualTo(30f, within(1e-6f));
        assertThat(col[15]).isEqualTo(1f, within(1e-6f));
    }

    @Test
    void toColumnMajorIsTheFlatTransposeOfTheRowMajorBasis() {
        Matrix4d m = identity();
        m.m01 = 7; // row 0, column 1

        float[] col = GlWorldBuilder.toColumnMajor(m);

        // element (0,1) lands at index 1*4 + 0 = 4 in column-major
        assertThat(col[4]).isEqualTo(7f, within(1e-6f));
        assertThat(col[1]).isEqualTo(0f, within(1e-6f));
    }

    // ------------------------------------------------------------------
    // Extent / framing volumes
    // ------------------------------------------------------------------

    @Test
    void boundsBoxOfAUnitCubeAtTheOriginIsCentredWithUnitHalfExtents() {
        Box box = GlWorldBuilder.boundsBox(new Vector3f(0f, 0f, 0f),
                new Vector3f(1f, 1f, 1f), identity());

        assertThat(box.center().x).isEqualTo(0.0, within(EPS));
        assertThat(box.half().x).isEqualTo(1.0, within(EPS));
        assertThat(box.half().y).isEqualTo(1.0, within(EPS));
        assertThat(box.half().z).isEqualTo(1.0, within(EPS));
    }

    @Test
    void boundsBoxFollowsThePlacementTranslation() {
        Matrix4d moved = identity();
        moved.m03 = 10;

        Box box = GlWorldBuilder.boundsBox(new Vector3f(0f, 0f, 0f),
                new Vector3f(1f, 1f, 1f), moved);

        assertThat(box.center().x).isEqualTo(10.0, within(EPS));
    }

    @Test
    void extentBoxMeasuresAModelsOwnVerticesWhenItHasNoBounds() {
        Entry e = entry(bareModel(), 0f, 0f, 0f);

        Box box = GlWorldBuilder.extentBox(e, identity());

        // The triangle spans (0,0,0)..(1,1,0).
        assertThat(box.center().x).isEqualTo(0.5, within(EPS));
        assertThat(box.center().y).isEqualTo(0.5, within(EPS));
        assertThat(box.half().x).isEqualTo(0.5, within(EPS));
        assertThat(box.half().z).isEqualTo(0.0, within(EPS));
    }

    @Test
    void extentBoxFallsBackToTheFootprintWhenNothingIsReadable() {
        Entry e = entry(null, 4f, 5f, 6f); // no model at all

        Box box = GlWorldBuilder.extentBox(e, identity());

        assertThat(box.center().x).isEqualTo(4.0, within(EPS));
        assertThat(box.center().y).isEqualTo(5.0, within(EPS));
        assertThat(box.half().x).isEqualTo(1.0, within(EPS)); // footprint 2 / 2
    }

    @Test
    void cornerRadiusIsTheDistanceFromCentreToACorner() {
        Box box = new Box(new Point3d(0, 0, 0), new Vector3d(3, 0, 4));

        assertThat(GlWorldBuilder.cornerRadius(box)).isEqualTo(5.0, within(EPS));
    }

    // ------------------------------------------------------------------
    // Corner-fit framing
    // ------------------------------------------------------------------

    @Test
    void fitScaleIsOneWhenTheSubjectAlreadyFitsTheFrustum() {
        Vector3d half = new Vector3d(1, 1, 1);
        double tanV = Math.tan(GlWorldBuilder.FIELD_OF_VIEW_RAD / 2.0);

        double needed = GlWorldBuilder.fitScale(half, GlWorldBuilder.FLAT_TILT_RAD,
                1000.0, tanV * 1.6, tanV);

        assertThat(needed).isCloseTo(1.0, within(EPS));
    }

    @Test
    void fitScaleGrowsWhenTheEyeIsTooCloseForTheCorners() {
        Vector3d half = new Vector3d(10, 10, 10);
        double tanV = Math.tan(GlWorldBuilder.FIELD_OF_VIEW_RAD / 2.0);

        double needed = GlWorldBuilder.fitScale(half, GlWorldBuilder.FLAT_TILT_RAD,
                1.0, tanV * 1.6, tanV);

        assertThat(needed).isGreaterThan(1.0);
    }

    @Test
    void homeCameraBuildsAValidEyeAndLens() {
        Box focus = new Box(new Point3d(0, 0, 0), new Vector3d(5, 3, 5));

        var camera = GlWorldBuilder.homeCamera(focus, 1.6, 20.0);

        assertThat(camera.view()).hasSize(16);
        assertThat(camera.projection()).hasSize(16);
    }

    // ------------------------------------------------------------------
    // Binding helpers
    // ------------------------------------------------------------------

    @Test
    void stripHashRemovesTheFragment() {
        assertThat(GlWorldBuilder.stripHash("#geom0")).isEqualTo("geom0");
    }

    @Test
    void stripHashKeepsAPlainId() {
        assertThat(GlWorldBuilder.stripHash("geom0")).isEqualTo("geom0");
    }

    @Test
    void stripHashOfNullIsNull() {
        assertThat(GlWorldBuilder.stripHash(null)).isNull();
    }

    @Test
    void bindingsOfMapsASymbolToItsTarget() {
        ColladaNode.InstanceGeometry ig = new ColladaNode.InstanceGeometry("#g",
                List.of(new ColladaNode.InstanceMaterial("sym", "mat0", List.of())));

        assertThat(GlWorldBuilder.bindingsOf(ig)).containsEntry("sym", "mat0");
    }

    @Test
    void bindingsOfSkipsAnIncompleteBinding() {
        ColladaNode.InstanceGeometry ig = new ColladaNode.InstanceGeometry("#g",
                List.of(new ColladaNode.InstanceMaterial(null, "mat0", List.of()),
                        new ColladaNode.InstanceMaterial("sym", null, List.of())));

        assertThat(GlWorldBuilder.bindingsOf(ig)).isEmpty();
    }

    @Test
    void readOfNoSourceIsNull() {
        assertThat(GlWorldBuilder.read(null, "p")).isNull();
    }

    @Test
    void readReturnsTheSourcesBytes() {
        TextureSource source = path -> new byte[] {1, 2, 3};

        assertThat(GlWorldBuilder.read(source, "p")).containsExactly(1, 2, 3);
    }

    @Test
    void readSwallowsAFailingSourceAsAColourFallback() {
        TextureSource source = path -> {
            throw new IllegalStateException("nope");
        };

        assertThat(GlWorldBuilder.read(source, "p")).isNull();
    }

    @Test
    void cachedReadsAPathOnceAndReusesTheResult() {
        int[] reads = {0};
        TextureSource source = path -> {
            reads[0]++;
            return new byte[] {9};
        };
        Map<String, byte[]> cache = new HashMap<>();

        GlWorldBuilder.cached(cache, source, "p");
        GlWorldBuilder.cached(cache, source, "p");

        assertThat(reads[0]).isEqualTo(1);
    }

    @Test
    void cachedOfANullPathIsNull() {
        assertThat(GlWorldBuilder.cached(new HashMap<>(), TextureSource.NONE, null))
                .isNull();
    }

    // ------------------------------------------------------------------
    // The whole build
    // ------------------------------------------------------------------

    @Test
    void buildOfNothingIsAnEmptyWorldWithAFallbackCamera() {
        GlWorldBuilder.Result r = GlWorldBuilder.build(List.of(), List.of(), 640, 400);

        assertThat(r.placed()).isZero();
        assertThat(r.skipped()).isZero();
        assertThat(r.pictures()).isZero();
        assertThat(r.scene().size()).isZero();
        assertThat(r.camera()).isNotNull();
        assertThat(r.center().x).isEqualTo(0.0, within(EPS));
        assertThat(r.half().x).isEqualTo(GlWorldBuilder.FRAMING_MARGIN, within(EPS));
    }

    @Test
    void buildOfNullListsIsAnEmptyWorld() {
        GlWorldBuilder.Result r = GlWorldBuilder.build(null, null, 640, 400);

        assertThat(r.scene().size()).isZero();
        assertThat(r.camera()).isNotNull();
    }

    @Test
    void buildSkipsAnEntryWithNoLoadedModel() {
        GlWorldBuilder.Result r = GlWorldBuilder.build(
                List.of(entry(null, 0f, 0f, 0f)), List.of(), 640, 400);

        assertThat(r.placed()).isZero();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.scene().size()).isZero();
    }

    @Test
    void buildPlacesALoadedModelIntoTheDrawList() {
        GlWorldBuilder.Result r = GlWorldBuilder.build(
                List.of(entry(bareModel(), 0f, 0f, 0f)), List.of(), 640, 400);

        assertThat(r.placed()).isEqualTo(1);
        assertThat(r.skipped()).isZero();
        assertThat(r.scene().size()).isEqualTo(1); // one triangle primitive
        assertThat(r.camera()).isNotNull();
    }

    @Test
    void buildMeasuresTheFocusOffThePlacedModel() {
        GlWorldBuilder.Result r = GlWorldBuilder.build(
                List.of(entry(bareModel(), 0f, 0f, 0f)), List.of(), 640, 400);

        // The triangle spans (0,0,0)..(1,1,0), so the focus centre is its middle.
        assertThat(r.center().x).isEqualTo(0.5, within(EPS));
        assertThat(r.center().y).isEqualTo(0.5, within(EPS));
    }

    @Test
    void buildHangsAPictureAsATexturedCard() {
        Picture picture = new Picture("poster", new byte[] {1, 2, 3},
                0f, 0f, 0f, 0f, 2f, 1f, null);

        GlWorldBuilder.Result r = GlWorldBuilder.build(List.of(), List.of(picture),
                640, 400);

        assertThat(r.pictures()).isEqualTo(1);
        assertThat(r.scene().size()).isEqualTo(1);
    }

    @Test
    void buildSkipsAPictureWithNoExtent() {
        Picture picture = new Picture("poster", new byte[] {1, 2, 3},
                0f, 0f, 0f, 0f, 0f, 0f, null);

        GlWorldBuilder.Result r = GlWorldBuilder.build(List.of(), List.of(picture),
                640, 400);

        assertThat(r.pictures()).isZero();
        assertThat(r.scene().size()).isZero();
    }

    @Test
    void buildUsesTheSuppliedAspectForTheCamera() {
        GlWorldBuilder.Result square = GlWorldBuilder.build(
                List.of(entry(bareModel(), 0f, 0f, 0f)), List.of(), 100, 100);
        GlWorldBuilder.Result wide = GlWorldBuilder.build(
                List.of(entry(bareModel(), 0f, 0f, 0f)), List.of(), 200, 100);

        // A wider frame projects differently, so the two cameras are not the same.
        assertThat(square.camera().projection())
                .isNotEqualTo(wide.camera().projection());
    }

    @Test
    void buildOfANonPositiveFrameFallsBackToASixteenByNineAspect() {
        GlWorldBuilder.Result r = GlWorldBuilder.build(
                List.of(entry(bareModel(), 0f, 0f, 0f)), List.of(), 0, 0);

        assertThat(r.camera()).isNotNull();
    }
}
