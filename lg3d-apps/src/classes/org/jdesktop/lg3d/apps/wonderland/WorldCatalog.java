/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.wonderland;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The AWT-free world backend of the Wonderland World app: it reads the running
 * {@code wonderland-server} world over its REST surface (the Spring
 * {@code WorldRestController}, {@code GET /api/world/cells}) using only the
 * JDK's {@link HttpClient} and Jackson, exactly like the Weather app reads
 * Open-Meteo.
 *
 * <p>This is the seam the Swing {@link WonderlandWorldPanel} renders, so all the
 * fetching, parsing and hierarchy arithmetic is exercised headlessly by
 * {@code WorldCatalogTest} with no network and no X display: the transport is
 * injected through {@link Fetcher}, and the default one is only ever built when
 * a real call is made.</p>
 *
 * <p>Why REST and not the cell protocol: the wire protocol needs a live
 * {@code CellClientSession} and a server handshake, and the desktop has no
 * production transport for it yet (that is {@code wonderland-client}'s own
 * story). The REST view is the server's own {@code WorldModel} rendered
 * read-only, so what this panel shows can never drift from what the world
 * actually holds &mdash; and a panel that reads it cannot corrupt it.</p>
 *
 * <p>The server answers a <em>flat</em> list carrying {@code id}, {@code parent},
 * {@code className} and {@code name} in publication order (the environment root
 * first, then ascending cell id, so a parent always precedes its children). The
 * tree the panel draws is therefore computed here, from those four fields, and
 * nothing else.</p>
 */
public final class WorldCatalog {

    /**
     * System property naming the server to read, so a launcher script or
     * {@code -Dlg.wonderland.server.url=host:port} points the app at a world
     * without editing it. Same {@code lg.wonderland.*} vocabulary the softphone
     * bridge in this package already uses.
     */
    public static final String SERVER_PROPERTY = "lg.wonderland.server.url";

    /** The server assumed when {@link #SERVER_PROPERTY} is unset or blank. */
    public static final String DEFAULT_SERVER = "http://localhost:8080";

    /** The world-cells resource, relative to the server root. */
    static final String CELLS_PATH = "/api/world/cells";

    /** The worlds-catalog resource, relative to the server root. */
    static final String WORLDS_PATH = "/api/world/worlds";

    /** The per-world preview-image resource, relative to the server root. */
    static final String PREVIEW_PATH = "/api/world/preview";

    /** The one-asset endpoint a world's models and textures come from. */
    static final String ASSET_PATH = "/api/world/asset";

    /** Request timeout: a local Spring Boot answers in milliseconds. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Shared client; no connection is made at construction. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorldCatalog() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /**
     * One cell of the published world, as the REST surface describes it.
     *
     * <p>{@code parent} is {@code null} for the environment root. The remaining
     * fields may be missing from the document &mdash; {@code name} is optional
     * on the server &mdash; which is why {@link #label()} falls back.</p>
     */
    public static final class Cell {
        /** The root every shipped world files its content next to; a container, not a surface. */
        private static final String ENVIRONMENT_CELL_TYPE = "environment-cell";

        public final String id;
        public final String parent;
        public final String className;
        public final String name;

        /**
         * The placement on the floor plane, or {@code null} when the server
         * described no position component for this cell. {@code x}/{@code z}
         * are the translation; {@code width}/{@code depth} the footprint.
         * Absent is not zero: a cell "at the origin" says so with real
         * numbers, an unplaced one says nothing (see {@link #placed()}).
         */
        public final Double x;
        public final Double z;
        public final Double width;
        public final Double depth;

        /**
         * The height above the floor plane, when the server described one. The
         * floor plan ignores it (it looks straight down); a 3D view cannot, so it
         * is carried with the rest of the placement rather than assumed to be 0.
         */
        public final Double y;

        /**
         * The cell's {@code wla://} deployed-model reference, or {@code null}
         * when the server named no model. This is what turns a listed cell into
         * geometry: the asset endpoint resolves the reference, the model loader
         * decodes it, and the world has something to draw. A cell with a
         * placement but no model is a room you can see the floor of.
         */
        public final String modelUrl;

        /**
         * How the cell's model is turned and stretched, or {@link Pose#IDENTITY}
         * when the server described neither. This is the difference between a
         * world and a pile of art: the {@code .dep} normalises a model to the
         * importer's units, and the position component's rotation and scale are
         * what put it back into the world as authored. A renderer that ignores
         * them draws the town rotated the wrong way and the clinic floor a metre
         * across, which is precisely how this port came to show nothing.
         */
        public final Pose pose;

