/*
 * wonderland-server (M3): the authoritative world the session server hosts -- the
 * SGS "session world storage" rebuilt as a plain Spring service. It owns the cell
 * hierarchy every client sees: an environment root cell (the client cache's
 * CellID.getEnvironmentCellID(), always present and always ACTIVE), the content
 * cells a session loads on attach, and the per-user view cell the SET_VIEW
 * handshake mints. applyMove(...) is the server-side mutation a MovableMessage
 * drives; the router reads it back to answer the mover and to broadcast the
 * authoritative transform to everyone else.
 *
 * No wonderland-client / lg3d-core type is imported: cells are WorldCell records
 * keyed by the shared CellID, transforms are the common CellTransform, and the
 * client class name is inert data (the original server likewise stored the
 * client cell class as a string, never the type). The default world is the
 * environment alone; addCell(...) lets a deployment (or a test) seed content, so
 * the hierarchy the router fans out is real, not an empty list.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.bounding.BoundingSphere;
import com.jme.math.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.springframework.stereotype.Service;

@Service
public class WorldModel {

    /**
     * The EnvironmentCell the client cache knows the root by type, and can now
     * instantiate as-is: the class is concrete (like the ViewCell the router
     * names), so instantiateCell's reflection succeeds on the default world
     * instead of throwing InstantiationException on every live walk-in. A
     * deployment/test constructor may still override it below with a desktop's
     * richer subclass name, exactly as the 2006 server shipped world data.
     */
    public static final String ENVIRONMENT_CLASS =
            "org.jdesktop.wonderland.client.cell.EnvironmentCell";

    private final Map<CellID, WorldCell> cells = new ConcurrentHashMap<>();
    private final AtomicLong nextCellID = new AtomicLong(CellID.getFirstCellID());
    private final CellID environmentCellID = CellID.getEnvironmentCellID();

    /**
     * A monotonic count of every world mutation -- the fact an autosave polls
     * instead of a listener list it has to keep in sync. A move, a graft, a
     * removal and a content add each bump it; a read never does. Two snapshots
     * taken with an unchanged revision describe the same world, so autosave can
     * skip the write entirely rather than rewrite a file no one changed. (P5.4)
     */
    private final AtomicLong revision = new AtomicLong(0);

    /**
     * The module whose cells were seeded into this world, or {@code null} when
     * nothing was seeded. The world is a bag of cells and does not otherwise
     * know where they came from; the REST catalog needs that one fact to tell
     * a browser which listed world is actually live (the {@code hosted} flag),
     * so a browser can decide whether the address it points at already runs
     * the world it is about to ask for.
     */
    private volatile String liveModule;

    public WorldModel() {
        this(ENVIRONMENT_CLASS);
    }

    /** Seed the root with a concrete, client-loadable environment class name. */
    public WorldModel(String environmentClassName) {
        // the environment root is always present, parentless, with no client state
        cells.put(environmentCellID, new WorldCell(environmentCellID, null,
                environmentClassName, "Environment", new BoundingSphere(1f, new Vector3f()),
                new CellTransform(), null));
    }

    public CellID getEnvironmentCellID() {
        return environmentCellID;
    }

    /** The seeded module this world now hosts, or {@code null} for an empty one. */
    public String getLiveModule() {
        return liveModule;
    }

    /**
     * Record which module was seeded (called by {@link WorldSeeder} on success).
     * A null/blank module clears the mark, so an unseeded world says so.
     */
    public void setLiveModule(String module) {
        liveModule = (module == null || module.isBlank()) ? null : module.trim();
    }

    /** Mint a fresh cell id (never the environment id, never reused). */
    public CellID newCellID() {
        return new CellID(nextCellID.getAndIncrement());
    }

    /**
     * Register a content cell parented to the environment. Returns the stored
     * descriptor so callers (a seeding runner, a test) can read back its id.
     */
    public WorldCell addCell(CellID cellID, String className, String name,
            CellTransform transform, CellClientState clientState) {
        return addCell(cellID, className, name, transform, clientState, null);
    }

    /**
     * Register a content cell that carries a {@code wla://} deployed-model URL, so
     * the read-only REST surface and the HTTP asset endpoint can resolve the cell's
     * art without the wire (see {@link WorldCell#getDeployedModelURL()}).
     */
    public WorldCell addCell(CellID cellID, String className, String name,
            CellTransform transform, CellClientState clientState, String deployedModelURL) {
        WorldCell cell = new WorldCell(cellID, environmentCellID, className, name,
                new BoundingSphere(1f, new Vector3f()),
                transform == null ? new CellTransform() : transform, clientState,
                deployedModelURL);
        cells.put(cellID, cell);
        revision.incrementAndGet();
        return cell;
    }

    /** Replace a descriptor wholesale (used to graft a view cell under the root). */
    public void putCell(WorldCell cell) {
        cells.put(cell.getCellID(), cell);
        revision.incrementAndGet();
    }

    /** Drop a cell (a departed session's view cell); the environment root stays. */
    public WorldCell removeCell(CellID cellID) {
        if (environmentCellID.equals(cellID)) {
            return null;
        }
        WorldCell dropped = cells.remove(cellID);
        if (dropped != null) {
            revision.incrementAndGet();
        }
        return dropped;
    }

    /** Look up a cell descriptor by id; null when the world does not host it. */
    public WorldCell find(CellID cellID) {
        return cells.get(cellID);
    }

    /** The current mutation count; see {@link #revision} for what it is for. */
    public long revision() {
        return revision.get();
    }

    public boolean contains(CellID cellID) {
        return cells.containsKey(cellID);
    }

    /**
     * Apply a server-authoritative move to a cell's transform. A move for an
     * unknown cell is a no-op returning false (the router turns that into an
     * error reply), so a stale client cannot forge a cell into existence.
     */
    public boolean applyMove(CellID cellID, CellTransform transform) {
        WorldCell existing = cells.get(cellID);
        if (existing == null) {
            return false;
        }
        cells.put(cellID, new WorldCell(existing.getCellID(), existing.getParentID(),
                existing.getClassName(), existing.getName(), existing.getLocalBounds(),
                transform, existing.getClientState(), existing.getDeployedModelURL()));
        revision.incrementAndGet();
        return true;
    }

    /** Every cell id currently hosted (environment included). */
    public List<CellID> cellIDs() {
        return new ArrayList<>(cells.keySet());
    }

    /**
     * The full hierarchy in load order -- a parent always precedes its children --
     * which is what the router pushes to a client after its view is registered.
     * A single-level world (everything parented to the environment) sorts root
     * first, then content by id, which satisfies the loadCell parent-must-exist
     * contract without a recursive walk.
     */
    public List<WorldCell> hierarchy() {
        // Hold the environment root out and lead with it, whatever its id, then
        // sort the content ascending by id. Doing it in two steps rather than one
        // comparator keeps the root-first guarantee explicit and total-orderable,
        // and never leans on the map's iteration order to place the root.
        List<WorldCell> content = new ArrayList<>();
        WorldCell environment = null;
        for (WorldCell cell : cells.values()) {
            if (cell.getCellID().equals(environmentCellID)) {
                environment = cell;
            } else {
                content.add(cell);
            }
        }
        // ascending id so a parent always precedes its children
        content.sort((a, b) -> Long.compare(Long.parseLong(a.getCellID().toString()),
                Long.parseLong(b.getCellID().toString())));
        // the constructor seeds the root and removeCell refuses it, so it is
        // always present to lead the load order
        List<WorldCell> all = new ArrayList<>(content.size() + 1);
        all.add(environment);
        all.addAll(content);
        return all;
    }
}
