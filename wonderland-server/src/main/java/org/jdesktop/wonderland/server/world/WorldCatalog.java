/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server (world library): the catalog of worlds the server can serve.
 *
 * "Access to every existing world" is a discovery problem before it is a loading
 * one: Open Wonderland ships a LIBRARY of demo worlds (almostempty, auditorium,
 * combined, gallery, japanesepavilion, meetingcomplex, town) as world module jars
 * inside the 400 MB installer, and this port adds its own clean-room outpatient
 * clinic. The seeder loads ONE world by name; the catalog is what answers "which
 * worlds are there, and what are they called" so a deployment (and the desktop's
 * Wonderland World browser) can pick among them instead of knowing one hard-coded
 * module.
 *
 * Two origins, one view:
 *   - BUNDLED worlds live on the classpath and are read through
 *     {@link ManifestClasspathWorldStore}; the outpatient clinic ships in the jar,
 *     so it is always available. The list is a constant because a classpath jar
 *     cannot be enumerated -- exactly the limitation ManifestClasspathWorldStore
 *     exists to work around for a single known module, not for discovery.
 *   - INSTALLED worlds are {@code *-world.jar} files in the configured
 *     {@code wonderland.world.dir}, read through {@link JarWorldStore}; dropping
 *     a stock world jar (extracted from the installer) into that directory makes
 *     it appear here with no rebuild.
 *
 * Every read is defensive: a jar that is not a world, a module.xml that will not
 * parse, an unreadable cell file -- none of them may throw or drop the whole
 * catalog, because a listing is a courtesy surface, not a boot dependency. A
 * degraded entry falls back to the directory/jar name as its title.
 */