        /**
         * The {@code wla://} reference to the body a resident wears, or
         * {@code null} when this cell is not an avatar or the world left the
         * choice to the client. A resident is not a piece of art: it has no
         * {@code .dep}, because nobody deployed it -- the avatar pool holds bare
         * COLLADA bodies, which is why this is a separate field rather than a
         * second value in {@link #modelUrl}.
         */
        public final String avatarUrl;

        /**
         * The element the world wrote this cell as -- {@code image-viewer-cell},
         * {@code pdfviewer-cell}, {@code whiteboard-cell} -- or {@code null}
         * when the server named no type. A world is not only models: the stock
         * combined world pins four posters, a document and a whiteboard into it,
         * and a browser that recognised only {@code model-cell} drew a world with
         * nine of its forty-one objects missing. The type is what lets this
         * client tell a poster it can hang from a widget it cannot.
         */
        public final String cellType;

        /**
         * The {@code wla://} reference to the picture a cell displays, or
         * {@code null} when it displays none. This is the poster's image and the
         * document's cover: art the world module already ships and the asset
         * endpoint already serves, needing only a surface to be seen on.
         */
        public final String assetUrl;

        /**
         * How big the picture a GUI cell shows is in the world, or {@code null}
         * for every cell that is not one. A plane needs three numbers and the file
         * only carries two of them: how wide one of its pixels is in metres, and --
         * for the viewers that write it -- how many of them it wants. The third,
         * the picture's own pixel width, belongs to whoever holds the bytes, so it
         * is read off the fetched image and this field stays at 0.
         */
        public final Plane plane;

        /**
         * One GUI plane's authored measure, in the units the cell file uses.
         *
         * @param metresPerPxX how wide one pixel of the picture is, along x
         * @param metresPerPxY the same along y
         * @param widthPx the picture's width in pixels as the cell recorded it, or
         *                0 when only the picture itself can say
         * @param heightPx the picture's height in pixels, or 0
         */
        public record Plane(double metresPerPxX, double metresPerPxY,
                double widthPx, double heightPx) {

            /**
             * The size of one picture pixel that every GUI cell in the shipped
             * worlds writes and the format therefore means by default: one
             * centimetre. Measured off the files, not chosen -- the combined world's
             * PDF viewer writes {@code <pixelScaleX>0.01</pixelScaleX>} alongside a
             * 640x480 preference, which is the 6.4x4.8 m screen standing in its field.
             */
            public static final double DEFAULT_METRES_PER_PX = 0.01;

            /** Whether the cell recorded a pixel size, rather than the image. */
            public boolean hasPixelSize() {
                return widthPx > 0.0 && heightPx > 0.0;
            }
        }

        /** The cell's turn about the vertical axis and its authored stretch. */
        public record Pose(double yawRad, double scaleX, double scaleY, double scaleZ) {

            /** A cell the world neither turned nor stretched. */
            public static final Pose IDENTITY = new Pose(0.0, 1.0, 1.0, 1.0);

            /** Whether this pose would change a matrix at all. */
            public boolean isIdentity() {
                return yawRad == 0.0 && scaleX == 1.0 && scaleY == 1.0
                        && scaleZ == 1.0;
            }
        }

        public Cell(String id, String parent, String className, String name) {
            this(id, parent, className, name, null, null, null, null);
        }

        public Cell(String id, String parent, String className, String name,
                Double x, Double z, Double width, Double depth) {
            this(id, parent, className, name, x, z, width, depth, null, null);
        }

        public Cell(String id, String parent, String className, String name,
                Double x, Double z, Double width, Double depth, Double y,
                String modelUrl) {
            this(id, parent, className, name, x, z, width, depth, y, modelUrl,
                    Pose.IDENTITY);
        }

        public Cell(String id, String parent, String className, String name,
                Double x, Double z, Double width, Double depth, Double y,
                String modelUrl, Pose pose) {
            this(id, parent, className, name, x, z, width, depth, y, modelUrl, pose,
                    null);
        }

        public Cell(String id, String parent, String className, String name,
                Double x, Double z, Double width, Double depth, Double y,
                String modelUrl, Pose pose, String avatarUrl) {
            this(id, parent, className, name, x, z, width, depth, y, modelUrl, pose,
                    avatarUrl, null, null);
        }

