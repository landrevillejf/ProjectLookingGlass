/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland M8: turn a listed world into something on the screen.
 *
 * This is the seam between the browser's read-only inventory and the renderers.
 * The server already describes every cell with its placement and its
 * {@code wla://} model reference; {@link HttpWorldStore} fetches what a
 * reference names, {@code ModelLoader} (wonderland-modules) decodes it, and
 * {@link WorldScene} hangs the result in one lit branch. From there this class
 * does the two things the desktop needs: {@link #open} shows the world in a
 * navigable window, and {@link #preview} reads one frame of that same scene back
 * as the picture beside the cell list.
 *
 * <p><strong>Why the preview is a render.</strong> A floor plan is a diagram: it
 * answers "where are the rooms", not "what is this place". The only honest
 * preview of a 3D world is a view of it, so the vignette asks this class for one
 * and falls back to the plan when there is nothing to draw -- a world whose
 * models did not load has no picture, and saying so with a plan beats inventing
 * one with a screenshot of an empty sky.</p>
 *
 * <p><strong>What is safe to call headlessly.</strong> {@link #modelCells} and
 * {@link #assetModuleOf} are pure decisions over the parsed document, tested in
 * CI. Everything below them constructs Java 3D nodes or opens a window, so it is
 * probe-only, like the rest of the render path -- and like the desktop's own
 * screencapture, which is how the result is verified rather than asserted.</p>
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.modules.asset.ModelLoader;
import org.jdesktop.wonderland.modules.asset.WlaRef;
import org.jdesktop.wonderland.renderer.avatar.AvatarLoadResolver;
import org.jdesktop.wonderland.renderer.avatar.AvatarLoadResult;
import org.jdesktop.wonderland.renderer.mesh.TextureSource;
import org.jdesktop.wonderland.renderer.view.WorldScene;
import org.jdesktop.wonderland.renderer.view.WorldViewer;

/** Renders a world the browser has listed, or a view of it. */
public final class WorldRender {

    /** The pixels of a vignette: big enough to read a room, small enough to be quick. */
    static final int PREVIEW_WIDTH_PX = 640;
    static final int PREVIEW_HEIGHT_PX = 400;

    /** How long to wait for the render thread to answer a preview, in milliseconds. */
    private static final long PREVIEW_TIMEOUT_MS = 12_000;

    /** The pause between attempts; a frame is drawn every vsync, not every poll. */
    private static final long PREVIEW_POLL_MS = 250;

    private WorldRender() {
    }

    // ------------------------------------------------------------------
    // Pure decisions (headless-tested)
    // ------------------------------------------------------------------

    /**
     * The cells a 3D view can draw: the ones that name a model. Order is kept,
     * so a world's geometry arrives in the order the server published it.
     */
    static List<WorldCatalog.Cell> modelCells(WorldCatalog.WorldView world) {
        List<WorldCatalog.Cell> out = new ArrayList<>();
        if (world == null) {
            return out;
        }
        for (WorldCatalog.Cell cell : world.cells()) {
            if (cell != null && cell.hasModel() && !cell.isAvatar()) {
                out.add(cell);
            }
        }
        return out;
    }

    /**
     * The cells that are one of the world's residents: a body at a place, with a
     * name over it. Separate from {@link #modelCells} because a resident names no
     * deployed model -- its body comes from the bundled avatar pool, which ships
     * bare COLLADA and is read from this process's own classpath, not fetched
     * from the server as art.
     */
    static List<WorldCatalog.Cell> avatarCells(WorldCatalog.WorldView world) {
        List<WorldCatalog.Cell> out = new ArrayList<>();
        if (world == null) {
            return out;
        }
        for (WorldCatalog.Cell cell : world.cells()) {
            if (cell != null && cell.isAvatar()) {
                out.add(cell);
            }
        }
        return out;
    }

    /**
     * The cells that are a picture rather than a model or a person: a poster
     * pinned to a wall, a document on a screen, a whiteboard, a remote
     * application's window. Judged by the type the world wrote the cell as and not
     * by whether it names a file, because a shipped world contains all four and a
     * browser that drew only the ones it could fetch lost nine of the combined
     * world's forty-one objects and four of the meeting complex's eleven -- which
     * is how a full world came to look like an empty field with two posts in it.
     */
    static List<WorldCatalog.Cell> guiCells(WorldCatalog.WorldView world) {
        List<WorldCatalog.Cell> out = new ArrayList<>();
        if (world == null) {
            return out;
        }
        for (WorldCatalog.Cell cell : world.cells()) {
            if (cell != null && cell.isGui()) {
                out.add(cell);
            }
        }
        return out;
    }

    /**
     * Whether a 3D view of this world is worth asking for at all: some cell names
     * a model or a picture. Package-visible because the panel asks before it
     * queues a render, and fetching ten kilobytes of JSON to discover there is
     * nothing to draw is a wasted round trip.
     */
    static boolean hasGeometry(WorldCatalog.WorldView world) {
        return !modelCells(world).isEmpty() || !guiCells(world).isEmpty();
    }

    /**
     * The module a reference actually lives in, read off its {@code wla://}
     * spelling -- which is not necessarily the module the world was listed under,
     * and is the only module whose asset endpoint can serve the file. Null when
     * the reference is not one this store can answer.
     *
     * <p>Both a model cell and a picture cell are asked of it: a poster's image
     * and a building's geometry are the same kind of fact -- art the world module
     * ships and the asset endpoint serves -- and living in the same module is the
     * common case, not the exception. A cell that is not there at all (a parent
     * the world does not carry) asks of nobody: {@code null}, not a guess, and
     * never a {@code NullPointerException} on the render path.</p>
     */
    static String assetModuleOf(WorldCatalog.Cell cell, String worldModule) {
        return (cell == null) ? null : moduleOf(cell.modelUrl, worldModule);
    }

    /** {@link #assetModuleOf} for the picture a GUI cell names instead of a model. */
    static String assetModuleOfPicture(WorldCatalog.Cell cell, String worldModule) {
        return (cell == null) ? null : moduleOf(cell.assetUrl, worldModule);
    }

    private static String moduleOf(String reference, String worldModule) {
        WlaRef ref = WlaRef.parse(reference);
        if (ref == null || ref.module() == null || ref.module().isEmpty()) {
            return worldModule;
        }
        return ref.module();
    }

    // ------------------------------------------------------------------
    // The render path (probe-only: Java 3D and a window)
    // ------------------------------------------------------------------

    /**
     * Load every model cell and describe it as a scene entry. Runs off the EDT
     * (one HTTP read per asset, cached); touches no Java 3D type, but belongs to
     * the render path because only it calls the result.
     */
    static List<WorldScene.Entry> entriesOf(String server, String worldModule,
            WorldCatalog.WorldView world, WorldCatalog.ByteFetcher fetcher) {
        HttpWorldStore store = new HttpWorldStore(server, fetcher);
        // P5.1: the world may ship one behaviour script; it is read once here,
        // through the same cached store, so an absent behaviour costs no extra
        // round trip and every cell's decision reuses the one source text.
        final String behaviour = WorldBehavior.scriptOf(store, worldModule);
        ModelLoader loader = new ModelLoader();
        List<WorldScene.Entry> out = new ArrayList<>();
        for (WorldCatalog.Cell cell : modelCells(world)) {
            String module = assetModuleOf(cell, worldModule);
            ModelLoadResult result = loader.load(store, cell.modelUrl);
            WorldCatalog.Cell.Pose pose = cell.pose;
            out.add(new WorldScene.Entry(cell.label(), result,
                    cell.x == null ? 0f : cell.x.floatValue(),
                    cell.y == null ? 0f : cell.y.floatValue(),
                    cell.z == null ? 0f : cell.z.floatValue(),
                    (float) pose.yawRad(),
                    (float) pose.scaleX(), (float) pose.scaleY(),
                    (float) pose.scaleZ(),
                    // The framing footprint: the wider of the two horizontal
                    // extents, because a camera backs off a room's long wall.
                    Math.max(metres(cell.width), metres(cell.depth)),
                    store.textures(module),
                    // The name the world gave the cell floats over it, as it did in
                    // the 2009 client: a room you cannot name is a room you cannot
                    // find. WorldScene decides where it goes and skips the cells that
                    // are ground rather than places. A shipped behaviour may re-word
                    // that name (P5.1); with no behaviour, or one that fails, the cell
                    // keeps the label it was authored with -- the unchanged default.
                    WorldBehavior.annotation(behaviour, cell, cell.label())));
        }
        for (WorldCatalog.Cell cell : avatarCells(world)) {
            WorldScene.Entry figure = figureOf(cell);
            if (figure != null) {
                out.add(figure);
            }
        }
        return out;
    }

    /**
     * Load every GUI cell's picture and describe it as a plane. Runs off the EDT
     * like {@link #entriesOf}; one HTTP read per file, through the same cached
     * store the models use, so a world that shows the same poster twice fetches it
     * once.
     */
    static List<WorldScene.Picture> picturesOf(String server, String worldModule,
            WorldCatalog.WorldView world, WorldCatalog.ByteFetcher fetcher) {
        HttpWorldStore store = new HttpWorldStore(server, fetcher);
        List<WorldScene.Picture> out = new ArrayList<>();
        for (WorldCatalog.Cell cell : guiCells(world)) {
            WorldScene.Picture plane = planeOf(cell, worldModule, store);
            if (plane != null) {
                out.add(plane);
            }
        }
        return out;
    }

    /**
     * One GUI cell as the plane it names, or {@code null} when it has no area to
     * hang -- a card with no size is a rounding error, not an object.
     *
     * <p>Three kinds of picture, and the difference between them is who holds the
     * pixels. A poster's file is an image the store hands straight over, and its
     * size in the world is the image's own pixel count times the cell's
     * centimetres per pixel -- the shipped world poster is 1008x1152 px in a cell
     * scaled to 0.1, which is the metre-wide notice pinned three metres up its
     * wall. A document's file is a PDF, so one page is rasterised to fill the
     * rectangle the cell itself recorded: 640x480 px at 0.01 is the 6.4x4.8 m
     * screen standing in the meeting complex, and the raster only decides how
     * sharp that screen is, never how big it is. A whiteboard or a remote window
     * has no portable contents at all, and is drawn as the plain card it stands
     * in for -- named, so a viewer can read what the blank rectangle is.</p>
     */
    private static WorldScene.Picture planeOf(WorldCatalog.Cell cell,
            String worldModule, HttpWorldStore store) {
        Showing showing = show(cell, worldModule, store);
        WorldCatalog.Cell.Plane plane = (cell.plane == null)
                ? new WorldCatalog.Cell.Plane(DEFAULT_METRES_PER_PX,
                        DEFAULT_METRES_PER_PX, 0.0, 0.0)
                : cell.plane;
        double width = showing.widthPx() * plane.metresPerPxX() * cell.pose.scaleX();
        double height = showing.heightPx() * plane.metresPerPxY() * cell.pose.scaleY();
        if (!(width > 0.0) || !(height > 0.0)
                || !Double.isFinite(width) || !Double.isFinite(height)) {
            return null;
        }
        WorldCatalog.Cell.Pose pose = cell.pose;
        return new WorldScene.Picture(cell.label(), showing.encoded(),
                cell.x == null ? 0f : cell.x.floatValue(),
                cell.y == null ? 0f : cell.y.floatValue(),
                cell.z == null ? 0f : cell.z.floatValue(),
                (float) pose.yawRad(), (float) width, (float) height,
                // A poster says what it is; only a card with nothing on it needs
                // its name written beside it.
                (showing.encoded() == null) ? cell.label() : null);
    }

    /**
     * What one GUI cell's file turned out to be. The recorded pixel size wins when
     * the cell wrote one (a document and a whiteboard know their own extent), and
     * the fetched picture's measured size wins otherwise; with neither, the format
     * default is used and the card is left blank. A reference that resolves but
     * whose bytes do not arrive is still a plane at the right place -- a world
     * whose server hiccups on one poster shows a card for it rather than losing it
     * the way the loader used to lose a cell whose type it did not know.
     */
    private static Showing show(WorldCatalog.Cell cell, String worldModule,
            HttpWorldStore store) {
        WlaRef ref = WlaRef.parse(cell.assetUrl);
        if (ref == null) {
            return fallback(cell);
        }
        byte[] bytes = store.bytes(assetModuleOfPicture(cell, worldModule), ref.path());
        if (bytes == null || bytes.length == 0) {
            return fallback(cell);
        }
        String lower = ref.path().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            byte[] page = rasterizePdf(bytes);
            return (cell.plane != null && cell.plane.hasPixelSize())
                    ? new Showing(page, cell.plane.widthPx(), cell.plane.heightPx())
                    : new Showing(page, DEFAULT_PIXEL_WIDTH, DEFAULT_PIXEL_HEIGHT);
        }
        int[] size = pixelSize(bytes);
        if (size != null) {
            return new Showing(bytes, size[0], size[1]);
        }
        if (cell.plane != null && cell.plane.hasPixelSize()) {
            return new Showing(bytes, cell.plane.widthPx(), cell.plane.heightPx());
        }
        return new Showing(bytes, DEFAULT_PIXEL_WIDTH, DEFAULT_PIXEL_HEIGHT);
    }

    /** A GUI plane with nothing to show: its recorded size, or the format's default. */
    private static Showing fallback(WorldCatalog.Cell cell) {
        return (cell.plane != null && cell.plane.hasPixelSize())
                ? new Showing(null, cell.plane.widthPx(), cell.plane.heightPx())
                : new Showing(null, DEFAULT_PIXEL_WIDTH, DEFAULT_PIXEL_HEIGHT);
    }

    /**
     * Rasterise the first page of a world's document. {@link PdfBoxRasterizer}
     * opens a file, and the server hands out bytes, so the page is written to a
     * temporary file the JVM deletes on exit -- the document is the world's own
     * art, read once per preview, never per frame.
     */
    private static byte[] rasterizePdf(byte[] document) {
        try {
            java.io.File temp = java.io.File.createTempFile("lg3d-world-page", ".pdf");
            temp.deleteOnExit();
            java.nio.file.Files.write(temp.toPath(), document);
            PdfBoxRasterizer rasterizer = new PdfBoxRasterizer();
            try {
                // Zoom 2 on a 72-point page is a 128-pixel-per-centimetre screen:
                // legible from across the room it stands in, and no larger than the
                // texture the renderer will hold.
                return rasterizer.renderPage(temp.getAbsolutePath(), 1, 2.0f);
            } finally {
                rasterizer.release();
            }
        } catch (IOException | RuntimeException | Error unreadable) {
            return null; // a page that will not rasterise is a blank screen
        }
    }

    /**
     * The picture's width and height in pixels, read off its header, or null when
     * the bytes are not an image at all. Only the header is decoded: a poster's
     * dimensions are a fact about the file, and inflating 1008x1152 pixels to learn
     * them is work the renderer does afterwards for its own texture.
     */
    private static int[] pixelSize(byte[] encoded) {
        try (ImageInputStream stream =
                ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            if (stream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            return null; // an undecodable picture is a card, not a crash
        }
    }

    /** One GUI cell's file: the encoded picture (null for a blank card) and its size. */
    private record Showing(byte[] encoded, double widthPx, double heightPx) {
    }

    /** The picture width a GUI cell that records none is drawn at: the shipped default. */
    private static final double DEFAULT_PIXEL_WIDTH = 640.0;
    private static final double DEFAULT_PIXEL_HEIGHT = 480.0;

    /** The centimetres per pixel the format means when a cell writes no scale. */
    private static final double DEFAULT_METRES_PER_PX =
            WorldCatalog.Cell.Plane.DEFAULT_METRES_PER_PX;

    /**
     * One resident as a scene entry, or null when the pool cannot answer for the
     * body the world named. The body is authored in metres and carries no
     * {@code .dep}, so it is placed untransformed but for the cell's own
     * translation and heading: a resident stands where the world says and faces
     * the way it is pointed.
     */
    private static WorldScene.Entry figureOf(WorldCatalog.Cell cell) {
        AvatarLoadResult body = new AvatarLoadResolver().resolve(cell.avatarUrl);
        if (!body.isLoaded()) {
            return null;
        }
        WorldCatalog.Cell.Pose pose = cell.pose;
        return new WorldScene.Entry(cell.label(), ModelLoadResult.bare(body.document()),
                cell.x == null ? 0f : cell.x.floatValue(),
                cell.y == null ? 0f : cell.y.floatValue(),
                cell.z == null ? 0f : cell.z.floatValue(),
                (float) pose.yawRad(),
                1f, 1f, 1f,
                Math.max(metres(cell.width), metres(cell.depth)),
                TextureSource.NONE,
                // A resident without a name over their head is a prop. The
                // reference screenshot's most legible fact is its crowd of tags.
                cell.label());
    }

    /** A footprint the server did not describe is no extent, not a zero-width wall. */
    private static float metres(Double value) {
        return (value == null) ? 0f : value.floatValue();
    }

    /**
     * Show one world in its own window and hand back the viewer, so the caller
     * can close it. The read and the scene build happen on the calling thread;
     * only the window goes to the EDT.
     *
     * @param assets the transport the models and textures are fetched through
     * @param width  the canvas width in pixels
     * @param height the canvas height in pixels
     * @throws IllegalStateException when no cell of the world had loadable
     *         geometry -- an empty window is not a world, and the caller has a
     *         truer message to show
     */
    public static WorldViewer open(String server, String module,
            WorldCatalog.WorldView world, WorldCatalog.ByteFetcher assets,
            int width, int height) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            // Say which it is rather than letting AWT throw something about
            // graphics devices: the visitor's next question is "then how do I
            // see this world", and the answer is "run the desktop on a display".
            throw new IllegalStateException(
                    "this JVM has no display to open a world in");
        }
        WorldScene scene = WorldScene.build(
                entriesOf(server, module, world, assets),
                picturesOf(server, module, world, assets));
        return show(scene, titleOf(module), width, height, true);
    }

    /**
     * The outcome of one preview attempt: a frame, or the reason there is none.
     *
     * <p>The reason is not decoration. "no display", "this world's models did not
     * load" and "the render thread never answered" are three different things a
     * user needs to be told, and a face that shows a floor plan instead of a
     * world has to know which of the three it is showing -- as does whoever runs
     * the probe that proves the render path works.</p>
     */
    public record Attempt(BufferedImage frame, String failure) {

        /** Whether a frame came back. */
        public boolean isRendered() {
            return frame != null;
        }
    }

    /**
     * Build the scene and put it on screen. Shared by {@link #open} and
     * {@link #preview}; the window is created on the EDT and the universe is
     * attached there, because that is what a heavyweight canvas needs.
     *
     * @param walk whether the window opens standing in the world
     *            ({@link WorldViewer#walkInside()}) or looking at it from outside
     *            ({@link WorldViewer#frameHome()}). A vignette wants the whole
     *            world in frame; a world you entered wants to be in front of you.
     */
    private static WorldViewer show(WorldScene scene, String title,
            int width, int height, boolean walk) {
        WorldViewer viewer = new WorldViewer(title, scene, width, height);
        if (walk) {
            viewer.walkInside();
        }
        runOnEdt(viewer::show);
        if (isSceneEmpty(scene)) {
            viewer.close();
            throw new IllegalStateException(
                    "nothing of this world loaded, so there is nothing to show");
        }
        return viewer;
    }

    /**
     * Whether a built scene has any content at all. A world can be made of models,
     * of pictures, or of both -- the combined world is all three at once, and the
     * shipping demo's only standing objects in one of its six rooms are a poster
     * and a document screen. Judging emptiness by the model count alone called such
     * a world empty and closed the window on it.
     */
    private static boolean isSceneEmpty(WorldScene scene) {
        return scene.cellCount() + scene.pictureCount() == 0;
    }

    /**
     * One frame of the world as a picture: build the scene, show it briefly,
     * read the raster back, close. Returns null when there is nothing to draw or
     * the render thread never answered, which leaves the vignette on its floor
     * plan -- the honest fallback, and the reason a preview is never an error.
     *
     * <p>Call off the EDT. The window does appear for a moment; a Java 3D canvas
     * only renders while it is showing, and that is a property of the stack, not
     * a shortcut in this code.</p>
     */
    public static BufferedImage preview(String server, String module,
            WorldCatalog.WorldView world, WorldCatalog.ByteFetcher assets) {
        return tryPreview(server, module, world, assets).frame();
    }

    /**
     * The same frame, with the reason it is missing when it is. Package-visible:
     * the face only needs the picture, and only the probe and the tests name why
     * a world did not arrive.
     */
    static Attempt tryPreview(String server, String module,
            WorldCatalog.WorldView world, WorldCatalog.ByteFetcher assets) {
        if (!hasGeometry(world)) {
            return new Attempt(null,
                    "no cell of this world names a model or a picture to draw");
        }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return new Attempt(null, "this JVM has no display to render into");
        }
        WorldViewer viewer = null;
        try {
            WorldScene scene = WorldScene.build(
                    entriesOf(server, module, world, assets),
                    picturesOf(server, module, world, assets));
            if (isSceneEmpty(scene)) {
                return new Attempt(null, "none of the world's " + world.size()
                        + " cells loaded (" + scene.skippedCount() + " skipped)");
            }
            viewer = show(scene, titleOf(module) + " \u2014 preview",
                    PREVIEW_WIDTH_PX, PREVIEW_HEIGHT_PX,
                    // Stand inside a world that has people in it.
                    //
                    // The reference picture of this project is one visitor's eye-level
                    // view of the group standing in the clinic lobby &mdash; ceiling
                    // over them, the directory board to one side, a poster on the far
                    // wall. A vignette framed from outside that room shows a box, and a
                    // box of flat walls is precisely the "Minecraft" a person objects to:
                    // the clinic's walls are its <em>outside</em>, and an outdoor world
                    // has no such inside to be lost. So the world's own entry point
                    // decides &mdash; a world you would arrive in is a world shown from
                    // where you arrive, and an open one is shown whole.
                    scene.entryPoint() != null);
            long deadline = System.currentTimeMillis() + PREVIEW_TIMEOUT_MS;
            BufferedImage shot = null;
            BufferedImage blank = null;
            // Java 3D renders asynchronously, so the first raster read after a
            // window appears can legitimately come back empty -- the canvas is on
            // screen but no frame has been swapped into it yet. Polling for a frame
            // that has something in it is the whole difference between a preview and
            // a black rectangle, and the reason the deadline is spent rather than
            // the first answer taken.
            while (shot == null && System.currentTimeMillis() < deadline) {
                BufferedImage frame = viewer.capture(PREVIEW_POLL_MS * 4);
                if (frame != null) {
                    blank = frame;
                    if (hasColour(frame)) {
                        shot = frame;
                        break;
                    }
                }
                Thread.sleep(PREVIEW_POLL_MS);
            }
            if (shot != null) {
                return new Attempt(shot, null);
            }
            return (blank == null)
                    ? new Attempt(null, "the render thread answered no frame in "
                            + PREVIEW_TIMEOUT_MS + " ms")
                    : new Attempt(null, "the canvas stayed blank for "
                            + PREVIEW_TIMEOUT_MS + " ms \u2014 " + blank.getWidth()
                            + "x" + blank.getHeight() + " of one colour, so the world "
                            + "has geometry but nothing lit in view");
        } catch (InterruptedException waited) {
            Thread.currentThread().interrupt();
            return new Attempt(null, "the preview wait was interrupted");
        } catch (RuntimeException | Error notRenderable) {
            // A preview is a courtesy: whatever made it impossible (no GL, a world
            // that will not decode) leaves the plan in place -- but it says so.
            return new Attempt(null, String.valueOf(notRenderable));
        } finally {
            if (viewer != null) {
                viewer.close();
            }
        }
    }

    /** The world's name for a window title; the module is the last resort. */
    private static String titleOf(String module) {
        return (module == null || module.isBlank()) ? "Wonderland world" : module;
    }

    /**
     * Whether a frame holds a picture rather than a cleared buffer.
     *
     * <p>Two tests, because one was not enough. Counting colours catches the
     * blank canvas -- a cleared raster has exactly one value in it. But counting
     * colours alone calls a picture a blank, and the outpatient clinic was one:
     * its art is flat-shaded rooms on a flat floor, so a whole frame of it
     * sampled to six distinct values, and the preview threw away a render that
     * filled the window. So a frame also passes when no single colour is most of
     * it: a canvas that is 66 % carpet and 36 % wall is a world, and a canvas
     * that is 100 % sky is not.</p>
     *
     * <p>The colour count is sampled on a stride and capped, so this stays cheap
     * enough to run on every poll of every preview.</p>
     */
    static boolean hasColour(BufferedImage image) {
        return distinctColors(image) > 8 || !isDominatedByOneColour(image);
    }

    /**
     * Whether one colour covers at least {@link #DOMINANT_SHARE} of the sampled
     * pixels -- which is what a blank canvas, or a world clipped to nothing,
     * looks like however many values the anti-aliasing leaves around them.
     */
    private static boolean isDominatedByOneColour(BufferedImage image) {
        java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
        int samples = 0;
        int worst = 0;
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                int count = seen.merge(image.getRGB(x, y), 1, Integer::sum);
                if (count > worst) {
                    worst = count;
                }
                samples++;
            }
        }
        return samples == 0 || (double) worst / samples >= DOMINANT_SHARE;
    }

    /** How much of a frame one colour may hold before it stops being a picture. */
    private static final double DOMINANT_SHARE = 0.98;

    /** How many different pixels a frame holds, capped at 4097. */
    static int distinctColors(BufferedImage image) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                seen.add(image.getRGB(x, y));
                if (seen.size() > 4096) {
                    return seen.size();
                }
            }
        }
        return seen.size();
    }

    /** Run on the EDT and wait; rethrows nothing the caller cannot handle. */
    private static void runOnEdt(Runnable action) {
        if (java.awt.EventQueue.isDispatchThread()) {
            action.run();
            return;
        }
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            java.awt.EventQueue.invokeAndWait(() -> {
                try {
                    action.run();
                } catch (RuntimeException thrown) {
                    failure.set(thrown);
                }
            });
        } catch (java.lang.reflect.InvocationTargetException | InterruptedException thrown) {
            if (thrown instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("the EDT did not run the action", thrown);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