package org.jdesktop.wonderland.server.world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.cell.state.AvatarCellServerState;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.ForeignCellServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
import org.jdesktop.wonderland.modules.asset.JarWorldStore;
import org.jdesktop.wonderland.modules.asset.ManifestClasspathWorldStore;
import org.jdesktop.wonderland.modules.asset.MultiWorldStore;
import org.jdesktop.wonderland.modules.asset.WorldContents;
import org.jdesktop.wonderland.modules.asset.WorldLoader;
import org.jdesktop.wonderland.modules.asset.WorldPlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Discovers the worlds the server can serve: bundled classpath worlds + installed jars. */
@Component
public class WorldCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(WorldCatalog.class);

    /** The suffix that makes a classpath cell file a world cell. */
    static final String CELL_SUFFIX = "-wlc.xml";

    /**
     * The worlds bundled on the classpath. The clinic ships in the server jar, so
     * it is always offered; a classpath jar cannot be enumerated, hence the fixed
     * list (see the header). Add a bundled world by adding its module name here and
     * shipping its resources under {@code src/main/resources/<module>/}.
     */
    static final List<String> BUNDLED = List.of(WorldSeeder.CLINIC_MODULE);

    private static final Pattern NAME = Pattern.compile("<name>\\s*(.*?)\\s*</name>", Pattern.DOTALL);
    private static final Pattern DESCRIPTION =
            Pattern.compile("<description>\\s*(.*?)\\s*</description>", Pattern.DOTALL);

    private final Path worldsDir;

    public WorldCatalog(@Value("${wonderland.world.dir:}") String worldsDir) {
        this.worldsDir = worldsDir == null || worldsDir.isBlank()
                ? null : Path.of(worldsDir.trim()).toAbsolutePath().normalize();
    }

    /** Every world the server can serve: bundled first, then installed jars by name. */
    public List<WorldSummary> available() {
        List<WorldSummary> out = new ArrayList<>();
        for (String module : BUNDLED) {
            out.add(bundledSummary(module));
        }
        for (Path jar : worldJars()) {
            out.add(jarSummary(jar));
        }
        out.sort(Comparator.comparing(WorldSummary::source)
                .thenComparing(WorldSummary::module));
        return out;
    }

    /**
     * Load one world module's decoded contents without seeding them into the
     * live {@link WorldModel}. This is what lets the REST surface answer
     * "show me the cells of world X" for any world in the catalog -- bundled or
     * installed -- rather than only the single one the server happened to seed
     * at boot. The store is chosen exactly as {@link WorldSeeder} would choose
     * it (an installed world jar wins, else a bundled classpath module), so the
     * listing cannot disagree with what seeding that same module would produce.
     *
     * <p>Defensive on the same terms as the rest of the catalog: an unknown
     * module, an unopenable jar or a world that will not decode yields empty,
     * never a throw -- a read-only browse must not be able to fault a request.
     */
    public Optional<WorldContents> loadContents(String module) {
        if (module == null || module.isBlank()) {
            return Optional.empty();
        }
        String name = module.trim();
        if (worldJar(name).isEmpty() && !BUNDLED.contains(name)) {
            return Optional.empty(); // unknown module: nothing to load
        }
        // Load across every installed world, not just this module's own jar: the
        // combined world places cells whose models live in the other six jars, and
        // a single-module store silently drops all of them (see MultiWorldStore).
        try (MultiWorldStore store = openAllStores()) {
            return Optional.of(decode(new WorldLoader(), store, name));
        } catch (IOException | RuntimeException ex) {
            LOG.warn("world '{}' could not be read across the installed worlds ({})",
                    name, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * Open every installed world jar plus the bundled classpath fallback as one
     * {@link MultiWorldStore}, so a world that references another module's assets
     * resolves them. The caller owns the returned store and must close it (which
     * closes each jar). A jar that cannot be opened aborts the whole build rather
     * than half-populating a store: a world missing one module's art is as wrong
     * as a world that failed to load, and both degrade to an empty listing.
     */
    MultiWorldStore openAllStores() throws IOException {
        MultiWorldStore store = new MultiWorldStore();
        for (Path jar : worldJars()) {
            store.add(stem(jar), jar);
        }
        store.fallback(new ManifestClasspathWorldStore());
        return store;
    }

    /** {@link WorldLoader#load} guarded: a world that will not decode is empty, not fatal. */
    private static WorldContents decode(WorldLoader loader,
            org.jdesktop.wonderland.modules.asset.WorldStore store, String module) {
        try {
            return loader.load(store, module);
        } catch (RuntimeException | Error ex) {
            LOG.warn("world '{}' failed to decode; listing it as empty ({})",
                    module, ex.toString());
            return new WorldContents(module, List.of());
        }
    }

    /**
     * Describe one world module's cells in the same flat, read-only vocabulary
     * {@code WorldRestController} uses for the seeded world (id, parent,
     * className, name, and a deployedModelURL for a model cell), so a browser
     * reading any catalog world sees the shape it already knows.
     *
     * <p>Classification mirrors {@link WorldSeeder#seedCell} exactly -- a cell
     * with a model component reads as the renderer's model cell, every other
     * named cell as the environment class -- and an unnamed cell is skipped, so
     * what this lists is what seeding the same module would publish.</p>
     *
     * <p>The placement is <em>absolute</em>, and that is the whole point of this
     * method existing rather than handing the state's own numbers over: a stock
     * world files its cells inside their parent's directory and measures each one
     * from that parent, so the twenty-odd objects of the meeting complex listed
     * at their local coordinates sat ninety metres from the container that holds
     * them, and a browser framed on what it could see looked at empty sky. See
     * {@link WorldPlacement}.</p>
     *
     * <p>An unknown or unreadable module yields an empty list, never a throw.</p>
     */
    public List<Map<String, Object>> describeCells(String module) {
        return loadContents(module).map(contents -> {
            List<WorldContents.LoadedCell> loaded = contents.cells();
            Map<String, WorldPlacement.Absolute> placed =
                    WorldPlacement.resolve(WorldPlacement.cellsOf(loaded));
            List<Map<String, Object>> out = new ArrayList<>();
            for (WorldContents.LoadedCell cell : loaded) {
                CellServerState state = cell.state();
                if (state == null || state.getName() == null || state.getName().isEmpty()) {
                    continue; // the seeder's rule: an unnamed cell is nothing to list
                }
                ModelCellComponentServerState model = (ModelCellComponentServerState)
                        state.getComponentServerState(ModelCellComponentServerState.class);
                boolean isModel = model != null;
                AvatarCellServerState avatar =
                        (state instanceof AvatarCellServerState a) ? a : null;
                ForeignCellServerState foreign =
                        (state instanceof ForeignCellServerState f) ? f : null;
                Map<String, Object> described = new LinkedHashMap<>();
                described.put("id", contents.module() + ":" + out.size());
                described.put("parent", null);
                described.put("className", avatar != null ? WorldSeeder.AVATAR_CELL_CLASS
                        : isModel ? WorldSeeder.MODEL_CELL_CLASS
                                : WorldModel.ENVIRONMENT_CLASS);
                described.put("name", state.getName());
                if (isModel) {
                    described.put("deployedModelURL", model.getDeployedModelURL());
                }
                if (foreign != null) {
                    // What the cell was written as and what it shows: the two facts
                    // a client needs to draw a poster as its picture instead of
                    // guessing from a name. See ForeignCellServerState.
                    described.put("cellType", foreign.getCellType());
                    if (foreign.getAssetURI() != null) {
                        described.put("assetURL", foreign.getAssetURI());
                    }
                    // The picture's own measure. A cell is a plane, not a placemark:
                    // "show this file here" needs a size as well as a place, and the
                    // only size a poster's file records is how wide one of its pixels
                    // is in metres. The picture's pixel count is the reader's half of
                    // the multiplication -- it has the bytes -- so what travels here
                    // is the scale, plus the preferred size for the two viewers that
                    // write one and have no picture to be measured by.
                    described.put("planeScale", List.of(foreign.getPixelScaleX(),
                            foreign.getPixelScaleY()));
                    if (foreign.getPixelWidth() > 0.0) {
                        described.put("planePixels", List.of(foreign.getPixelWidth(),
                                foreign.getPixelHeight()));
                    }
                }
                if (avatar != null) {
                    // The body the client should wear. Null is the pool default,
                    // and the client says so -- so the key is present only when
                    // the world actually chose a body for this resident.
                    String body = avatar.getAvatarModelUrl();
                    if (body != null && !body.isBlank()) {
                        described.put("avatarModelUrl", body);
                    }
                }
                putPlacement(described, state, placed.get(cell.path()));
                out.add(described);
            }
            return out;
        }).orElse(List.of());
    }

    /**
     * Add one cell's world placement to a descriptor: the absolute position as
     * {@code x}/{@code y}/{@code z}, its heading as {@code yaw} and its composed
     * stretch as {@code scale}, plus {@code width}/{@code depth} -- its authored
     * footprint on the floor plane, grown by the same stretch, because a browser
     * drawing a plan of a world it has only listed needs a size in metres and a
     * cell file records half-extents (the {@code Bounds(BoundingVolume)}
     * contract), so the footprint is twice the recorded extent times the scale.
     *
     * <p>A null placement -- a cell the resolver did not see, which happens only
     * for a cell with no path at all -- carries no keys: absent is how this
     * surface says "nowhere", and a zero is a place.</p>
     *
     * <p>This is what lets a browser draw a top-down plan of a catalog world it
     * has only listed <em>and</em> what lets it render the world for real: a
     * {@code .dep} stores its model in the importer's units, and the rotation and
     * scale here are the other half of the transform that puts it back in
     * metres. A client that reads only the translation sees the town spun the
     * wrong way and the clinic's floor a metre across, which is exactly the
     * "the world never appears" this surface exists to end.</p>
     */
    private static void putPlacement(Map<String, Object> described,
            CellServerState state, WorldPlacement.Absolute absolute) {
        if (absolute == null) {
            return;
        }
        Object component = (state == null) ? null
                : state.getComponentServerState(PositionComponentServerState.class);
        boolean authored = component instanceof PositionComponentServerState;
        // A cell that neither it nor any ancestor placed is nowhere, and nowhere
        // is absent keys -- not the (0,0,0) the resolver hands back as the
        // identity for a position-less cell, which a reader would take for a
        // real spot at the origin (see the class header). A child with no own
        // position but a placed parent has a non-identity absolute and stays.
        if (!authored && isUnplaced(absolute)) {
            return;
        }
        Vector3f translation = absolute.translation();
        described.put("x", translation.x);
        described.put("y", translation.y);
        described.put("z", translation.z);
        described.put("yaw", WorldPlacement.yawOf(absolute));
        Vector3f scale = absolute.scale();
        if (!isUnit(scale)) {
            described.put("scale", List.of((double) scale.x, (double) scale.y,
                    (double) scale.z));
        }
        putFootprint(described, state, scale);
    }

    /**
     * Whether a resolved placement is the identity -- no authored translation and
     * no stretch -- which is what the resolver returns for a cell nobody placed.
     */
    private static boolean isUnplaced(WorldPlacement.Absolute absolute) {
        Vector3f t = absolute.translation();
        return t.x == 0f && t.y == 0f && t.z == 0f && isUnit(absolute.scale());
    }

    /** Whether a composed stretch still leaves a cell its authored size. */
    private static boolean isUnit(Vector3f scale) {
        return scale.x == 1.0f && scale.y == 1.0f && scale.z == 1.0f;
    }

    /**
     * Add the cell's authored footprint, grown by the stretch its ancestors and
     * its own position component compose to. A bounds hint is measured in the
     * cell's own space, so a room filed inside a container scaled to a tenth is
     * a tenth as wide in the world -- reporting the authored number next to the
     * composed placement is the pair of facts that cannot both be true, and the
     * plan a browser draws is wrong in exactly the way a floor plan of a town
     * drawn at two scales is wrong.
     */
    private static void putFootprint(Map<String, Object> described,
            CellServerState state, Vector3f scale) {
        Object component = (state == null) ? null
                : state.getComponentServerState(PositionComponentServerState.class);
        if (!(component instanceof PositionComponentServerState position)) {
            return;
        }
        PositionComponentServerState.Bounds bounds = position.getBounds();
        if (bounds != null && bounds.x > 0 && bounds.z > 0) {
            described.put("width", 2.0 * bounds.x * scale.x);
            described.put("depth", 2.0 * bounds.z * scale.z);
        }
    }

    /**
     * The quaternion's heading about the vertical axis, in radians, or 0.0 when
     * the world recorded no rotation. Taken from the rotation matrix's first row
     * and last column, which is the standard Y-up extraction and, unlike reading
     * the quaternion's {@code y} component, stays right for a rotation that also
     * tilts. The quaternion is normalised first because jMonkeyEngine will build
     * one from any axis/angle pair the author typed.
     */
    static double yawOf(com.jme.math.Quaternion rotation) {
        if (rotation == null) {
            return 0.0;
        }
        double x = rotation.x;
        double y = rotation.y;
        double z = rotation.z;
        double w = rotation.w;
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (length < 1e-9) {
            return 0.0; // a zero quaternion is no rotation at all
        }
        x /= length;
        y /= length;
        z /= length;
        w /= length;
        return Math.atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + x * x));
    }

    /** The installed world jar for {@code module}, if the worlds directory carries one. */
    public Optional<Path> worldJar(String module) {
        if (module == null) {
            return Optional.empty();
        }
        return worldJars().stream()
                .filter(j -> module.equals(stem(j)))
                .findFirst();
    }

    /**
     * One world's preview image: the bytes and the media type its file name says.
     * A value here is a promise the browser can keep -- the REST surface answers
     * 200 with exactly this content type, and 404 when {@link #preview} is empty.
     *
     * <p>Public, because that REST surface lives in another package and hands one
     * of these straight to the caller.</p>
     */
    public record Preview(byte[] bytes, String contentType) {
    }

    /**
     * The conventional preview file names, tried in order inside a module's own
     * store (a jar store also resolves them under its {@code art/} root). A world
     * that ships {@code preview.png} at its module root needs no code anywhere:
     * this is the whole contract an author has to satisfy.
     */
    static final List<String> PREVIEW_NAMES =
            List.of("preview.png", "preview.jpg", "preview.jpeg");

    /**
     * The hand-verified stock-world previews: a world whose only picture in the
     * shipped library lives in <em>another</em> module's jar, as
     * {@code module -> {source module, asset path}}.
     *
     * <p>This table exists because the stock Open Wonderland world jars are not
     * consistent about carrying a world view. Of the seven, exactly one image in
     * the whole library is a rendered picture of a world:
     * {@code art/Japanese-Pavilion-portal3.png}, which sits in the combined
     * world's jar and shows the Japanese Pavilion -- so the pavilion borrows it
     * and the combined world does not, since one portal plane is not a view of a
     * world that contains six. The other poster-shaped asset in that jar
     * ({@code OWLDefaultWorldPoster.v2-sm.png}) is a text signboard reading "Use
     * the Placemarks menu to explore this world"; serving it as a preview would
     * be a lie about what the world looks like, so it is deliberately absent.
     * Every entry here must be opened and looked at before it is named -- an
     * unverified world has no preview, and the browser falls back to its floor
     * plan.</p>
     */
    private static final Map<String, String[]> PREVIEW_ALIASES = Map.of(
            "wonderland-japanesepavilion-world",
            new String[] {"wonderland-combined-world", "Japanese-Pavilion-portal3.png"});

    /**
     * Resolve a world's preview image: the module's own conventional file first,
     * then the verified alias table. Defensive on the catalog's usual terms -- an
     * unknown module, an absent asset or an unreadable jar all yield empty, never
     * a throw, because a missing picture is a world with no preview, not a fault.
     *
     * <p>The REST surface in {@code server.web} is its only production caller; the
     * resolution order and the degrade are pinned by the catalog's own tests.</p>
     */
    public Optional<Preview> preview(String module) {
        if (module == null || module.isBlank()) {
            return Optional.empty();
        }
        String name = module.trim();
        for (String candidate : PREVIEW_NAMES) {
            Optional<Preview> found = readPreview(name, candidate);
            if (found.isPresent()) {
                return found;
            }
        }
        String[] alias = PREVIEW_ALIASES.get(name);
        return (alias == null) ? Optional.empty() : readPreview(alias[0], alias[1]);
    }

    /** One preview candidate's bytes, with the media type its extension names. */
    private Optional<Preview> readPreview(String storeModule, String path) {
        return openAssetBytes(storeModule, path)
                .map(bytes -> new Preview(bytes, contentTypeOf(path)));
    }

    /**
     * The media type an image asset's name declares. Only the two formats a
     * preview may be: anything else has no type here, so a stray {@code .gif}
     * or {@code .svg} in a world module is not silently served as a PNG.
     *
     * <p>Public and pure so a deployment (or a test) can ask what a name means
     * without opening a store for it.</p>
     */
    public static String contentTypeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    /**
     * Resolve one asset's bytes for {@code module} from whichever store backs it --
     * the classpath for a bundled world, its jar for an installed one (Stage 2: the
     * HTTP asset endpoint uses this to serve a cell's {@code wla://} art to a walk
     * host). The whole read is defensive: an absent module, a missing path or an
     * unreadable jar all return empty, never a throw -- a failed asset is a 404, so
     * a broken reference cannot turn a browse into a 500. A jar is opened, read and
     * closed per call, so no store (and no file handle) outlives the request.
     */
    public Optional<byte[]> openAssetBytes(String module, String path) {
        if (module == null || module.isBlank() || path == null || path.isBlank()) {
            return Optional.empty();
        }
        if (BUNDLED.contains(module)) {
            return readBytes(new ManifestClasspathWorldStore()::open, module, path);
        }
        Optional<Path> jar = worldJar(module);
        if (jar.isEmpty()) {
            return Optional.empty();
        }
        try (JarWorldStore store = new JarWorldStore(jar.get())) {
            return readBytes(store::open, module, path);
        } catch (IOException ex) {
            LOG.warn("world jar {} for asset '{}' could not be opened ({})",
                    jar.get().getFileName(), path, ex.toString());
            return Optional.empty();
        }
    }

    /** Read a store's {@code open(module, path)} fully, closing the stream; empty on any miss. */
    private static Optional<byte[]> readBytes(OpenFunction open, String module, String path) {
        try (InputStream in = open.open(module, path).orElse(null)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** The directory's world jars, sorted; empty when no worlds directory is configured. */
    List<Path> worldJars() {
        if (worldsDir == null || !Files.isDirectory(worldsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(worldsDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> stem(p)))
                    .toList();
        } catch (IOException ex) {
            LOG.warn("cannot list worlds directory {}: {}", worldsDir, ex.toString());
            return List.of();
        }
    }

    private WorldSummary bundledSummary(String module) {
        ManifestClasspathWorldStore store = new ManifestClasspathWorldStore();
        int cells = store.listAssets(module, CELL_SUFFIX).size();
        String[] title = readModuleXml(store::open, module);
        return new WorldSummary(module, title[0], title[1],
                WorldSummary.SOURCE_CLASSPATH, cells);
    }

    private WorldSummary jarSummary(Path jar) {
        String module = stem(jar);
        int cells = 0;
        String[] title = new String[] {module, ""};
        try (JarWorldStore store = new JarWorldStore(jar)) {
            cells = store.listAssets(module, CELL_SUFFIX).size();
            title = readModuleXml(store::open, module);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("world jar {} is not readable as a world ({}); listing it by name only",
                    jar.getFileName(), ex.toString());
        }
        return new WorldSummary(module, title[0], title[1], WorldSummary.SOURCE_JAR, cells);
    }

    /**
     * Read the {@code <name>} and {@code <description>} out of a module's
     * {@code module.xml} through any store's {@code open}. Degrades to
     * {module, ""} when module.xml is absent or unreadable -- the module name is a
     * fine title, and a broken description is not worth losing the row over.
     */
    private static String[] readModuleXml(OpenFunction open, String module) {
        try (InputStream in = open.open(module, "module.xml").orElse(null)) {
            if (in == null) {
                return new String[] {module, ""};
            }
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String name = first(NAME, xml);
            String desc = first(DESCRIPTION, xml);
            return new String[] {name == null ? module : name, desc == null ? "" : desc};
        } catch (IOException | RuntimeException ex) {
            return new String[] {module, ""};
        }
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String stem(Path jar) {
        String name = jar.getFileName().toString();
        return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }

    /** A store's {@code open(module, path)} as a functional seam shared by both origins. */
    @FunctionalInterface
    private interface OpenFunction {
        Optional<InputStream> open(String module, String path);
    }
}
