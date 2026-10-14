/*
 * wonderland-server (M3): the wonderland-wfs REST surface rebuilt. The 2006 WFS
 * types were JAX-RS-annotated DTOs the client polled over an HTTP server GlassFish
 * provided; the annotations landed in M0 (data model only) and this is the real
 * server side -- a Spring @RestController that reads the WorldModel and answers
 * with the same resource vocabulary (the world's cells and their descriptors).
 *
 * It is deliberately read-only for M3: WFS was a frame-sharing read/browse surface,
 * and every mutation of the world still rides the cell-protocol WebSocket, not REST.
 * Keeping it on the WorldModel (not a copy) means the REST view and the wire view of
 * the world can never drift apart.
 */
package org.jdesktop.wonderland.server.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.server.world.WorldCatalog;
import org.jdesktop.wonderland.server.world.WorldCell;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.jdesktop.wonderland.server.world.WorldSummary;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/world")
public class WorldRestController {

    private final WorldModel world;
    private final WorldCatalog catalog;

    public WorldRestController(WorldModel world, WorldCatalog catalog) {
        this.world = world;
        this.catalog = catalog;
    }

    /**
     * Every world the server can serve -- the bundled classpath worlds (the
     * outpatient clinic) plus the world jars in the configured worlds directory.
     * This is the "access to all existing worlds" surface: the catalog is read
     * independently of the seeded one, so a browser can list what is available
     * even before (or without) any world being loaded into {@link #world}.
     * Exactly one entry may carry {@code hosted}: the one whose module was
     * seeded into the live world, so a browser can ask "is the world I picked
     * the world that is running?" in the same read that listed it.
     */
    @GetMapping("/worlds")
    public List<WorldSummary> worlds() {
        String live = world.getLiveModule();
        List<WorldSummary> out = new ArrayList<>();
        for (WorldSummary listed : catalog.available()) {
            out.add(new WorldSummary(listed.module(), listed.title(),
                    listed.description(), listed.source(), listed.cellCount(),
                    listed.module().equals(live)));
        }
        return out;
    }

    @GetMapping("/cells")
    public List<Map<String, Object>> cells(
            @RequestParam(value = "module", required = false) String module) {
        // No module: the live (seeded) world, exactly as before. A module: that
        // catalog world read on demand, so a browser can pick any listed world
        // and see its cells without the server having seeded it.
        if (module != null && !module.isBlank()) {
            return catalog.describeCells(module.trim());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorldCell cell : world.hierarchy()) {
            out.add(describe(cell));
        }
        return out;
    }

    @GetMapping("/cells/{id}")
    public ResponseEntity<Map<String, Object>> cell(@PathVariable("id") long id) {
        WorldCell cell = world.find(new CellID(id));
        if (cell == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(describe(cell));
    }

    /**
     * Serve one world's preview image (the browser's vignette): a picture of the
     * world rather than a diagram of it, so "what does this world look like?" is
     * answered with the art the world itself carries. Resolution is the catalog's
     * ({@link WorldCatalog#preview}) -- the module's own {@code preview.png} first,
     * then the hand-verified stock-art alias table -- and the answer is a plain
     * 404 when a world has no picture, which is the browser's cue to fall back to
     * the floor plan. No path is taken from the caller here, so unlike
     * {@link #asset} there is nothing to confine: the module name selects a store
     * and the file names are the server's own constants.
     */
    @GetMapping("/preview")
    public ResponseEntity<byte[]> preview(@RequestParam("module") String module) {
        return catalog.preview(module).map(p -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(p.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                        .body(p.bytes()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Serve one world asset's bytes (Stage 2: the HTTP asset endpoint). A cell's
     * descriptor carries a {@code wla://<module>/<path>} deployed-model URL; the
     * walk host resolves the art it names by calling this with that module and
     * path, so the model bytes come over plain HTTP and the WebSocket wire format
     * is never touched. The read is confined to the module's own store (a bundled
     * classpath world or an installed jar) via {@link WorldCatalog#openAssetBytes};
     * a missing asset is a 404, and a path that tries to escape the module root is
     * rejected as a 400 before the store is consulted.
     */
    @GetMapping("/asset")
    public ResponseEntity<byte[]> asset(@RequestParam("module") String module,
            @RequestParam("path") String path) {
        if (!isConfinedAssetPath(path)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Optional<byte[]> bytes = catalog.openAssetBytes(module, path);
        return bytes.map(b -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(b))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Whether {@code path} stays inside the module root: no leading '/', no parent
     * ('..') segment, no backslash. Defense in depth -- the stores already refuse
     * traversal, but the endpoint should not even hand a crafted path to them.
     */
    private static boolean isConfinedAssetPath(String path) {
        return path != null && !path.isBlank() && !path.startsWith("/")
                && !path.contains("..") && !path.contains("\\");
    }

    private Map<String, Object> describe(WorldCell cell) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", cell.getCellID().toString());
        map.put("parent", cell.getParentID() == null ? null : cell.getParentID().toString());
        map.put("className", cell.getClassName());
        map.put("name", cell.getName());
        // only a model cell carries a URL; omitting it for the rest keeps the
        // environment/non-model descriptor exactly as before (no null noise)
        if (cell.getDeployedModelURL() != null) {
            map.put("deployedModelURL", cell.getDeployedModelURL());
        }
        return map;
    }
}
