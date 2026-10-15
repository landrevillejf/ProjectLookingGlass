/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: a whole world as one Java 3D branch, with no lg3d in
 * sight.
 *
 * Everything before this class rendered <em>one cell</em> and handed the result
 * to the lg3d desktop: {@code WonderlandWorldContainer} is a {@code Component3D},
 * so it needs a live AppConnector session and a virtual universe, and the
 * desktop's 2D shell has neither. That is the real reason a world was never
 * seen on screen -- not the layout of a preview panel. The other half is that
 * nothing in the tree ever called {@code WonderlandModelCell.setModelLoadResult},
 * so a walked-in cell had no model to hand the seam and {@code createCellRenderer}
 * returned null: even in the 3D desktop the world would have mounted nothing.
 *
 * This class is the missing middle: give it the cells of a world, already loaded
 * (a {@code WorldStore} plus {@code ModelLoader} does that, over HTTP or off a
 * jar), and it builds the scene the original Wonderland client showed -- every
 * model at its authored place, lit, visible. It hangs each cell's BranchGroup
 * under a {@link SharedGroup} because Java 3D attaches a BranchGroup only to a
 * Locale or a SharedGroup, and it sets each renderer's status to VISIBLE because
 * an {@code ModelRenderer}'s Switch opens on CHILD_NONE -- a cell that is loaded
 * but not shown draws nothing, which is exactly the silent-empty failure this
 * class exists to end.
 *
 * <p><strong>The third silent-empty, and the one that hid a whole world.</strong>
 * A {@code .dep} is not only a pointer to geometry: it records the normalisation
 * the 2006 importer computed to seat the model at the origin at world scale, and
 * the shipped art proves how much that matters -- {@code DefaultTown5.kmz} is a
 * model over 160 units wide behind a {@code modelBGScale} of 0.0254, so a
 * renderer that ignores it draws the town forty times too big, with the camera
 * inside one wall. So the background transform is read here, off
 * {@link ModelLoadResult#model()}, and composed with the cell's own placement
 * into one matrix per cell: {@code T(translation)·R(yaw)·S(cell scale)} outside,
 * {@code T·R·S} of the imported model inside. The wfs stores each cell's
 * rotation as an axis/angle pair and every shipped world uses the Y axis, so the
 * desktop's yaw vocabulary is one angle, and it arrives on the wire as
 * {@code yaw}.</p>
 *
 * Not constructed in CI: the Java 3D nodes need the native rendering stack. The
 * pure part -- which entries are placeable at all -- is the caller's, and is
 * tested there.
 */
package org.jdesktop.wonderland.renderer.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.renderer.j3d.J3dMeshFactory;
import org.jdesktop.wonderland.renderer.j3d.ModelRenderer;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.jdesktop.wonderland.renderer.mesh.MaterialAppearance;
import org.jdesktop.wonderland.renderer.mesh.TextureSource;
import org.jdesktop.wonderland.renderer.transform.WorldTransformMath;
import org.jogamp.java3d.BoundingSphere;
import org.jogamp.java3d.BranchGroup;
import org.jogamp.java3d.Link;
import org.jogamp.java3d.SharedGroup;
import org.jogamp.java3d.Shape3D;
import org.jogamp.java3d.Transform3D;
import org.jogamp.java3d.TransformGroup;
import org.jogamp.vecmath.Color3f;
import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Point3f;
import org.jogamp.vecmath.Vector3d;
import org.jogamp.vecmath.Vector3f;

/** One world's loaded models, assembled and lit under a single attachable branch. */
public final class WorldScene {

    /**
     * The radius added to the cells' own spread: a room is bigger than its
     * origin, and the camera has to start outside the walls it is looking at.
     */
    private static final float FRAMING_MARGIN = 18f;

    /**
     * How tall a cell's art has to be before it counts as something to look at.
     *
     * <p>The shipped pavilion world is the reason this exists
     * (build-gradle/scratch/WlProbe11). It is three cells, and their recorded
     * half-extents say what each one is: a grass circle 120 m across and 0.05 m
     * thick, a hexagonal terrain tile 90 m across and 3 m thick, and the pavilion
     * itself 30.8 m across and 4.99 m tall. Framing the union of all three puts
     * the eye 170 m out and 59 m up, looking down at a lawn — which is the grey
     * floor plan the world browser kept showing. So the camera is seated on the
     * cells that stand up and falls back to the whole world when none do.</p>
     *
     * <p>Standing is a ratio, not an absolute height, because ground is thick
     * exactly where it is wide: a 3 m relief on a 90 m terrain tile is still
     * terrain (3.3 % of its reach), while a 5 m roof on a 31 m building is a
     * building (16 %). The absolute floor below is what keeps a small decal from
     * being framed as a subject just because it is proportionally tall.</p>
     */
    private static final double MIN_STANDING_M = 1.0;

    /** How much of its own horizontal reach a cell must rise over to be a subject. */
    private static final double MIN_STANDING_RATIO = 0.15;

    /**
     * How wide a cell may be and still be a subject at all, measured as its
     * half-extent along the ground. The ratio alone was not enough: the gallery
     * world's terrain is a 59 m mountain on a 528 m field, so it is 11 % relief and
     * would keep failing the ratio test only for as long as the arithmetic stayed
     * where it is. An object whose own footprint runs further than a person can see
     * is ground they stand on, however tall a peak it carries -- and framing on it
     * is what put the camera several hundred metres above the lawn, the plan view
     * this port keeps being judged against.
     */
    private static final double MAX_STANDING_SPREAD_M = 120.0;

    /** How far the eye may stand from a neighbourhood and still call it one. */
    private static final double FOCUS_CLUSTER_RADIUS_M = 40.0;

    /** How much wider the sky box is than the world it holds. */
    private static final float SKY_FACTOR = 3f;

    /**
     * One cell to place: its loaded model, where the world puts it in metres
     * (y up), how it is turned and stretched, how wide its footprint is for
     * framing, and where its textures come from. A null or unloaded model is
     * skipped by {@link #build}, not an error -- a world with half its art is
     * still a world.
     *
     * <p>The texture source travels with the entry because it belongs to the
     * model: {@code ModelLoader} decided which textures are present against the
     * module the {@code wla://} reference named, which is not necessarily the
     * module the world was listed under.</p>
     *
     * <p>{@code tag} is the name to float over the cell, or null for none. It is
     * the caller's decision, not this class's: only the app layer knows that a
     * cell is a resident and not a sofa, and a world browser that guessed would
     * label the furniture and miss the people.</p>
     */
    public record Entry(String name, ModelLoadResult model, float x, float y, float z,
            float yaw, float scaleX, float scaleY, float scaleZ, float footprint,
            TextureSource textures, String tag) {

        /** An entry the world did not turn or stretch, textures from {@code source}. */
        public Entry(String name, ModelLoadResult model, float x, float y, float z,
                TextureSource textures) {
            this(name, model, x, y, z, 0f, 1f, 1f, 1f, 0f, textures, null);
        }

        /** An entry with nothing to read textures from: colour fallbacks only. */
        public Entry(String name, ModelLoadResult model, float x, float y, float z) {
            this(name, model, x, y, z, 0f, 1f, 1f, 1f, 0f, TextureSource.NONE, null);
        }

        public Entry {
            if (textures == null) {
                textures = TextureSource.NONE;
            }
            // A missing or degenerate component is the identity: an art file with
            // a blank <scale> must draw at its authored size, not collapse.
            if (!isSane(scaleX)) {
                scaleX = 1f;
            }
            if (!isSane(scaleY)) {
                scaleY = 1f;
            }
            if (!isSane(scaleZ)) {
                scaleZ = 1f;
            }
            if (!isSane(yaw)) {
                yaw = 0f;
            }
            if (footprint < 0f || !isSane(footprint)) {
                footprint = 0f;
            }
        }

        private static boolean isSane(float v) {
            return !Float.isNaN(v) && !Float.isInfinite(v) && v != 0f;
        }
    }

    /**
     * One picture the world pinned up: a poster, a document's page, a whiteboard,
     * a remote window. Not an {@link Entry} because it has no model to load -- the
     * world gave it a file and a rectangle, and the rectangle is the geometry.
     *
     * <p>This is the half of a stock world that the port kept losing. Nine of the
     * combined world's forty-one cells and four of the meeting complex's eleven
     * are planes of this kind, and a loader that recognised only
     * {@code model-cell} drew those worlds with their posters, screens and
     * whiteboards missing -- a field with two posts in it and nothing else, which
     * is what a person looking at the result correctly called a scrapped world.</p>
     *
     * <p>{@code image} is the encoded bytes of the picture, or null for the GUI
     * cell whose contents no headless port can produce (a live remote window, an
     * SVG board): those are drawn as the plain card they are standing in for,
     * which is an object in the world rather than an absent one. {@code width} and
     * {@code height} are metres, already grown by the cell's own stretch, and
     * {@code yaw} turns the card about the vertical axis so it faces the way the
     * world pointed it. {@code tag} is the name to hang beside it -- usually null,
     * because a poster says what it is.</p>
     */
    public record Picture(String name, byte[] image, float x, float y, float z,
            float yaw, float width, float height, String tag) {

        public Picture {
            if (!Entry.isSane(width) || width < 0f) {
                width = 0f;
            }
            if (!Entry.isSane(height) || height < 0f) {
                height = 0f;
            }
            if (!Entry.isSane(yaw)) {
                yaw = 0f;
            }
        }

        /** Whether there is anything to see on the card. */
        public boolean hasImage() {
            return image != null && image.length > 0;
        }

        /** Whether the card has an area at all; a zero-sized plane is not worth building. */
        public boolean hasExtent() {
            return width > 0f && height > 0f;
        }
    }

    /**
     * How tall a person's name reads. The 2009 clinic's tags are about a sixth of
     * the figure they sit over: measured off the screenshot, a 1.7 m avatar carries
     * text roughly 17 cm tall. The first cut here was 34 cm and the walk-through
     * showed why that is wrong -- at a metre and a half, half a metre of letters
     * covers the room it is supposed to be labelling.
     */
    private static final double TAG_FIGURE_TEXT_M = 0.17;

    /**
     * How tall a room's name reads. Larger than a person's, because a room's tag
     * is hung near the ceiling to be seen across the lobby, and it has to survive
     * that distance as legible text.
     */
    private static final double TAG_ROOM_TEXT_M = 0.30;

    /** Clear space between the top of a thing and the tag over it. */
    private static final double TAG_GAP_M = 0.09;

    /**
     * How high off a room's floor its tag hangs. It is a ceiling-mounted sign, not
     * a floating caption at throat height: the eye reads a room's name by looking
     * <em>up</em> into it, which also keeps it clear of the name tags of everyone
     * standing in there.
     */
    private static final double TAG_ROOM_MOUNT_M = 2.15;

    /** A cell this tall or shorter, measured to its top, is a person rather than a room. */
    private static final double TAG_FIGURE_M = 1.2;

    /**
     * How deep a room must be before it counts as a space a person stands <em>in</em>
     * and is signed on its far wall rather than over its own middle: 8 m across.
     */
    private static final double TAG_WALL_MOUNT_M = 4.0;

    /** How far off that wall the sign hangs, so the card is not inside the plaster. */
    private static final double TAG_WALL_INSET_M = 0.35;

    /** The shortest cell worth a tag: ground, lawns and floors are not things to name. */
    private static final double TAG_MIN_HEIGHT_M = 0.3;

    /**
     * The widest cell that gets one. A terrain tile is 240 m of lawn whose name is
     * a file name; its tag would hang in the middle of every view of the world it
     * is standing on.
     */
    private static final double TAG_MAX_SPREAD_M = 40.0;

    /**
     * The largest footprint that gets a ground shadow, measured corner to corner.
     * A person is a metre across; a 6 m conference table is not a thing the 2009
     * client shaded, and a blob under it would read as a stain.
     */
    private static final double SHADOW_MAX_FOOTPRINT_M = 1.6;

    /** How much wider the shadow pool is than the feet standing in it. */
    private static final double SHADOW_SPREAD = 1.7;

    /** How far the pool is lifted off the floor it lies on, in metres. */
    private static final double SHADOW_LIFT_M = 0.03;

    /**
     * How far in front of the residents a visitor stands, in metres.
     *
     * <p>Far enough to see them as people rather than as faces: the 2009 view is
     * taken from several metres back, with the whole group, the sofas behind them
     * and the ceiling over all of it in one frame. Standing an arm's length from
     * the nearest resident fills the window with one head and shows nothing of the
     * room the head is in.</p>
     */
    private static final double ENTRY_DISTANCE_M = 5.0;

    /**
     * How far inside the world's near wall the entry pose is kept, in metres. The
     * group's own depth can push the back-off through the wall a visitor is meant
     * to have their back to, and standing outside the room you have entered is not
     * standing in it.
     */
    private static final double ENTRY_WALL_INSET_M = 0.6;

    private final BranchGroup root;
    private final int placedCount;
    private final int skippedCount;
    private final int pictureCount;
    private final Point3d center;
    private final Vector3d halfExtent;
    private final float radius;
    private final float reach;
    private final List<NameTag> nameTags;
    private final int shadowCount;
    private final Point3d entryPoint;

    private WorldScene(BranchGroup root, int placedCount, int skippedCount,
            Point3d center, Vector3d halfExtent, float radius, float reach,
            Point3d entryPoint,
            List<NameTag> nameTags, int shadowCount, int pictureCount) {
        this.root = root;
        this.placedCount = placedCount;
        this.skippedCount = skippedCount;
        this.pictureCount = pictureCount;
        this.center = center;
        this.halfExtent = halfExtent;
        this.radius = radius;
        this.reach = reach;
        this.entryPoint = entryPoint;
        this.nameTags = List.copyOf(nameTags);
        this.shadowCount = shadowCount;
    }

    /**
     * Build the world.
     *
     * @param entries the cells to place, each carrying its own texture source
     * @return the scene; {@link #cellCount()} says how much of the world
     *         actually had geometry, because an empty scene is a truthful answer
     *         about a world whose models did not load, not a failure of this call
     */
    public static WorldScene build(List<Entry> entries) {
        return build(entries, List.of());
    }

    /**
     * Build the world, models and pictures both.
     *
     * @param pictures the cells that show a file rather than a model; see
     *                 {@link Picture}
     */
    public static WorldScene build(List<Entry> entries, List<Picture> pictures) {
        BranchGroup branch = new BranchGroup();
        branch.setCapability(BranchGroup.ALLOW_DETACH);

        // Java 3D lets a BranchGroup be attached to a Locale or a SharedGroup
        // only, and each renderer hands back a BranchGroup: one SharedGroup per
        // cell, hung under that cell's pose.
        List<Entry> placed = new ArrayList<>();
        int skipped = 0;
        BoundingSphere extent = null;
        Box spread = null;
        List<Box> standing = new ArrayList<>();
        List<Tag> tags = new ArrayList<>();
        List<Box> shaded = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry == null || entry.model() == null || !entry.model().isLoaded()) {
                skipped++;
                continue;
            }
            ModelRenderer renderer = new ModelRenderer(entry.model().document(),
                    entry.model().presentTextures(), entry.textures());
            // The renderer's Switch opens on CHILD_NONE: loaded is not shown.
            renderer.setStatus(CellStatus.VISIBLE, true);

            // One matrix per cell: the authored placement outside the imported
            // model's normalisation, so the .dep's scale/rotation/translation is
            // never silently dropped (see the header).
            Transform3D moved = new Transform3D();
            moved.setTranslation(new Vector3f(entry.x(), entry.y(), entry.z()));
            Transform3D pose = moved;
            if (entry.yaw() != 0f) {
                pose = new Transform3D();
                pose.mul(moved, yawTurn(entry.yaw())); // place, then turn: T·R
            }
            if (entry.scaleX() != 1f || entry.scaleY() != 1f || entry.scaleZ() != 1f) {
                Transform3D stretch = new Transform3D();
                stretch.setScale(new org.jogamp.vecmath.Vector3d(
                        entry.scaleX(), entry.scaleY(), entry.scaleZ()));
                Transform3D turnedAndScaled = new Transform3D();
                turnedAndScaled.mul(pose, stretch); // T·R·S of the cell itself
                pose = turnedAndScaled;
            }
            Transform3D whole = new Transform3D();
            whole.mul(pose, backgroundTransform(entry.model()));
            // A cell whose composed matrix collapses space is skipped, not fatal.
            // Java 3D throws BadTransformException when a TransformGroup is handed
            // a non-affine transform, and it throws it at addBranchGraph time --
            // which is after this loop, so the one degenerate .dep in the combined
            // world (a backgroundScale of 0 recorded by its importer) was taking
            // the other 40 cells down with it and the world browser had no picture
            // at all for a world that has plenty. Dropping the cell costs one
            // object; dropping the branch cost the world.
            if (!isAffine(whole)) {
                skipped++;
                continue;
            }
            TransformGroup place = new TransformGroup(whole);
            place.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
            place.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);

            // No capability on the SharedGroup: every cell is attached here,
            // before the branch is compiled and handed to the universe, so the
            // graph is static and needs no write permission at run time.
            SharedGroup mount = new SharedGroup();
            mount.addChild(renderer.getScene());
            // A SharedGroup is never added to a group directly: Java 3D takes
            // it through a Link, and says so by throwing at attach time with
            // "SharedGroup must be referenced through a link node". One Link per
            // cell is what lets the same model be placed several times, which is
            // exactly what a world of repeated rooms is.
            place.addChild(new Link(mount));
            branch.addChild(place);
            placed.add(entry);
            // The world's reach is measured off the volume the .dep recorded for
            // the art itself, seated by the cell's own placement -- never off the
            // background matrix, which has already shrunk the art to metres (see
            // extentOf).
            Box cell = extentOf(entry, pose);
            extent = union(extent, cell.sphere());
            spread = (spread == null) ? cell : spread.union(cell);
            if (standsUp(cell)) {
                standing.add(cell);
            }
            collectTag(tags, entry, cell);
            collectShadow(shaded, cell);
        }
        // The pictures occupy space exactly like a model does: a world whose only
        // standing object is a 2 m poster still has to be framed on it, and a sky
        // sized to the models alone leaves that poster outside the box.
        List<Picture> hung = new ArrayList<>();
        for (Picture picture : pictures) {
            if (picture == null || !picture.hasExtent()) {
                continue;
            }
            Box plane = pictureBox(picture);
            extent = union(extent, plane.sphere());
            spread = (spread == null) ? plane : spread.union(plane);
            if (standsUp(plane)) {
                standing.add(plane);
            }
            hung.add(picture);
        }
        if (extent == null) {
            extent = new BoundingSphere(new Point3d(0, 0, 0), FRAMING_MARGIN);
        }
        // One art map and one factory for the sky, the shadows and the labels, so
        // the ramp texture and a dozen label textures travel through a single
        // texture cache rather than one per call site. It is built before the sky
        // because the sky is now textured like everything else.
        Map<String, byte[]> art = new HashMap<>();
        J3dMeshFactory factory = new J3dMeshFactory(art::get);
        addSky(branch, extent, art, factory);
        addShadows(branch, shaded, art, factory);
        List<NameTag> tagsBuilt = addTags(branch, tags, art, factory);
        int hungCount = addPictures(branch, hung, art, factory);
        // The sky is built off the whole world; the camera is seated on one
        // neighbourhood of it, never on its property line. The difference is what
        // the combined world measured: its standing cells run from x = -2052 to
        // x = +2052, because the shipping demo packs six separate demo areas onto
        // one parcel, so a frame fitted to that union backed the eye off 2.6 km to
        // look down on the whole plot. That is a map of a world, and a map is what
        // its owner read -- correctly -- as nothing of the world having survived.
        // The subject is therefore the cluster a person would be standing in: the
        // group's room when there is a group, else the busiest corner.
        Box focus = focusOn(standing, spread, shaded);
        return new WorldScene(branch, placed.size(), skipped, focus.center(),
                focus.half(), (float) focus.sphere().getRadius(),
                (float) extent.getRadius(), crowdEntry(shaded, focus), tagsBuilt,
                shaded.size(), hungCount);
    }

    /**
     * The volume the camera is seated on: the standing cells within one
     * neighbourhood of an anchor.
     *
     * @param standing  every placed cell that stands up rather than lies down
     * @param everything the union of everything placed, used when nothing stands
     * @param figures   the shadowed footprints, i.e. the residents to arrive at
     */
    static Box focusOn(List<Box> standing, Box everything, List<Box> figures) {
        if (standing.isEmpty()) {
            return (everything != null) ? everything
                    : new Box(new Point3d(0, 0, 0),
                            new Vector3d(FRAMING_MARGIN, FRAMING_MARGIN,
                                    FRAMING_MARGIN));
        }
        Box anchor = figures.isEmpty()
                ? busiest(standing)
                : nearestTo(standing, crowdCenter(figures));
        return clusterAround(anchor, standing);
    }

    /**
     * The cell the neighbourhood is read from when nobody is standing anywhere: the
     * one with the most neighbours within reach, ties going to the larger
     * footprint. A world's crowd gathers in a room, so the busiest cell <em>is</em>
     * the room, and a frame on the room shows buildings rather than a parcel.
     */
    private static Box busiest(List<Box> standing) {
        Box best = null;
        int bestNeighbours = -1;
        double bestFootprint = -1.0;
        for (Box cell : standing) {
            int neighbours = 0;
            for (Box other : standing) {
                if (other != cell && reachOf(cell, other) <= FOCUS_CLUSTER_RADIUS_M) {
                    neighbours++;
                }
            }
            double footprint = cell.half.x * cell.half.z;
            if (neighbours > bestNeighbours
                    || (neighbours == bestNeighbours && footprint > bestFootprint)) {
                best = cell;
                bestNeighbours = neighbours;
                bestFootprint = footprint;
            }
        }
        return best;
    }

    /** The standing cell a visitor should face: the one nearest the group. */
    private static Box nearestTo(List<Box> standing, Point3d crowd) {
        Box best = standing.get(0);
        double bestReach = Double.POSITIVE_INFINITY;
        for (Box cell : standing) {
            double dx = cell.center.x - crowd.x;
            double dz = cell.center.z - crowd.z;
            double reach = Math.sqrt(dx * dx + dz * dz);
            if (reach < bestReach) {
                best = cell;
                bestReach = reach;
            }
        }
        return best;
    }

    /** The point a group stands around: the mean of its members' centres. */
    private static Point3d crowdCenter(List<Box> figures) {
        double x = 0.0;
        double z = 0.0;
        for (Box figure : figures) {
            x += figure.center.x;
            z += figure.center.z;
        }
        return new Point3d(x / figures.size(), 0.0, z / figures.size());
    }

    /** Everything standing within reach of {@code anchor}, as one box. */
    private static Box clusterAround(Box anchor, List<Box> standing) {
        Box out = anchor;
        for (Box cell : standing) {
            if (cell != anchor && reachOf(anchor, cell) <= FOCUS_CLUSTER_RADIUS_M) {
                out = out.union(cell);
            }
        }
        return out;
    }

    /** Ground distance between two cells' centres; height is not a reason to look away. */
    private static double reachOf(Box a, Box b) {
        double dx = a.center.x - b.center.x;
        double dz = a.center.z - b.center.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The volume one picture takes up in the world: a card of its own width and
     * height, turned the way the world turned it, seated where the world put its
     * centre. The four corners are pushed through the turn rather than the box
     * being rotated, so the half-extents stay axis-aligned -- which is all the
     * framing and the sky size need, and what keeps a card edge-on to the view
     * from being counted as a world one pixel wide.
     */
    static Box pictureBox(Picture picture) {
        double hx = picture.width() / 2.0;
        double hy = picture.height() / 2.0;
        double cos = Math.cos(picture.yaw());
        double sin = Math.sin(picture.yaw());
        double reachX = Math.abs(hx * cos) + Math.abs(hy * sin);
        double reachZ = Math.abs(hx * sin) + Math.abs(hy * cos);
        return new Box(new Point3d(picture.x(), picture.y(), picture.z()),
                new Vector3d(reachX, hy, reachZ));
    }

    /**
     * Hang the pictures. Each is one flat card of its own measure, textured with
     * the file the cell named and lit like the rest of the art, or a plain paper
     * card when the world named a file this port cannot render.
     *
     * <p>The geometry is the tag's {@link #tagQuad}: a vertical card in the
     * {@code xy} plane, wound counter-clockwise seen from the front, because this
     * fork culls back faces and a card wound the other way is a card that is not
     * there. That is also why the {@code yaw} matters -- the shipped posters write
     * a half-turn about Y, and a plane hung without it faces the wall instead of
     * the room.</p>
     *
     * @return how many cards were hung; a picture whose bytes would not decode is
     *         counted out rather than drawn as a black rectangle
     */
    private static int addPictures(BranchGroup branch, List<Picture> pictures,
            Map<String, byte[]> art, J3dMeshFactory factory) {
        int hungCount = 0;
        for (int i = 0; i < pictures.size(); i++) {
            Picture picture = pictures.get(i);
            String path = null;
            if (picture.hasImage()) {
                path = PICTURE_PATH_PREFIX + i;
                art.put(path, picture.image());
            }
            Shape3D card = factory.createShape(
                    tagQuad(picture.width(), picture.height()),
                    new MaterialAppearance(1f, 1f, 1f, 1f, path));
            if (card == null) {
                continue;
            }
            Transform3D moved = new Transform3D();
            moved.setTranslation(new Vector3d(picture.x(), picture.y(),
                    picture.z()));
            Transform3D seat = moved;
            if (picture.yaw() != 0f) {
                seat = new Transform3D();
                seat.mul(moved, yawTurn(picture.yaw())); // hang, then turn: T·R
            }
            TransformGroup placed = new TransformGroup(seat);
            placed.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
            placed.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
            placed.addChild(card);
            branch.addChild(placed);
            hungCount++;
        }
        return hungCount;
    }

    /** The prefix of each picture's entry in the shared art map. */
    private static final String PICTURE_PATH_PREFIX = "lg3d:picture";

    /**
     * The turn a cell's heading describes: that yaw about the world's vertical
     * axis.
     *
     * <p>The axis comes first, because that is the order {@code AxisAngle4f}'s
     * four-argument constructor takes -- {@code (x, y, z, angle)}. Writing the
     * heading first, which is what every other angle-first API in this stack
     * invites, is not a compile error, not a throw and not even a wrong-looking
     * object: the axis becomes {@code (yaw, 0, 1)} and the angle becomes
     * {@code 0}, so the cell is seated where the world put it and then never
     * turned. Read off a rendered clinic, that is what every poster and every
     * turned model had been doing: a print authored a quarter-turn to hang on
     * the east wall drew as a card facing the north one, so from the door it was
     * 8 px of picture at 13 m -- present, textured, and indistinguishable from
     * art that failed to load. Both call sites go through here for that reason,
     * and the test is on this method's matrix rather than on a screenshot.</p>
     */
    static Transform3D yawTurn(float yaw) {
        Transform3D turn = new Transform3D();
        turn.setRotation(new org.jogamp.vecmath.AxisAngle4f(0f, 1f, 0f, yaw));
        return turn;
    }

    /**
     * Where a visitor is put when they enter: a few metres in front of the group of
     * residents, at their floor, looking along -Z at them. {@code null} when the
     * world has nobody standing in it.
     *
     * <p>Entering a world at the edge of its property is entering a map, not a
     * place -- the first walk-through of the clinic came in at the near edge of the
     * ground plane, seven metres of open courtyard ahead, and the visitor's first
     * frame was sky. The obvious second rule was "the biggest room", and it was
     * wrong twice: a clinic's rooms are all 5 m square, so area ties and the
     * tie-break picked a corner exam room with nobody in it, and a 5 m box fills a
     * 45-degree view with wall. What makes a place worth arriving in is that people
     * are standing in it -- the 2009 screenshot is exactly that, one visitor's view
     * of the group in the lobby -- so the world is entered facing its residents.</p>
     */
    static Point3d crowdEntry(List<Box> figures, Box focus) {
        if (figures.isEmpty()) {
            return null;
        }
        double sumX = 0;
        double floor = Double.POSITIVE_INFINITY;
        double nearest = Double.NEGATIVE_INFINITY;
        for (Box figure : figures) {
            sumX += figure.center.x;
            floor = Math.min(floor, figure.center.y - figure.half.y);
            nearest = Math.max(nearest, figure.center.z + figure.half.z);
        }
        // On the near side of the group rather than in the middle of it: standing
        // among the residents puts one of them an arm's length from the lens, which
        // is a face filling the window and nothing else to look at. And no further
        // back than the room allows -- in a hall 16 m deep, five metres past the
        // nearmost body is the wall behind the door.
        double back = Math.min(nearest + ENTRY_DISTANCE_M,
                focus.center.z + focus.half.z - ENTRY_WALL_INSET_M);
        return new Point3d(sumX / figures.size(), floor, back);
    }

    /** One name to float in the world: the text, the point its centre hangs at, its height. */
    record Tag(String text, Point3d at, double textHeight) {
    }

    /**
     * Where one cell's name goes, if it gets one.
     *
     * <p>Two placements, because two kinds of thing are named. A person is tagged
     * over their head, which is where the 2009 client put them and where nothing
     * else can be. A room is tagged <em>inside</em> itself, hung near its ceiling:
     * a room's own top is above its ceiling, so a tag there is invisible to the one
     * person the world is built for -- the one standing in the room -- and a tag at
     * throat height is in that person's face the moment they enter, which is what
     * the first walk-through showed. A thin panel (a directory board) has no room to
     * hang in, so its tag sits just in front of the panel's own face. A cell too flat
     * to stand under (a floor, a lawn, a terrain tile) is not a thing anybody needs
     * named, and is skipped.</p>
     */
    static void collectTag(List<Tag> tags, Entry entry, Box cell) {
        String text = entry.tag();
        if (text == null || text.isBlank() || cell.half.y < TAG_MIN_HEIGHT_M) {
            return;
        }
        // A file name is not a place. The shipped worlds name a cell after the art
        // it points at, so a loader that passed the name through painted
        // "PortalToField-Wood.kmz" in 30 cm letters across a doorway and
        // "MountainLakeTerrainGrassStoneFlat.kmz" over a whole field -- and the
        // world browser that did it is the one being shown these screenshots.
        if (looksLikeFile(text)) {
            return;
        }
        if (Math.max(cell.half.x, cell.half.z) * 2.0 > TAG_MAX_SPREAD_M) {
            return;
        }
        double floor = cell.center.y - cell.half.y;
        Point3d at;
        double height;
        if (cell.half.y <= TAG_FIGURE_M) {
            height = TAG_FIGURE_TEXT_M;
            at = new Point3d(cell.center.x,
                    cell.center.y + cell.half.y + TAG_GAP_M + height / 2.0,
                    cell.center.z);
        } else {
            height = TAG_ROOM_TEXT_M;
            // Near the ceiling, but never through it: a low room (a 2 m corridor)
            // gets its sign just under its own top rather than embedded in it.
            double mount = Math.min(floor + cell.half.y * 2.0 - 0.25,
                    floor + TAG_ROOM_MOUNT_M);
            // A big room is signed on the wall its visitor walks towards. Over the
            // middle of a 22 m hall, a name hung at ceiling height floats in the
            // air in front of the people standing under it -- a room this size is
            // read from one end, and the sign belongs on the far wall, where a
            // doorway sign would be. A small room has no such sightline, so its
            // tag stays just in front of itself, where the map view can see it.
            double z = cell.half.z > TAG_WALL_MOUNT_M
                    ? cell.center.z - cell.half.z + TAG_WALL_INSET_M
                    : cell.center.z + Math.min(cell.half.z + 0.06, 0.25);
            at = new Point3d(cell.center.x, Math.max(mount, floor + 1.0), z);
        }
        tags.add(new Tag(text.trim(), at, height));
    }

    /**
     * Whether a cell's "name" is really the name of the file it points at.
     *
     * <p>Tested by the extension the worlds actually ship, not by a guess at
     * styling: {@code OrientationBricks.png} and {@code owl_3d_demoworld_013_mainbldg
     * .dae.gz} are art references that were written into a name field, while
     * {@code Reception} and {@code Exam Room 2} never carry one.</p>
     */
    static boolean looksLikeFile(String text) {
        String trimmed = text.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot < 0 || dot == trimmed.length() - 1) {
            return false;
        }
        String extension = trimmed.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ART_EXTENSIONS.contains(extension);
    }

    /** The file suffixes a shipped world's art arrives with. */
    private static final java.util.Set<String> ART_EXTENSIONS = java.util.Set.of(
            "kmz", "kmf", "dae", "gz", "zip", "png", "jpg", "jpeg", "gif", "tiff",
            "bmp", "obj", "x", "pdf", "svg", "mp3", "wav", "ogg", "mov", "mp4",
            "dep", "lgcfg", "xml");

    /**
     * Which cells stand on the ground hard enough to darken it. A figure --
     * something between a footstool and a door in height, and small enough to be
     * one object rather than a room -- gets a shadow pool; a room, a floor or a
     * lawn does not, because there is nothing under it to shade.
     */
    static void collectShadow(List<Box> shaded, Box cell) {
        if (cell.half.y < TAG_MIN_HEIGHT_M || cell.half.y > TAG_FIGURE_M) {
            return;
        }
        double footprint = Math.hypot(cell.half.x, cell.half.z) * 2.0;
        if (footprint > SHADOW_MAX_FOOTPRINT_M * 2.0) {
            return;
        }
        shaded.add(cell);
    }

    /**
     * Lay the shadow pools. Each is one flat textured quad, lifted a few
     * centimetres off the floor it sits on so it does not fight that floor for the
     * depth buffer, and stretched to the figure's own footprint times
     * {@link #SHADOW_SPREAD} -- a pool exactly the size of the feet in it reads as
     * a decal rather than a shadow.
     */
    private static void addShadows(BranchGroup branch, List<Box> shaded,
            Map<String, byte[]> art, J3dMeshFactory factory) {
        if (shaded.isEmpty()) {
            return;
        }
        byte[] texture = ShadowArt.ellipse();
        if (texture == null) {
            return; // no pool at all, rather than a black square under everything
        }
        art.put(SHADOW_PATH, texture);
        for (Box cell : shaded) {
            double width = clamp(cell.half.x * 2.0 * SHADOW_SPREAD, 0.2, 3.0);
            double depth = clamp(cell.half.z * 2.0 * SHADOW_SPREAD, 0.2, 3.0);
            Shape3D pool = factory.createShape(flatQuad(width, depth),
                    new MaterialAppearance(0f, 0f, 0f, 1f, SHADOW_PATH));
            if (pool == null) {
                continue;
            }
            Transform3D seat = new Transform3D();
            seat.setTranslation(new Vector3d(cell.center.x,
                    cell.center.y - cell.half.y + SHADOW_LIFT_M, cell.center.z));
            TransformGroup placed = new TransformGroup(seat);
            placed.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
            placed.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
            placed.addChild(pool);
            branch.addChild(placed);
        }
    }

    /** The one shadow image every pool shares. */
    private static final String SHADOW_PATH = "lg3d:shadow";

    private static double clamp(double v, double low, double high) {
        return Math.max(low, Math.min(high, v));
    }

    /**
     * Paint the tags. Each is one textured quad under a seat (translation) and a
     * spinner (the billboard), so the raster always meets a face-on card.
     *
     * <p>The text is rasterised by {@link LabelArt} and served back to the mesh
     * factory through the same {@link TextureSource} seam a module jar's textures
     * arrive by -- there is no second texture path in the renderer, which is the
     * only reason there is no second set of alpha rules to get wrong.</p>
     *
     * @return the tags that were built, in the order they hang; empty when the
     *         world had nothing to name, because a text that would not rasterise
     *         is skipped rather than fatal, like any other missing art
     */
    private static List<NameTag> addTags(BranchGroup branch, List<Tag> tags,
            Map<String, byte[]> art, J3dMeshFactory factory) {
        if (tags.isEmpty()) {
            return List.of();
        }
        List<NameTag> built = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            Tag tag = tags.get(i);
            LabelArt.Image image = LabelArt.of(tag.text());
            if (image == null) {
                continue;
            }
            int lines = tag.text().split("\n").length;
            double height = tag.textHeight() * Math.max(1, lines);
            double width = LabelArt.widthForMeters(image, height);
            String path = "lg3d:tag" + i;
            art.put(path, image.png());
            Shape3D card = factory.createShape(tagQuad(width, height),
                    new MaterialAppearance(1f, 1f, 1f, 1f, path));
            if (card == null) {
                continue;
            }
            Transform3D seat = new Transform3D();
            seat.setTranslation(new Vector3d(tag.at().x, tag.at().y, tag.at().z));
            TransformGroup placed = new TransformGroup(seat);
            placed.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
            placed.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
            TransformGroup spin = new TransformGroup();
            spin.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
            spin.addChild(card);
            placed.addChild(spin);
            branch.addChild(placed);
            built.add(new NameTag(spin, tag.at()));
        }
        return built;
    }

    /**
     * A tag's geometry: one flat card in the {@code xy} plane at the local origin,
     * its +Z normal toward the walker, UVs covering the whole image. Wound the way
     * the sky cards are (counter-clockwise seen from the side the eye stands on),
     * because this fork culls back faces and a card wound the other way is a card
     * that is not there.
     *
     * <p>The {@code v} runs <em>upwards</em> in the card's own space: 0 at its
     * bottom edge, 1 at its top. That is the direction the upload runs the image:
     * {@code J3dMeshFactory.toComponent} hands the texture to Java 3D with its
     * origin at the upper left ({@code yUp = false}), so the picture's first row is
     * sampled at {@code v = 1} -- the same convention the shipped COLLADA exports
     * are authored against, which is why a .dae wall sign reads upright on the same
     * upload -- and a card that wants to read upright has to put that first row at
     * its <em>top</em>, which is {@code v = 1}. Mapping the picture's first row to
     * {@code v = 0} instead is a vertical mirror: measured off a rendered clinic,
     * every name over every resident's head -- {@code Waiting Patient} and the
     * lobby's {@code Directory} among them -- came back upside down while the
     * walls behind them read correctly, which is what pins the difference to this
     * one array rather than to the upload. {@link SkyArt#rowOf} is the other half
     * of this one convention.</p>
     */
    static ColladaMeshData tagQuad(double width, double height) {
        float hx = (float) (width / 2.0);
        float hy = (float) (height / 2.0);
        float[] coordinates = {
            -hx, -hy, 0f, hx, -hy, 0f, hx, hy, 0f,
            -hx, -hy, 0f, hx, hy, 0f, -hx, hy, 0f,
        };
        float[] normals = {
            0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f,
            0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f,
        };
        float[] texCoords = {
            0f, 0f, 1f, 0f, 1f, 1f,
            0f, 0f, 1f, 1f, 0f, 1f,
        };
        int[] triangles = {0, 1, 2, 3, 4, 5};
        return new ColladaMeshData(coordinates, normals, texCoords, triangles, null);
    }

    /**
     * A ground shadow's geometry: one flat card in the {@code xz} plane at the
     * local origin, its +Y normal to the sky, UVs covering the whole image. Wound
     * counter-clockwise seen from above, because the eye that reads a pool is above
     * it -- and this fork culls back faces, so a card wound the other way is a card
     * that is not there.
     *
     * <p>The {@code v} runs the same way {@link #tagQuad}'s does -- the image's
     * first row at {@code v = 1}, which is the -Z end of a face -- so one
     * convention governs every card this file builds. The pool is radially
     * symmetric, so nothing visible turns on it; stating it is what keeps the next
     * card written here from being mirrored.</p>
     */
    static ColladaMeshData flatQuad(double width, double depth) {
        float hx = (float) (width / 2.0);
        float hz = (float) (depth / 2.0);
        float[] coordinates = {
            -hx, 0f, -hz, -hx, 0f, hz, hx, 0f, hz,
            -hx, 0f, -hz, hx, 0f, hz, hx, 0f, -hz,
        };
        float[] normals = {
            0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
            0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
        };
        float[] texCoords = {
            0f, 1f, 0f, 0f, 1f, 0f,
            0f, 1f, 1f, 0f, 1f, 1f,
        };
        int[] triangles = {0, 1, 2, 3, 4, 5};
        return new ColladaMeshData(coordinates, normals, texCoords, triangles, null);
    }

    /**
     * One cell's placed volume: its centre and its half-extents along each axis.
     *
     * <p>The half-extents are kept rather than only the bounding sphere, because
     * the sphere's radius is the distance to a corner and so cannot tell a 10 m
     * pavilion from a 10 m-thick slab of the same reach — the difference the
     * camera framing turns on.</p>
     */
    record Box(Point3d center, Vector3d half) {

        /** The sphere from the centre to a corner. */
        BoundingSphere sphere() {
            return new BoundingSphere(center,
                    Math.sqrt(half.x * half.x + half.y * half.y
                            + half.z * half.z));
        }

        /** The smallest box holding this one and {@code other}. */
        Box union(Box other) {
            double minX = Math.min(center.x - half.x, other.center.x - other.half.x);
            double minY = Math.min(center.y - half.y, other.center.y - other.half.y);
            double minZ = Math.min(center.z - half.z, other.center.z - other.half.z);
            double maxX = Math.max(center.x + half.x, other.center.x + other.half.x);
            double maxY = Math.max(center.y + half.y, other.center.y + other.half.y);
            double maxZ = Math.max(center.z + half.z, other.center.z + other.half.z);
            return new Box(new Point3d((minX + maxX) / 2.0, (minY + maxY) / 2.0,
                    (minZ + maxZ) / 2.0),
                    new Vector3d((maxX - minX) / 2.0, (maxY - minY) / 2.0,
                            (maxZ - minZ) / 2.0));
        }
    }

    /**
     * Whether a placed cell is art that stands up rather than ground to stand on.
     *
     * <p>Both limits are needed. The ratio says a 3 m relief on a 90 m tile is
     * terrain; the spread says a 530 m field is terrain whatever it carries on it.</p>
     */
    static boolean standsUp(Box cell) {
        double spread = Math.max(cell.half.x, cell.half.z);
        return cell.half.y >= MIN_STANDING_M
                && cell.half.y >= spread * MIN_STANDING_RATIO
                && spread <= MAX_STANDING_SPREAD_M;
    }

    /**
     * The normalisation a {@code .dep} records for the model it points at:
     * scale, then rotation about the recorded axis, then translation -- the
     * composition the 2006 client applied and the port must apply too, or a
     * world's art arrives at its importer's raw units. An absent or degenerate
     * element is identity, never a guess: a model that ships no
     * {@code modelBGScale} is drawn as authored.
     */
    static Transform3D backgroundTransform(ModelLoadResult result) {
        Transform3D out = new Transform3D();
        org.jdesktop.wonderland.modules.artimport.DeployedModel dep =
                (result == null) ? null : result.model();
        if (dep == null) {
            return out;
        }
        Transform3D scaled = new Transform3D();
        Vector3f scale = dep.backgroundScale();
        if (scale != null && isSane(scale)) {
            scaled.setScale(new org.jogamp.vecmath.Vector3d(scale.x, scale.y, scale.z));
        }
        Transform3D rotated = new Transform3D();
        Vector3f axis = dep.backgroundRotationAxis();
        float angle = dep.backgroundRotationAngle();
        if (axis != null && angle != 0f && isSane(axis)) {
            double length = Math.sqrt(axis.x * axis.x + axis.y * axis.y
                    + axis.z * axis.z);
            if (length > 1e-6) { // a zero axis is no axis: leave the model turned as stored
                rotated.setRotation(new org.jogamp.vecmath.AxisAngle4f(
                        (float) (axis.x / length), (float) (axis.y / length),
                        (float) (axis.z / length), angle));
            }
        }
        Transform3D moved = new Transform3D();
        Vector3f shift = dep.backgroundTranslation();
        if (shift != null && isSane(shift)) {
            moved.setTranslation(shift);
        }
        // T·R·S: the model is sized, then turned, then seated. Composed through
        // temporaries because Transform3D.mul(a, b) reads both operands while it
        // writes the receiver, so passing the receiver as either operand would
        // corrupt the matrix half-way through the product.
        Transform3D turnedAndSized = new Transform3D();
        turnedAndSized.mul(rotated, scaled);
        out.mul(moved, turnedAndSized);
        return out;
    }

    /** A vector of finite numbers; a NaN in the art must not poison the graph. */
    private static boolean isSane(Vector3f v) {
        return !(Float.isNaN(v.x) || Float.isNaN(v.y) || Float.isNaN(v.z)
                || Float.isInfinite(v.x) || Float.isInfinite(v.y)
                || Float.isInfinite(v.z));
    }

    /**
     * Whether Java 3D will accept this cell transform at all. The test itself is
     * {@link WorldTransformMath#isAffine(double[])} -- the same one a COLLADA node
     * is held to -- so there is one threshold in the renderer, not two. Run before
     * the {@code TransformGroup} is built, so a degenerate cell costs that cell and
     * not the world.
     */
    static boolean isAffine(Transform3D transform) {
        org.jogamp.vecmath.Matrix4d m = new org.jogamp.vecmath.Matrix4d();
        transform.get(m);
        return WorldTransformMath.isAffine(WorldTransformMath.toRowMajor(m));
    }

    /**
     * The sky: a box of cards seated on the world's centre.
     *
     * <p>Three textbook answers were measured here and all three fail on this
     * stack. A {@code Background} paints nothing, whether it rides the content
     * branch or arrives as a branch graph of its own (build-gradle/scratch/
     * WlProbe7 and WlProbe16; this fork's {@code Background} carries no
     * {@code setShape} either). A {@code Sphere} primitive is a {@code Group} whose
     * {@code Shape3D} children are built at attach time and never see the
     * {@code Appearance} handed to its constructor, so a world of one reads back as
     * flat {@code #000000}. And a shell of explicit triangles that <em>surrounds the
     * eye</em> does reach the raster -- it emitted exactly the sky colour, at a
     * lattice of single pixels -- but never fills, because its triangles straddle
     * the camera (WlProbe16).</p>
     *
     * <p>What does paint is what the shipped art is made of: flat quads. One
     * 200x200 m card 150 m off the home eye filled that probe's whole frame
     * (build-gradle/scratch/WlProbe17), so the sky is six of those, wound the way
     * that card was -- counter-clockwise seen from inside the box, which is the side
     * the eye stands on, since Java 3D culls back faces and a sky wound the other
     * way draws nothing.</p>
     *
     * <p>The six cards carry {@link SkyArt}'s vertical ramp rather than one flat
     * blue, because a flat blue is the reason a rendered world read as a box of
     * bricks on a board: the gradient, not the hue, is what says the space above a
     * place is open.</p>
     */
    private static void addSky(BranchGroup branch, BoundingSphere extent,
            Map<String, byte[]> art, J3dMeshFactory factory) {
        Point3d center = centerOf(extent);
        float half = (float) extent.getRadius() * SKY_FACTOR;
        if (!Float.isFinite(half) || half <= 0f) {
            half = FRAMING_MARGIN * SKY_FACTOR;
        }
        Transform3D seat = new Transform3D();
        seat.setTranslation(new Vector3d(center.x, center.y, center.z));
        TransformGroup place = new TransformGroup(seat);
        // No ramp means no texture: the box keeps its single flat colour, which is
        // the same warning-not-failure every other missing artefact gets.
        byte[] ramp = SkyArt.ramp();
        String path = null;
        if (ramp != null) {
            path = SKY_PATH;
            art.put(SKY_PATH, ramp);
        }
        Shape3D shell = skyBox(half, factory, path);
        if (shell == null) {
            return; // no shell is honest; a half-built sky box is not
        }
        place.addChild(shell);
        branch.addChild(place);
    }

    /** The one sky image every world's box shares. */
    private static final String SKY_PATH = "lg3d:sky";

    /**
     * The sky's flat colour: the fallback a box falls back to when the ramp could
     * not be rasterised. A mid-latitude daytime blue, pre-lit.
     */
    static final Color3f SKY_COLOUR = new Color3f(0.45f, 0.62f, 0.78f);

    /** The six outward directions of a box; read only, never mutated. */
    private static final Vector3d[] SKY_AXES = {
        new Vector3d(1, 0, 0), new Vector3d(-1, 0, 0),
        new Vector3d(0, 1, 0), new Vector3d(0, -1, 0),
        new Vector3d(0, 0, 1), new Vector3d(0, 0, -1),
    };

    /**
     * One face's six vertices as (right, up) signs seen from inside the box:
     * bottom-left, bottom-right, top-right, then the second triangle.
     */
    private static final int[][] SKY_CORNERS = {
        {-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1},
    };

    /**
     * The box itself: six inward-facing cards of half-extent {@code half} in one
     * mesh, centred on the local origin and seated by {@link #addSky}.
     *
     * <p>Built through {@code J3dMeshFactory.createShape} like every model cell,
     * which is the geometry the renderer demonstrably fills, and given no
     * {@code PolygonAttributes} at all: this fork's {@code PolygonAttributes(int,
     * int, float)} is not read the way the textbook spells its parameters -- asking
     * for {@code CULL_NONE} and {@code POLYGON_FILL} painted the shell as single
     * pixels, which is point output -- so the box rides on the defaults the models
     * use and depends on its winding instead.</p>
     *
     * <p>The face's tangent is the axis the face is <em>not</em> pointing along.
     * Choosing it by the outward direction's y instead produced exactly (1,0,0) for
     * the +X and -X faces, so their up vector was the cross of a vector with itself
     * -- zero -- and those two faces collapsed to a line on the x axis: a sky with
     * nothing where east and west are, which is a world with a hole in it on the side
     * the eye happened to turn to.</p>
     *
     * @param half distance from the box centre to the middle of a face, in metres
     * @param factory the mesh factory the cards are built with
     * @param texturePath the art map's key holding the sky ramp, or null for the
     *                    flat {@link #SKY_COLOUR}
     * @return the shell, or null if the factory had nothing to build
     */
    static Shape3D skyBox(float half, J3dMeshFactory factory, String texturePath) {
        boolean graded = texturePath != null;
        int vertexCount = SKY_AXES.length * SKY_CORNERS.length;
        float[] coordinates = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] texCoords = graded ? new float[vertexCount * 2] : new float[0];
        int[] triangles = new int[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            triangles[i] = i;
        }
        int at = 0;
        for (Vector3d outward : SKY_AXES) {
            // A tangent that is never parallel to the face's own normal, then the
            // face's "up" chosen so the winding comes out counter-clockwise seen
            // from inside -- the combination WlProbe17 measured to fill.
            Vector3d right = (Math.abs(outward.x) > 0.5)
                    ? new Vector3d(0, 0, 1) : new Vector3d(1, 0, 0);
            Vector3d up = new Vector3d();
            up.cross(right, outward);
            for (int[] corner : SKY_CORNERS) {
                float y = (float) ((outward.y + corner[0] * right.y
                        + corner[1] * up.y) * half);
                coordinates[at * 3] = (float) ((outward.x + corner[0] * right.x
                        + corner[1] * up.x) * half);
                coordinates[at * 3 + 1] = y;
                coordinates[at * 3 + 2] = (float) ((outward.z + corner[0] * right.z
                        + corner[1] * up.z) * half);
                // The normal faces the eye inside the box; the art is pre-lit and
                // this Appearance carries no Material, so it colours nothing --
                // it is here because every mesh the factory paints carries one.
                normals[at * 3] = (float) -outward.x;
                normals[at * 3 + 1] = (float) -outward.y;
                normals[at * 3 + 2] = (float) -outward.z;
                if (graded) {
                    // The ramp is one column, so u is the same everywhere; the whole
                    // sky comes from the corner's height, which is what makes the four
                    // side faces read as one continuous gradient and the ceiling and
                    // floor land on its two ends.
                    texCoords[at * 2] = 0.5f;
                    texCoords[at * 2 + 1] = SkyArt.rowOf(y, half);
                }
                at++;
            }
        }
        Color3f colour = graded ? new Color3f(1f, 1f, 1f) : SKY_COLOUR;
        return factory.createShape(
                new ColladaMeshData(coordinates, normals, texCoords, triangles,
                        null),
                new MaterialAppearance(colour.x, colour.y, colour.z, 1f,
                        texturePath));
    }

    /**
     * One cell's real reach: the eight corners of the bounding volume the
     * {@code .dep} recorded, pushed through the cell's placement.
     *
     * <p>Two measurements fixed the arithmetic here, both off the shipped pavilion
     * world (build-gradle/scratch/WlProbe11). First, the {@code .dep}'s bounds are
     * recorded in {@code modelBounds} as <em>half</em>-extents in metres: the grass
     * circle's geometry spans ±4724 of the importer's units and its
     * {@code modelBGScale} is 0.0254 (inches to metres), which is ±120 m — exactly
     * the {@code dimension} it names, while the authored cell footprint is 240 m.
     * So a corner is center ± dimension, not center ± dimension/2.</p>
     *
     * <p>Second, those metres are the <em>normalised</em> frame — the background
     * scale has already been folded into them — so the bounds travel through the
     * cell's pose only. Pushing them through the whole matrix as well applies the
     * 0.0254 twice and frames a 60 m pavilion as a 1.5 m pebble, which puts the eye
     * inside a wall: the world is drawn and lit and reads black because the camera
     * is inside the art.</p>
     *
     * <p>A model that names no bounds is measured off its own geometry, and a
     * model that names neither bounds nor readable geometry falls back to its
     * footprint -- which is the authored extent and the best answer there is.
     * The measurement walks the raw positions and no node transforms, so a
     * bound-less model whose scene node carries its own transform is measured in
     * its local frame; that is the case for the bundled avatar bodies, which are
     * authored one node deep and placed entirely by their cell.</p>
     */
    private static Box extentOf(Entry entry, Transform3D placement) {
        org.jdesktop.wonderland.modules.artimport.DeployedModel dep =
                (entry.model() == null) ? null : entry.model().model();
        org.jdesktop.wonderland.modules.artimport.DeployedModel.Bounds bounds =
                (dep == null) ? null : dep.bounds();
        Vector3f center = (bounds == null) ? null : bounds.center();
        Vector3f size = (bounds == null) ? null : bounds.dimension();
        if (center == null || size == null
                || !(isSane(center) && isSane(size))) {
            Box measured = measuredOf(entry, placement);
            if (measured != null) {
                return measured;
            }
            Point3d at = new Point3d(entry.x(), entry.y(), entry.z());
            placement.transform(at);
            return new Box(at, new Vector3d(entry.footprint() / 2.0,
                    entry.footprint() / 2.0, entry.footprint() / 2.0));
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Point3d point = new Point3d(
                    center.x + (((corner & 1) == 0) ? -1.0 : 1.0) * size.x,
                    center.y + (((corner & 2) == 0) ? -1.0 : 1.0) * size.y,
                    center.z + (((corner & 4) == 0) ? -1.0 : 1.0) * size.z);
            placement.transform(point);
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }
        Point3d middle = new Point3d((minX + maxX) / 2.0,
                (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        return new Box(middle, new Vector3d(maxX - middle.x, maxY - middle.y,
                maxZ - middle.z));
    }

    /**
     * The placed box of a model's own geometry, or null when nothing readable was
     * found. A bound-less {@code .dep} is not a bound-less mesh: the bundled
     * avatar bodies ship raw COLLADA with no deployed-model at all, and a 1.75 m
     * resident framed as the 35 cm cube its cell footprint names is a person the
     * camera walks through and the framing does not count.
     */
    private static Box measuredOf(Entry entry, Transform3D placement) {
        org.jdesktop.wonderland.modules.collada.ColladaDocument document =
                (entry.model() == null) ? null : entry.model().document();
        if (document == null) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (String id : document.geometryIds()) {
            org.jdesktop.wonderland.modules.collada.ColladaGeometry geometry =
                    document.geometry(id);
            org.jdesktop.wonderland.modules.collada.ColladaGeometry.Mesh mesh =
                    (geometry == null) ? null : geometry.mesh();
            org.jdesktop.wonderland.modules.collada.ColladaGeometry.Vertices verts =
                    (mesh == null) ? null : mesh.vertices();
            org.jdesktop.wonderland.modules.collada.ColladaGeometry.Input position =
                    null;
            if (verts != null) {
                for (org.jdesktop.wonderland.modules.collada.ColladaGeometry.Input in
                        : verts.inputs()) {
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
            org.jdesktop.wonderland.modules.collada.ColladaGeometry.Source source =
                    (ref == null) ? null
                            : mesh.sourceById(ref.startsWith("#")
                                    ? ref.substring(1) : ref);
            if (source == null) {
                continue;
            }
            double[] data = source.data();
            int stride = (source.stride() >= 3) ? source.stride() : 3;
            for (int at = 0; at + 2 < data.length; at += stride) {
                Point3d point = new Point3d(data[at], data[at + 1], data[at + 2]);
                placement.transform(point);
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                minZ = Math.min(minZ, point.z);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
                maxZ = Math.max(maxZ, point.z);
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        Point3d middle = new Point3d((minX + maxX) / 2.0,
                (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        return new Box(middle, new Vector3d(maxX - middle.x, maxY - middle.y,
                maxZ - middle.z));
    }

    /** The sphere holding both spheres; null is the identity of the union. */
    private static BoundingSphere union(BoundingSphere a, BoundingSphere b) {
        if (a == null) {
            return b;
        }
        a.combine(b);
        return a;
    }

    /** {@code BoundingSphere}'s centre is only readable through an out-param. */
    private static Point3d centerOf(BoundingSphere sphere) {
        Point3d at = new Point3d();
        sphere.getCenter(at);
        return at;
    }

    /** The branch to hand a universe. */
    public BranchGroup root() {
        return root;
    }

    /** How many cells contributed geometry. */
    public int cellCount() {
        return placedCount;
    }

    /** How many entries had no loadable model; the honest half of {@link #cellCount()}. */
    public int skippedCount() {
        return skippedCount;
    }

    /**
     * How many picture planes were hung: the posters, screens, boards and windows
     * the world pinned up. {@link #cellCount()} counts models only, so a world
     * whose entire standing content is one poster would report zero here and be
     * called empty by a caller that had plenty to show.
     */
    public int pictureCount() {
        return pictureCount;
    }

    /**
     * How many name tags were built. A world that renders with zero here has art
     * and no one in it, which is the difference the probe reports.
     */
    public int tagCount() {
        return nameTags.size();
    }

    /** How many ground shadow pools were laid: the cells the world stands on. */
    public int shadowCount() {
        return shadowCount;
    }

    /**
     * Turn every name tag to face the eye. Called by the viewer wherever it writes
     * the camera, which is the only way the camera moves (see {@link NameTag}).
     */
    public void aimTags(Point3d eye) {
        for (NameTag tag : nameTags) {
            tag.aim(eye);
        }
    }

    /** The middle of the placed cells; where the camera looks. */
    public Point3d center() {
        return center;
    }

    /**
     * How far the framed subject reaches from {@link #center()} on each axis.
     * This is the camera's measure, so it is the standing art rather than the
     * whole world (see {@link #reach()}); the sphere {@link #radius()} reports
     * is the corner distance of this same box.
     */
    public Vector3d halfExtent() {
        return new Vector3d(halfExtent);
    }

    /**
     * How far outside {@link #center()} the framed subject reaches; how far back
     * to start. This is the camera's measure, so it is the standing art rather
     * than the whole world (see {@link #reach()}).
     */
    public float radius() {
        return radius;
    }

    /**
     * How far the world reaches in total, ground and sky included; what the sky
     * dome and the view's back clip are sized from. Always at least
     * {@link #radius()}.
     */
    public float reach() {
        return reach;
    }

    /**
     * Where to stand a visitor entering this world, or {@code null} when the world
     * has nobody standing in it. The y is the residents' floor; the eye height is
     * the viewer's to add.
     */
    public Point3d entryPoint() {
        return (entryPoint == null) ? null : new Point3d(entryPoint);
    }
}