        public Cell(String id, String parent, String className, String name,
                Double x, Double z, Double width, Double depth, Double y,
                String modelUrl, Pose pose, String avatarUrl,
                String cellType, String assetUrl) {
            this(id, parent, className, name, x, z, width, depth, y, modelUrl, pose,
                    avatarUrl, cellType, assetUrl, null);
        }

        public Cell(String id, String parent, String className, String name,
                Double x, Double z, Double width, Double depth, Double y,
                String modelUrl, Pose pose, String avatarUrl,
                String cellType, String assetUrl, Plane plane) {
            this.id = id;
            this.parent = parent;
            this.className = className;
            this.name = name;
            this.x = x;
            this.z = z;
            this.width = width;
            this.depth = depth;
            this.y = y;
            this.modelUrl = (modelUrl == null || modelUrl.isBlank()) ? null : modelUrl;
            this.pose = (pose == null) ? Pose.IDENTITY : pose;
            this.avatarUrl = (avatarUrl == null || avatarUrl.isBlank())
                    ? null : avatarUrl;
            this.cellType = (cellType == null || cellType.isBlank()) ? null : cellType;
            this.assetUrl = (assetUrl == null || assetUrl.isBlank()) ? null : assetUrl;
            this.plane = plane;
        }

        /** Whether the document placed this cell in the world. */
        public boolean placed() {
            return x != null && z != null;
        }

        /** Whether this cell names a model a 3D view can load. */
        public boolean hasModel() {
            return modelUrl != null;
        }

        /**
         * Whether this cell is one of the world's residents rather than a piece
         * of its art. Judged by the class the server named, so a cell counts as
         * an avatar even when the world left its body to the pool default and so
         * says nothing in {@link #avatarUrl}.
         */
        public boolean isAvatar() {
            return "AvatarCell".equals(simpleClassName());
        }

        /**
         * Whether this cell is a picture the world pinned up: a poster, a
         * document's page, a whiteboard or a remote window. Judged by the type the
         * server named rather than by whether the cell names a file, because the
         * shipped worlds contain all four and a browser that recognised only the
         * ones with a fetchable image drew a meeting complex with its whiteboard
         * and both its screens missing. A GUI cell is anything the world wrote as
         * a cell that is neither a model nor a resident, so an object this client
         * has never met still arrives as a plane in the right place rather than
         * being quietly dropped for the crime of having no renderer.
         *
         * <p>The one exception is the environment: every shipped world files an
         * {@code environment-cell} alongside its content, and that cell is the
         * container the rest hang from rather than a surface to hang something
         * on. Drawing it would put a blank card at the origin of all seven worlds.
         * </p>
         */
        public boolean isGui() {
            return cellType != null && !hasModel() && !isAvatar()
                    && !ENVIRONMENT_CELL_TYPE.equals(cellType);
        }

        /**
         * The name to show: the cell's own name, else the simple class name,
         * else the raw id. A world cell always has a class (the server creates
         * it from the module), but a hand-written document might not, so the id
         * is the last resort rather than an empty row. A blank name is no name.
         */
        public String label() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            if (className != null && !className.isBlank()) {
                int dot = className.lastIndexOf('.');
                return (dot < 0) ? className : className.substring(dot + 1);
            }
            return (id == null) ? "?" : id;
        }

