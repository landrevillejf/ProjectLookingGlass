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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.server.world.WorldCell;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/world")
public class WorldRestController {

    private final WorldModel world;

    public WorldRestController(WorldModel world) {
        this.world = world;
    }

    @GetMapping("/cells")
    public List<Map<String, Object>> cells() {
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

    private Map<String, Object> describe(WorldCell cell) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", cell.getCellID().toString());
        map.put("parent", cell.getParentID() == null ? null : cell.getParentID().toString());
        map.put("className", cell.getClassName());
        map.put("name", cell.getName());
        return map;
    }
}
