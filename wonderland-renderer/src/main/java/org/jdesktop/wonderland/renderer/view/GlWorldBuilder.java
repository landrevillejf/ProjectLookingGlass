/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.4b: assemble a whole world into a JOGL {@link GlScene}
 * and seat the {@link Camera} that frames it -- the java3d-free twin of
 * {@link WorldScene#build}.
 *
 * {@code WorldScene} hangs each cell under a Java 3D {@code TransformGroup} and
 * reads its framing back off {@code BoundingSphere}s; this builder walks exactly
 * the same cells and pictures but emits a flat draw list of {@link GlMesh}es and
 * one column-major model matrix each, which is what {@code GlScene} draws. The
 * placement rule is identical -- a cell's own {@code T·R(yaw)·S} outside the
 * imported model's {@code .dep} normalisation inside -- and so is the framing:
 * the standing cells are clustered into one neighbourhood ({@link WorldScene#focusOn}),
 * the eye is backed off until the subject's eight corners fit the frustum, and a
 * world with residents is entered facing them.
 *
 * <p><strong>Pure, so it is asserted rather than probed.</strong> Nothing here
 * touches a GL context: {@code GlScene.add} and {@code GlMesh} only record data,
 * and {@code Camera} is arithmetic. The two pieces {@code WorldScene} expressed
 * through a java3d {@code Transform3D} -- {@link #backgroundMatrix} and
 * {@link #extentBox} -- are re-expressed on {@code org.jogamp.vecmath.Matrix4d}
 * (kept by the port as standalone maths, not lg3d), so the whole walk runs
 * headless and its placement / framing / corner-fit is unit-tested. The only
 * java3d-free-but-live step left is {@code GlScene.draw}, which the probe drives.</p>
 *
 * <p>This builder reuses {@code WorldScene}'s package-private pure framing
 * helpers ({@code Box}, {@code focusOn}, {@code standsUp}, {@code crowdEntry},
 * {@code pictureBox}, {@code collectShadow}, {@code tagQuad}) so there is one framing
 * rule in the tree, not two that can drift. At the roadmap's cutover those
 * helpers move to a java3d-free home with this class and {@code WorldScene} is
 * deleted; until then the JOGL path renders beside the java3d one and neither
 * disturbs the other.</p>
 */
package org.jdesktop.wonderland.renderer.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jdesktop.wonderland.modules.artimport.DeployedModel;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.jdesktop.wonderland.renderer.gl.Camera;
import org.jdesktop.wonderland.renderer.gl.GlMesh;
import org.jdesktop.wonderland.renderer.gl.GlScene;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshBuilder;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.jdesktop.wonderland.renderer.mesh.MaterialAppearance;
import org.jdesktop.wonderland.renderer.mesh.TextureBindingPlan;
import org.jdesktop.wonderland.renderer.mesh.TextureSource;
import org.jdesktop.wonderland.renderer.transform.WorldTransformMath;
import org.jdesktop.wonderland.renderer.view.WorldScene.Box;
import org.jdesktop.wonderland.renderer.view.WorldScene.Entry;
import org.jdesktop.wonderland.renderer.view.WorldScene.Picture;
import org.jogamp.vecmath.AxisAngle4d;
import org.jogamp.vecmath.Matrix4d;
import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Vector3d;
import org.jogamp.vecmath.Vector3f;

/**
 * Builds a JOGL {@link GlScene} and its framing {@link Camera} from the same
 * entries and pictures {@link WorldScene#build} consumes. Pure: no GL context.
 */
public final class GlWorldBuilder {

    // Framing constants, ported verbatim from WorldViewer / WorldScene so the
    // JOGL camera sits where the java3d one did.
    static final double FIELD_OF_VIEW_RAD = Math.toRadians(45);
    static final double FRONT_CLIP_M = 0.05;
    static final double FLAT_TILT_RAD = Math.toRadians(52);
    static final double TALL_TILT_RAD = Math.toRadians(18);
    static final double HOME_MARGIN = 1.18;
    static final int CORNER_FIT_PASSES = 4;
    static final double SKY_REACH = 6.0;
    static final double CLIP_STANDOFF = 2.75;
    static final float FRAMING_MARGIN = 18f;

    private GlWorldBuilder() {
    }

    /**
     * The built world: a draw list, the camera that frames it, and the counts and
     * framing volumes the probe and the HUD report.
     *
     * @param scene    the meshes and models to draw; never null
     * @param camera   the eye/lens the scene is framed through; never null
     * @param placed   how many entries contributed geometry
     * @param skipped  how many entries had no loadable, affine model
     * @param pictures how many picture cards were hung
     * @param center   the framed subject's centre
     * @param half     the framed subject's half-extents
     * @param entry    where a visitor arrives, or null when nobody is standing
     */
    public record Result(GlScene scene, Camera camera, int placed, int skipped,
            int pictures, Point3d center, Vector3d half, Point3d entry) {
    }

    /**
     * Assemble the world at {@code width}x{@code height}'s aspect ratio.
     *
     * @param entries  the model cells, each carrying its own texture source
     * @param pictures the cells that show a file rather than a model
     * @param width    the frame width in pixels; the aspect the camera fits to
     * @param height   the frame height in pixels
     * @return the scene, its framing camera and the framing volumes
     */
    public static Result build(List<Entry> entries, List<Picture> pictures,
            int width, int height) {
        GlScene scene = new GlScene();
        ColladaMeshBuilder meshBuilder = new ColladaMeshBuilder();
        List<Box> standing = new ArrayList<>();
        List<Box> shaded = new ArrayList<>();
        Box spread = null;
        int placed = 0;
        int skipped = 0;
        for (Entry entry : nullSafe(entries)) {
            if (entry == null || entry.model() == null || !entry.model().isLoaded()) {
                skipped++;
                continue;
            }
            Matrix4d pose = cellPose(entry);
            Matrix4d whole = new Matrix4d();
            whole.mul(pose, backgroundMatrix(entry.model()));
            if (!WorldTransformMath.isAffine(WorldTransformMath.toRowMajor(whole))) {
                // A cell whose composed matrix collapses space costs one object,
                // never the world -- the same tolerance WorldScene keeps.
                skipped++;
                continue;
            }
            addModel(scene, meshBuilder, entry, whole);
            // The framing reach is measured off the .dep volume seated by the
            // cell's OWN placement, never the background that already shrank the
            // art to metres (see WorldScene.extentOf).
            Box cell = extentBox(entry, pose);
            spread = (spread == null) ? cell : spread.union(cell);
            if (WorldScene.standsUp(cell)) {
                standing.add(cell);
            }
            WorldScene.collectShadow(shaded, cell);
            placed++;
        }
        int hung = 0;
        for (Picture picture : nullSafe(pictures)) {
            if (picture == null || !picture.hasExtent()) {
                continue;
            }
            Box plane = WorldScene.pictureBox(picture);
            spread = (spread == null) ? plane : spread.union(plane);
            if (WorldScene.standsUp(plane)) {
                standing.add(plane);
            }
            addPicture(scene, picture);
            hung++;
        }
        if (spread == null) {
            spread = new Box(new Point3d(0, 0, 0),
                    new Vector3d(FRAMING_MARGIN, FRAMING_MARGIN, FRAMING_MARGIN));
        }
        Box focus = WorldScene.focusOn(standing, spread, shaded);
        Point3d entry = WorldScene.crowdEntry(shaded, focus);
        double aspect = (width > 0 && height > 0) ? (double) width / height : 16.0 / 9.0;
        Camera camera = homeCamera(focus, aspect, cornerRadius(spread));
        return new Result(scene, camera, placed, skipped, hung,
                focus.center(), focus.half(), entry);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return (list == null) ? List.of() : list;
    }

    // ------------------------------------------------------------------
    // Placement (pure, Matrix4d)
    // ------------------------------------------------------------------

    /**
     * A cell's own placement: translate, then turn about the world vertical, then
     * stretch -- {@code T·R(yaw)·S}. A component left at its default contributes
     * nothing, so an unturned, unstretched cell is a bare translation.
     */
    static Matrix4d cellPose(Entry entry) {
        Matrix4d moved = identity();
        moved.m03 = entry.x();
        moved.m13 = entry.y();
        moved.m23 = entry.z();
        Matrix4d pose = moved;
        if (entry.yaw() != 0f) {
            Matrix4d turn = new Matrix4d();
            turn.set(new AxisAngle4d(0.0, 1.0, 0.0, entry.yaw()));
            Matrix4d placed = new Matrix4d();
            placed.mul(moved, turn); // place, then turn: T·R
            pose = placed;
        }
        if (entry.scaleX() != 1f || entry.scaleY() != 1f || entry.scaleZ() != 1f) {
            Matrix4d stretch = identity();
            stretch.m00 = entry.scaleX();
            stretch.m11 = entry.scaleY();
            stretch.m22 = entry.scaleZ();
            Matrix4d turnedAndScaled = new Matrix4d();
            turnedAndScaled.mul(pose, stretch); // T·R·S
            pose = turnedAndScaled;
        }
        return pose;
    }

    /**
     * The normalisation a {@code .dep} records for its model: scale, then rotation
     * about the recorded axis, then translation -- {@code T·R·S}. The Matrix4d twin
     * of {@link WorldScene#backgroundTransform}; an absent or degenerate element is
     * identity, never a guess.
     */
    static Matrix4d backgroundMatrix(ModelLoadResult result) {
        return backgroundOf((result == null) ? null : result.model());
    }

    /**
     * {@link #backgroundMatrix} off the {@code .dep} itself, split out so the
     * scale / rotation / translation branches are asserted headless: a
     * {@code ModelLoadResult} that carries a {@code DeployedModel} can only be
     * built inside its own module, but a {@code DeployedModel} is public data.
     */
    static Matrix4d backgroundOf(DeployedModel dep) {
        Matrix4d out = identity();
        if (dep == null) {
            return out;
        }
        Matrix4d scaled = identity();
        Vector3f scale = dep.backgroundScale();
        if (scale != null && isSane(scale)) {
            scaled.m00 = scale.x;
            scaled.m11 = scale.y;
            scaled.m22 = scale.z;
        }
        Matrix4d rotated = identity();
        Vector3f axis = dep.backgroundRotationAxis();
        float angle = dep.backgroundRotationAngle();
        if (axis != null && angle != 0f && isSane(axis)) {
            double length = Math.sqrt(axis.x * axis.x + axis.y * axis.y
                    + axis.z * axis.z);
            if (length > 1e-6) { // a zero axis is no axis: leave the model as stored
                rotated.set(new AxisAngle4d(axis.x / length, axis.y / length,
                        axis.z / length, angle));
            }
        }
        Matrix4d moved = identity();
        Vector3f shift = dep.backgroundTranslation();
        if (shift != null && isSane(shift)) {
            moved.m03 = shift.x;
            moved.m13 = shift.y;
            moved.m23 = shift.z;
        }
        Matrix4d turnedAndSized = new Matrix4d();
        turnedAndSized.mul(rotated, scaled);
        out.mul(moved, turnedAndSized);
        return out;
    }

    /**
     * A cell's placed volume from the {@code .dep} bounds (else the mesh's own
     * vertices, else the cell's footprint) seated by {@code placement}. The
     * Matrix4d twin of {@code WorldScene.extentOf}.
     */
    static Box extentBox(Entry entry, Matrix4d placement) {
        DeployedModel dep = (entry.model() == null) ? null : entry.model().model();
        DeployedModel.Bounds bounds = (dep == null) ? null : dep.bounds();
        Vector3f center = (bounds == null) ? null : bounds.center();
        Vector3f size = (bounds == null) ? null : bounds.dimension();
        if (center != null && size != null && isSane(center) && isSane(size)) {
            return boundsBox(center, size, placement);
        }
        Box measured = measuredBox(entry, placement);
        if (measured != null) {
            return measured;
        }
        Point3d at = transform(placement, entry.x(), entry.y(), entry.z());
        return new Box(at, new Vector3d(entry.footprint() / 2.0,
                entry.footprint() / 2.0, entry.footprint() / 2.0));
    }

    /**
     * The placed volume of a {@code .dep}'s recorded bounds: its eight corners
     * pushed through {@code placement}, folded to an axis-aligned box. Split out of
     * {@link #extentBox} so the corner walk is asserted headless.
     */
    static Box boundsBox(Vector3f center, Vector3f size, Matrix4d placement) {
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY};
        for (int corner = 0; corner < 8; corner++) {
            Point3d p = transform(placement,
                    center.x + (((corner & 1) == 0) ? -1.0 : 1.0) * size.x,
                    center.y + (((corner & 2) == 0) ? -1.0 : 1.0) * size.y,
                    center.z + (((corner & 4) == 0) ? -1.0 : 1.0) * size.z);
            min[0] = Math.min(min[0], p.x);
            min[1] = Math.min(min[1], p.y);
            min[2] = Math.min(min[2], p.z);
            max[0] = Math.max(max[0], p.x);
            max[1] = Math.max(max[1], p.y);
            max[2] = Math.max(max[2], p.z);
        }
        return boxOf(min, max);
    }

    /** The placed box of a model's own vertices, or null when none are readable. */
    private static Box measuredBox(Entry entry, Matrix4d placement) {
        ColladaDocument document = (entry.model() == null)
                ? null : entry.model().document();
        if (document == null) {
            return null;
        }
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY};
        boolean any = false;
        for (String id : document.geometryIds()) {
            ColladaGeometry geometry = document.geometry(id);
            ColladaGeometry.Mesh mesh = (geometry == null) ? null : geometry.mesh();
            ColladaGeometry.Vertices verts = (mesh == null) ? null : mesh.vertices();
            ColladaGeometry.Input position = null;
            if (verts != null) {
                for (ColladaGeometry.Input in : verts.inputs()) {
                    if ("POSITION".equalsIgnoreCase(in.semantic())) {
                        position = in;
                        break;
                    }
                }
            }
            if (position == null) {
                continue;
            }
            String ref = position.source();
            ColladaGeometry.Source source = (ref == null) ? null
                    : mesh.sourceById(ref.startsWith("#") ? ref.substring(1) : ref);
            if (source == null) {
                continue;
            }
            double[] data = source.data();
            int stride = (source.stride() >= 3) ? source.stride() : 3;
            for (int at = 0; at + 2 < data.length; at += stride) {
                Point3d p = transform(placement, data[at], data[at + 1], data[at + 2]);
                min[0] = Math.min(min[0], p.x);
                min[1] = Math.min(min[1], p.y);
                min[2] = Math.min(min[2], p.z);
                max[0] = Math.max(max[0], p.x);
                max[1] = Math.max(max[1], p.y);
                max[2] = Math.max(max[2], p.z);
                any = true;
            }
        }
        return any ? boxOf(min, max) : null;
    }

    private static Box boxOf(double[] min, double[] max) {
        Point3d middle = new Point3d((min[0] + max[0]) / 2.0,
                (min[1] + max[1]) / 2.0, (min[2] + max[2]) / 2.0);
        return new Box(middle, new Vector3d(max[0] - middle.x,
                max[1] - middle.y, max[2] - middle.z));
    }

    // ------------------------------------------------------------------
    // The COLLADA walk (pure: records meshes, touches no GL)
    // ------------------------------------------------------------------

    private static void addModel(GlScene scene, ColladaMeshBuilder meshBuilder,
            Entry entry, Matrix4d whole) {
        ColladaDocument document = entry.model().document();
        if (document == null) {
            return;
        }
        TextureBindingPlan plan = new TextureBindingPlan(document,
                entry.model().presentTextures() == null
                        ? List.of() : entry.model().presentTextures());
        Map<String, MaterialAppearance> appearances = new LinkedHashMap<>();
        Map<String, byte[]> textureCache = new HashMap<>();
        for (ColladaNode.WorldMatrix wm : document.worldMatrices()) {
            ColladaNode node = wm.node();
            if (node.geometries().isEmpty()) {
                continue;
            }
            double[] nodeRow = WorldTransformMath.toRowMajor(wm.matrix());
            if (!WorldTransformMath.isAffine(nodeRow)) {
                continue; // a degenerate node costs that node, not the cell
            }
            Matrix4d meshMatrix = new Matrix4d();
            meshMatrix.mul(whole, new Matrix4d(nodeRow));
            float[] model = toColumnMajor(meshMatrix);
            for (ColladaNode.InstanceGeometry ig : node.geometries()) {
                Map<String, String> bindings = bindingsOf(ig);
                ColladaGeometry geom = document.geometry(stripHash(ig.url()));
                if (geom == null) {
                    continue;
                }
                for (ColladaMeshData data : meshBuilder.build(geom)) {
                    String materialId = bindings.getOrDefault(
                            data.material(), data.material());
                    MaterialAppearance ma = appearances.computeIfAbsent(
                            materialId, plan::forMaterial);
                    float[] rgba = (ma == null) ? null : ma.rgba();
                    byte[] texture = (ma == null || !ma.isTextured()) ? null
                            : cached(textureCache, entry.textures(), ma.texturePath());
                    scene.add(new GlMesh(data), model, rgba, texture);
                }
            }
        }
    }

    private static void addPicture(GlScene scene, Picture picture) {
        byte[] image = picture.hasImage() ? picture.image() : null;
        Matrix4d moved = identity();
        moved.m03 = picture.x();
        moved.m13 = picture.y();
        moved.m23 = picture.z();
        Matrix4d seat = moved;
        if (picture.yaw() != 0f) {
            Matrix4d turn = new Matrix4d();
            turn.set(new AxisAngle4d(0.0, 1.0, 0.0, picture.yaw()));
            Matrix4d hung = new Matrix4d();
            hung.mul(moved, turn); // hang, then turn: T·R
            seat = hung;
        }
        ColladaMeshData quad = WorldScene.tagQuad(picture.width(), picture.height());
        scene.add(new GlMesh(quad), toColumnMajor(seat),
                new float[] {1f, 1f, 1f, 1f}, image);
    }

    static byte[] cached(Map<String, byte[]> cache, TextureSource source,
            String path) {
        if (path == null) {
            return null;
        }
        if (cache.containsKey(path)) {
            return cache.get(path);
        }
        byte[] bytes = read(source, path);
        cache.put(path, bytes);
        return bytes;
    }

    static byte[] read(TextureSource source, String path) {
        if (source == null) {
            return null;
        }
        try {
            return source.read(path);
        } catch (RuntimeException e) {
            return null; // a texture that will not read is a colour fallback
        }
    }

    static Map<String, String> bindingsOf(ColladaNode.InstanceGeometry ig) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ColladaNode.InstanceMaterial im : ig.materials()) {
            if (im.symbol() != null && im.target() != null) {
                out.putIfAbsent(im.symbol(), im.target());
            }
        }
        return out;
    }

    static String stripHash(String url) {
        if (url == null) {
            return null;
        }
        int hash = url.lastIndexOf('#');
        return hash < 0 ? url : url.substring(hash + 1);
    }

    // ------------------------------------------------------------------
    // Framing / camera (pure)
    // ------------------------------------------------------------------

    /**
     * The home camera: outside the framed subject, tilted by how flat it is, backed
     * off until its eight corners fit the frustum. The JOGL twin of
     * {@code WorldViewer.frameHome}.
     */
    static Camera homeCamera(Box focus, double aspect, double reach) {
        Point3d center = focus.center();
        Vector3d half = focus.half();
        double spread = Math.max(half.x, half.z);
        double flatness = (spread <= 1e-9) ? 0.0 : Math.min(1.0, half.y / spread);
        double tilt = FLAT_TILT_RAD - (FLAT_TILT_RAD - TALL_TILT_RAD) * flatness;
        double tanVertical = Math.tan(FIELD_OF_VIEW_RAD / 2.0);
        double tanHorizontal = tanVertical * aspect;
        double distance = Math.max(Math.max(
                half.x / tanHorizontal,
                (half.z * Math.sin(tilt) + half.y * Math.cos(tilt)) / tanVertical),
                half.y + 1.0) * HOME_MARGIN;
        for (int pass = 0; pass < CORNER_FIT_PASSES; pass++) {
            double needed = fitScale(half, tilt, distance, tanHorizontal, tanVertical);
            if (needed <= 1.0) {
                break;
            }
            distance *= needed;
        }
        double eyeX = center.x;
        double eyeY = center.y + distance * Math.sin(tilt);
        double eyeZ = center.z + distance * Math.cos(tilt);
        double focusRadius = cornerRadius(focus);
        double far = Math.max(1000.0, reach * SKY_REACH + focusRadius * CLIP_STANDOFF);
        return new Camera((float) eyeX, (float) eyeY, (float) eyeZ,
                (float) center.x, (float) center.y, (float) center.z,
                0f, 1f, 0f,
                (float) FIELD_OF_VIEW_RAD, (float) aspect,
                (float) FRONT_CLIP_M, (float) far);
    }

    /**
     * How much further back the eye has to move for the subject's eight corners to
     * fall inside the frustum: 1.0 when they already do. Ported verbatim from
     * {@code WorldViewer.fitScale}.
     */
    static double fitScale(Vector3d half, double tilt, double distance,
            double tanHorizontal, double tanVertical) {
        double sin = Math.sin(tilt);
        double cos = Math.cos(tilt);
        double worst = 1.0;
        for (int corner = 0; corner < 8; corner++) {
            double px = ((corner & 1) == 0) ? -half.x : half.x;
            double py = ((corner & 2) == 0) ? -half.y : half.y;
            double pz = ((corner & 4) == 0) ? -half.z : half.z;
            double vx = px;
            double vy = py - distance * sin;
            double vz = pz - distance * cos;
            double depth = vy * -sin + vz * -cos;
            if (depth <= 1e-6) {
                continue; // behind the eye: this corner cannot be framed
            }
            double screenX = Math.abs(vx);
            double screenY = Math.abs(vy * cos + vz * -sin);
            worst = Math.max(worst, screenX / (depth * tanHorizontal));
            worst = Math.max(worst, screenY / (depth * tanVertical));
        }
        return worst;
    }

    /** The distance from a box's centre to a corner: its bounding-sphere radius. */
    static double cornerRadius(Box box) {
        Vector3d half = box.half();
        return Math.sqrt(half.x * half.x + half.y * half.y + half.z * half.z);
    }

    // ------------------------------------------------------------------
    // Small matrix helpers (pure)
    // ------------------------------------------------------------------

    private static Matrix4d identity() {
        Matrix4d m = new Matrix4d();
        m.setIdentity();
        return m;
    }

    private static Point3d transform(Matrix4d m, double x, double y, double z) {
        return new Point3d(
                m.m00 * x + m.m01 * y + m.m02 * z + m.m03,
                m.m10 * x + m.m11 * y + m.m12 * z + m.m13,
                m.m20 * x + m.m21 * y + m.m22 * z + m.m23);
    }

    /**
     * A row-major {@link Matrix4d} as the column-major {@code float[16]} OpenGL
     * multiplies vertices by: element (r, c) lands at index {@code c * 4 + r}, the
     * flat transpose of the row-major layout, so the translation ends up at 12/13/14.
     */
    static float[] toColumnMajor(Matrix4d m) {
        return new float[] {
            (float) m.m00, (float) m.m10, (float) m.m20, (float) m.m30,
            (float) m.m01, (float) m.m11, (float) m.m21, (float) m.m31,
            (float) m.m02, (float) m.m12, (float) m.m22, (float) m.m32,
            (float) m.m03, (float) m.m13, (float) m.m23, (float) m.m33,
        };
    }

    /** A vector of finite numbers; a NaN in the art must not poison the scene. */
    private static boolean isSane(Vector3f v) {
        return !(Float.isNaN(v.x) || Float.isNaN(v.y) || Float.isNaN(v.z)
                || Float.isInfinite(v.x) || Float.isInfinite(v.y)
                || Float.isInfinite(v.z));
    }
}