        /** The cell type without its package, or the empty string. */
        public String simpleClassName() {
            if (className == null || className.isBlank()) {
                return "";
            }
            int dot = className.lastIndexOf('.');
            return (dot < 0) ? className : className.substring(dot + 1);
        }
    }

    /**
     * An immutable snapshot of a published world: the cells in the order the
     * server published them, plus the tree computed from their {@code parent}
     * links.
     */
    public static final class WorldView {

        private final List<Cell> cells;

        WorldView(List<Cell> cells) {
            this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
        }

        /** The cells in publication order (parents before children). */
        public List<Cell> cells() {
            return cells;
        }

        public int size() {
            return cells.size();
        }

        public boolean isEmpty() {
            return cells.isEmpty();
        }

        /** @return the named cell, or {@code null} when the world has no such id. */
        public Cell find(String id) {
            if (id == null) {
                return null;
            }
            for (Cell cell : cells) {
                if (id.equals(cell.id)) {
                    return cell;
                }
            }
            return null;
        }

        /** The cells whose parent is absent from the world (roots). */
        public List<Cell> roots() {
            List<Cell> out = new ArrayList<>();
            for (Cell cell : cells) {
                if (cell.parent == null || find(cell.parent) == null) {
                    out.add(cell);
                }
            }
            return out;
        }

        /** The direct children of {@code parentId}, in publication order. */
        public List<Cell> childrenOf(String parentId) {
            List<Cell> out = new ArrayList<>();
            if (parentId == null) {
                return out;
            }
            for (Cell cell : cells) {
                if (parentId.equals(cell.parent)) {
                    out.add(cell);
                }
            }
            return out;
        }

        /**
         * How deep {@code cellId} sits in the tree: 0 for a root, 1 for its
         * child, and so on.
         *
         * <p>An unknown id reads as depth 0 rather than -1, so a caller can
         * indent with it directly. A {@code parent} cycle &mdash; which the
         * server's own guards should never publish, but a hand-written document
         * can &mdash; terminates here after walking each cell once, so a two-cell
         * loop is depth 1 rather than an infinite climb.</p>
         */
        public int depthOf(String cellId) {
            Cell cell = find(cellId);
            if (cell == null) {
                return 0;
            }
            Set<String> seen = new HashSet<>();
            seen.add(cell.id);
            int depth = 0;
            while (cell.parent != null && seen.add(cell.parent)) {
                Cell parent = find(cell.parent);
                if (parent == null) {
                    break; // an orphan hangs off the visible tree at its own depth
                }
                cell = parent;
                depth++;
            }
            return depth;
        }

        /**
         * The breadcrumb from the root down to {@code cellId}, joined by
         * {@code " / "} &mdash; what the panel shows under a selected row so a
         * flat id is readable as a place in the world.
         *
         * <p>Same one-pass rule as {@link #depthOf}: on a corrupted parent chain
         * the walk stops where a cell repeats, so the path names the cells it
         * visited instead of hanging the event thread.</p>
         */
        public String pathOf(String cellId) {
            List<String> chain = new ArrayList<>();
            Cell cell = find(cellId);
            Set<String> seen = new HashSet<>();
            while (cell != null && seen.add(cell.id)) {
                chain.add(0, cell.label());
                cell = (cell.parent == null) ? null : find(cell.parent);
            }
            return String.join(" / ", chain);
        }

        /**
         * One-line census of the world for the status strip: how many cells,
         * how many levels deep, and the root's label. Empty world says so
         * plainly instead of "0 cells / 0 levels".
         */
        public String summary() {
            if (isEmpty()) {
                return "the world has no cells";
            }
            int levels = 0;
            for (Cell cell : cells) {
                levels = Math.max(levels, depthOf(cell.id) + 1);
            }
            List<Cell> roots = roots();
            String rootLabel = roots.isEmpty() ? "?" : roots.get(0).label();
            return cells.size() + (cells.size() == 1 ? " cell" : " cells")
                    + " \u00B7 " + levels + (levels == 1 ? " level" : " levels")
                    + " \u00B7 root: " + rootLabel;
        }
    }

    // ------------------------------------------------------------------
    // Catalog model
    // ------------------------------------------------------------------

    /**
     * One world the server offers, as its {@code /api/world/worlds} resource
     * describes it: the module name (the id used to ask for its cells), the human
     * title and description read from its {@code module.xml}, where it comes from
     * (a bundled classpath world or an installed jar), how many cells it
     * carries, and whether the server is <em>hosting</em> it right now &mdash;
     * the seeded live world, not merely a loadable one. This is the row the
     * browser's picker lists before any world is chosen; {@link #module} is the
     * key {@link #cellsUrl(String, String)} turns back into a cells request.
     */
    public record WorldEntry(String module, String title, String description,
            String source, int cellCount, boolean hosted) {

        /** A listed world read from a server that says nothing about hosting. */
        public WorldEntry(String module, String title, String description,
                String source, int cellCount) {
            this(module, title, description, source, cellCount, false);
        }

        /** The label to show in the picker: the title, falling back to the module. */
        public String displayLabel() {
            String shown = (title == null || title.isBlank()) ? module : title;
            return shown + " (" + module + ")";
        }
    }

    // ------------------------------------------------------------------
    // Transport seam
    // ------------------------------------------------------------------

    /** Fetches one document; the seam that keeps the tests off the network. */
    public interface Fetcher {
        /**
         * @param url an absolute http(s) URL
         * @return the response body
         * @throws IOException on a transport failure or a non-2xx status
         */
        String fetch(String url) throws IOException;
    }

    /** The real transport: a blocking GET on the JDK HTTP client. */
    static final Fetcher HTTP_FETCHER = url -> {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "lg3d-wonderland-world/1.0")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading " + url, ie);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return resp.body();
    };

    /**
     * Fetches a binary document; the preview image's seam. It differs from
     * {@link Fetcher} in one load-bearing way: a 404 is an <em>answer</em>
     * ("this world has no picture"), not a failure, so it yields empty rather
     * than throwing. Any other non-2xx is still a transport failure.
     */
    public interface ByteFetcher {
        Optional<byte[]> fetch(String url) throws IOException;
    }

    /** The real binary transport: a blocking GET that reads the body as bytes. */
    static final ByteFetcher HTTP_BYTE_FETCHER = url -> {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "lg3d-wonderland-world/1.0")
                .header("Accept", "image/png, image/jpeg")
                .GET()
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading " + url, ie);
        }
        if (resp.statusCode() == 404) {
            return Optional.empty();
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return Optional.of(resp.body());
    };

    // ------------------------------------------------------------------
    // Server address handling
    // ------------------------------------------------------------------

    /**
     * The server the app should read: {@link #SERVER_PROPERTY} when set to
     * something non-blank, else {@link #DEFAULT_SERVER}.
     *
     * <p>Package-visible rather than inlined in the panel so the fallback rule
     * (a blank property is the same as no property) is a tested claim.</p>
     */
    public static String defaultServer() {
        String configured = System.getProperty(SERVER_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_SERVER;
        }
        return normalizeServer(configured.trim());
    }

    /**
     * Turns what a user typed into a base URL: {@code localhost:8080} and
     * {@code http://host:8080/} both become {@code http://host:8080}, because
     * the field holds a server, not a page, and {@link URI#create(String)}
     * rejects the bare form.
     *
     * @throws IllegalArgumentException when nothing was given
     */
    public static String normalizeServer(String server) {
        if (server == null || server.trim().isEmpty()) {
            throw new IllegalArgumentException("server must not be empty");
        }
        String base = server.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.contains("://")) {
            base = "http://" + base;
        }
        return base;
    }

    /** The full cells URL for a server address. */
    public static String cellsUrl(String server) {
        return normalizeServer(server) + CELLS_PATH;
    }

    /** The full worlds-catalog URL for a server address. */
    public static String worldsUrl(String server) {
        return normalizeServer(server) + WORLDS_PATH;
    }

    /**
     * The cells URL for one named module, so the browser can read a world it
     * picked from the catalog rather than only the single one the server seeded.
     * The module is percent-encoded as a query value.
     */
    public static String cellsUrl(String server, String module) {
        return cellsUrl(server) + "?module="
                + URLEncoder.encode(module, StandardCharsets.UTF_8);
    }

    /**
     * The preview-image URL for one named module: the picture the server says it
     * has of that world, or a 404 when it has none (which is the panel's cue to
     * draw the floor plan instead). Same encoding rule as {@link #cellsUrl}.
     */
    public static String previewUrl(String server, String module) {
        return normalizeServer(server) + PREVIEW_PATH + "?module="
                + URLEncoder.encode(module, StandardCharsets.UTF_8);
    }

    /**
     * The asset URL for one file inside one world module: the model, texture or
     * image a {@code wla://<module>/<path>} reference names, served as bytes.
     * Same encoding rule as {@link #cellsUrl}, and a missing asset is a 404.
     */
    public static String assetUrl(String server, String module, String path) {
        return normalizeServer(server) + ASSET_PATH
                + "?module=" + URLEncoder.encode(module, StandardCharsets.UTF_8)
                + "&path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    /**
     * The command that boots a local {@code wonderland-server} serving the
     * bundled outpatient-clinic world. The panel names it when a read fails:
     * the single most common reason a first launch shows "cannot reach" is that
     * no server is running, and the desktop deliberately leaves
     * {@code wonderland-server} a separate process (it never starts one). One
     * source of truth so the face and its test name the same command.
     */
    public static String startCommand() {
        return "./gradlew :wonderland-server:bootRun "
                + "--args='--wonderland.world.seed=wonderland-outpatient-clinic'";
    }

    // ------------------------------------------------------------------
    // Load + parse
    // ------------------------------------------------------------------

    /**
     * Reads the published world from {@code server} (blocking; call off the
     * EDT).
     *
     * @throws IOException when the server is unreachable or not answering REST
     */
    public static WorldView load(String server) throws IOException {
        return load(server, HTTP_FETCHER);
    }

    /** Loads through an injected transport; the test seam. */
    static WorldView load(String server, Fetcher fetcher) throws IOException {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        return parse(fetcher.fetch(cellsUrl(server)));
    }

    /**
     * Reads the published cells of one named world (blocking; call off the EDT),
     * rather than the single world the server seeded. This is what lets the
     * browser list every catalog world and open whichever one the user picks.
     */
    public static WorldView loadWorld(String server, String module) throws IOException {
        return loadWorld(server, module, HTTP_FETCHER);
    }

    /** Loads one module's cells through an injected transport; the test seam. */
    static WorldView loadWorld(String server, String module, Fetcher fetcher) throws IOException {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        return parse(fetcher.fetch(cellsUrl(server, module)));
    }

    /** Reads the list of worlds the server can serve (blocking; call off the EDT). */
    public static List<WorldEntry> loadWorlds(String server) throws IOException {
        return loadWorlds(server, HTTP_FETCHER);
    }

    /** Loads the world catalog through an injected transport; the test seam. */
    static List<WorldEntry> loadWorlds(String server, Fetcher fetcher) throws IOException {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        return parseWorlds(fetcher.fetch(worldsUrl(server)));
    }

    /**
     * Reads one world's preview image (blocking; call off the EDT). Empty means
     * the server has no picture of this world -- a 404, not an error -- and the
     * caller falls back to the floor plan.
     */
    public static Optional<byte[]> loadPreview(String server, String module)
            throws IOException {
        return loadPreview(server, module, HTTP_BYTE_FETCHER);
    }

    /** Loads a preview through an injected transport; the test seam. */
    static Optional<byte[]> loadPreview(String server, String module, ByteFetcher fetcher)
            throws IOException {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        return fetcher.fetch(previewUrl(server, module));
    }

    /**
     * Parses the {@code /api/world/cells} document: a JSON array of objects
     * carrying {@code id}, {@code parent}, {@code className} and {@code name},
     * plus the optional {@code x}/{@code z}/{@code width}/{@code depth} a
     * placement-carrying server adds (see {@link Cell#placed()}). Unknown extra
     * fields are ignored and an entry without an id is skipped -- a row the
     * panel cannot select is worse than one fewer row.
     *
     * @throws IOException when the document is not a JSON array (a Spring error
     *     page is HTML, and an HTML body parsed as a world is a lie)
     */
    public static WorldView parse(String body) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            throw new IOException("empty world document");
        }
        List<RawCell> raw;
        try {
            raw = MAPPER.readValue(body, new TypeReference<List<RawCell>>() { });
        } catch (IOException e) {
            throw new IOException("not a world document: " + e.getMessage(), e);
        }
        List<Cell> cells = new ArrayList<>();
        for (RawCell r : raw) {
            if (r == null || r.id == null || r.id.isEmpty()) {
                continue;
            }
            cells.add(new Cell(r.id, emptyToNull(r.parent),
                    emptyToNull(r.className), emptyToNull(r.name),
                    r.x, r.z, positiveOrNull(r.width), positiveOrNull(r.depth),
                    r.y, emptyToNull(r.deployedModelURL), poseOf(r),
                    emptyToNull(r.avatarModelUrl), emptyToNull(r.cellType),
                    emptyToNull(r.assetURL), planeOf(r)));
        }
        return new WorldView(cells);
    }

    /**
     * The measure a GUI cell carries, or null for a cell that is not one. Absent
     * {@code planeScale} is read as the format's own centimetre-per-pixel rather
     * than as no size: a poster whose file said nothing still has to be drawn
     * somewhere, and dropping it for a missing number is the same mistake this
     * whole path exists to undo.
     */
    private static Cell.Plane planeOf(RawCell r) {
        if (r.cellType == null || r.cellType.isBlank()) {
            return null;
        }
        double sx = pairComponent(r.planeScale, 0,
                Cell.Plane.DEFAULT_METRES_PER_PX);
        double sy = pairComponent(r.planeScale, 1, sx);
        double w = pairComponent(r.planePixels, 0, 0.0);
        double h = pairComponent(r.planePixels, 1, 0.0);
        return new Cell.Plane(sx, sy, w, h);
    }

    /** One component of a two-number list, or {@code fallback} when not carried. */
    private static double pairComponent(List<Double> values, int index,
            double fallback) {
        if (values == null || values.size() <= index) {
            return fallback;
        }
        Double value = values.get(index);
        return (value == null || !Double.isFinite(value) || value <= 0.0)
                ? fallback : value;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * The turn and stretch a wire entry describes, or {@link Cell.Pose#IDENTITY}
     * when it describes neither. A short {@code scale} array is read as far as it
     * goes and left at 1.0 beyond: a server that only ever ships a heading must
     * not cost a cell its authored size, and one that ships a typo must not move
     * the world.
     */
    private static Cell.Pose poseOf(RawCell r) {
        double yaw = (r.yaw == null) ? 0.0 : r.yaw;
        List<Double> scale = r.scale;
        double sx = component(scale, 0);
        double sy = component(scale, 1);
        double sz = component(scale, 2);
        if (yaw == 0.0 && sx == 1.0 && sy == 1.0 && sz == 1.0) {
            return Cell.Pose.IDENTITY;
        }
        return new Cell.Pose(yaw, sx, sy, sz);
    }

    /** One scale component, or 1.0 when the document did not carry it. */
    private static double component(List<Double> scale, int index) {
        if (scale == null || scale.size() <= index) {
            return 1.0;
        }
        Double value = scale.get(index);
        return (value == null || value.isNaN() || value == 0.0) ? 1.0 : value;
    }

    /** A non-positive size is no size: a degenerate footprint reads as unplaced. */
    private static Double positiveOrNull(Double v) {
        return (v == null || v <= 0.0) ? null : v;
    }

    /**
     * Parses the {@code /api/world/worlds} document: a JSON array of objects
     * carrying {@code module}, {@code title}, {@code description}, {@code source}
     * and {@code cellCount}. Unknown extra fields are ignored and an entry with
     * no module is skipped -- a row the picker cannot ask cells for is worse
     * than one fewer row.
     *
     * @throws IOException when the document is not a JSON array (a Spring error
     *     page is HTML, and an HTML body parsed as a catalog is a lie)
     */
    public static List<WorldEntry> parseWorlds(String body) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            throw new IOException("empty worlds document");
        }
        List<RawWorld> raw;
        try {
            raw = MAPPER.readValue(body, new TypeReference<List<RawWorld>>() { });
        } catch (IOException e) {
            throw new IOException("not a worlds document: " + e.getMessage(), e);
        }
        List<WorldEntry> out = new ArrayList<>();
        for (RawWorld r : raw) {
            if (r == null || r.module == null || r.module.isEmpty()) {
                continue;
            }
            out.add(new WorldEntry(r.module, r.title, r.description, r.source,
                    r.cellCount, r.hosted));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Jackson's view of one wire entry; the fields the server describes a cell
     * with, geometry optional. {@code ignoreUnknown} keeps the reader
     * forward-compatible: a server that later adds an owner to the document is
     * still readable here rather than failing the whole refresh.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawCell {
        public String id;
        public String parent;
        public String className;
        public String name;
        public Double x;
        public Double z;
        public Double width;
        public Double depth;
        public Double y;
        public String deployedModelURL;
        /** The body a resident wears; absent means the pool's default. */
        public String avatarModelUrl;
        /** The position component's rotation, reduced to its heading in radians. */
        public Double yaw;
        /** The authored stretch, as {@code [sx, sy, sz]}; absent means 1,1,1. */
        public List<Double> scale;
        /** What the world wrote this cell as, e.g. {@code image-viewer-cell}. */
        public String cellType;
        /** The picture this cell displays, in the world's own {@code wla://} form. */
        public String assetURL;
        /** One GUI plane's metre-per-pixel, as {@code [x, y]}. */
        public List<Double> planeScale;
        /** One GUI plane's pixel size, as {@code [width, height]}; absent means ask the image. */
        public List<Double> planePixels;
    }

    /**
     * Jackson's view of one catalog entry; the five fields the server describes a
     * loadable world with. {@code ignoreUnknown} keeps the reader
     * forward-compatible with a server that later adds more to the summary.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawWorld {
        public String module;
        public String title;
        public String description;
        public String source;
        public int cellCount;
        public boolean hosted;
    }
}
