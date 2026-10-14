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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
        public final String id;
        public final String parent;
        public final String className;
        public final String name;

        public Cell(String id, String parent, String className, String name) {
            this.id = id;
            this.parent = parent;
            this.className = className;
            this.name = name;
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
     * Parses the {@code /api/world/cells} document: a JSON array of objects
     * carrying {@code id}, {@code parent}, {@code className} and {@code name}.
     * Unknown extra fields are ignored and an entry without an id is skipped --
     * a row the panel cannot select is worse than one fewer row.
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
                    emptyToNull(r.className), emptyToNull(r.name)));
        }
        return new WorldView(cells);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Jackson's view of one wire entry; the four fields the server describes a
     * cell with. {@code ignoreUnknown} keeps the reader forward-compatible: a
     * server that later adds a transform or an owner to the document is still
     * readable here rather than failing the whole refresh.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawCell {
        public String id;
        public String parent;
        public String className;
        public String name;
    }
}
