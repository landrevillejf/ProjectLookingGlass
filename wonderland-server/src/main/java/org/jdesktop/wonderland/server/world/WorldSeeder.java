/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server (world seeding): loads a world module into the authoritative
 * WorldModel at startup, so a running server actually hosts content instead of an
 * empty Environment root.
 *
 * This is the last mile M5 deferred: WorldLoader already decodes a module's
 * *-wlc.xml cells and resolves their deployed models headless, and the desktop
 * bridge already mounts a loaded cell -- but nothing ever fed one into the other.
 * The seeder closes that gap on the SERVER side: it reads a world through a
 * ManifestClasspathWorldStore, and for each cell mints a CellID and stores a
 * WorldCell carrying the client class name (inert data, so the server never
 * imports the renderer), the human name, and the transform lifted from the
 * position component. The REST surface then lists it and the wire fan-out pushes
 * it -- the same WorldModel the router and controllers already read.
 *
 * Seeding is OPT-IN and OFF by default (wonderland.world.seed=none): the M3b
 * socket integration test asserts an empty world fans out exactly the environment
 * plus the fresh view cell, so a world that seeded unconditionally would break
 * it. A deployment (or bootRun with -Dwonderland.world.seed=wonderland-outpatient-clinic)
 * names the module to load; anything that fails to load degrades to an empty
 * world with a warning, never a boot failure -- a bad world must not stop a server.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
import org.jdesktop.wonderland.modules.asset.ManifestClasspathWorldStore;
import org.jdesktop.wonderland.modules.asset.WorldContents;
import org.jdesktop.wonderland.modules.asset.WorldLoader;
import org.jdesktop.wonderland.modules.asset.WorldStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds the {@link WorldModel} from a configured world module at startup. */
@Component
public class WorldSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(WorldSeeder.class);

    /**
     * The client-side class a model cell instantiates to. Held as inert string
     * data -- the server must not import the renderer, exactly as it never imports
     * the client (see the module header). This is the class the cell cache
     * reflects by name when the LOAD_CELL reaches a client.
     */
    static final String MODEL_CELL_CLASS =
            "org.jdesktop.wonderland.renderer.cell.WonderlandModelCell";

    /** The sentinel seed value that leaves the world empty (the default). */
    static final String SEED_NONE = "none";

    private final WorldModel world;
    private final String seedModule;

    public WorldSeeder(WorldModel world,
            @Value("${wonderland.world.seed:" + SEED_NONE + "}") String seedModule) {
        this.world = world;
        this.seedModule = seedModule == null ? SEED_NONE : seedModule.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedModule.isEmpty() || SEED_NONE.equalsIgnoreCase(seedModule)) {
            return; // empty world, exactly as the tests expect
        }
        seed(new ManifestClasspathWorldStore(), seedModule);
    }

    /**
     * Load {@code module} through {@code store} and seed the world, returning the
     * number of cells stored. The whole operation is defensive: a world that
     * cannot load (a broken store, an absent JAXB runtime) is logged and yields an
     * empty world rather than a thrown boot failure.
     */
    int seed(WorldStore store, String module) {
        try {
            return seedContents(new WorldLoader().load(store, module));
        } catch (RuntimeException | Error ex) {
            // A world that cannot load must not stop the server: degrade to empty.
            LOG.warn("world '{}' failed to seed; serving an empty world ({})",
                    module, ex.toString());
            return 0;
        }
    }

    /** Seed every named cell of a loaded world; returns how many were stored. */
    int seedContents(WorldContents contents) {
        int seeded = 0;
        for (WorldContents.LoadedCell cell : contents.cells()) {
            if (seedCell(cell.state())) {
                seeded++;
            }
        }
        LOG.info("seeded world '{}': {} of {} cells", contents.module(),
                seeded, contents.cellCount());
        return seeded;
    }

    /**
     * Store one decoded cell in the authoritative world. A cell with no name is
     * skipped (nothing meaningful to list); the transform is taken from the
     * position component when present, else identity.
     *
     * @return whether the cell was seeded
     */
    boolean seedCell(CellServerState state) {
        if (state == null || state.getName() == null || state.getName().isEmpty()) {
            return false;
        }
        boolean isModel = state.getComponentServerState(
                ModelCellComponentServerState.class) != null;
        String className = isModel ? MODEL_CELL_CLASS : WorldModel.ENVIRONMENT_CLASS;
        world.addCell(world.newCellID(), className, state.getName(),
                transformOf(state), new CellClientState());
        return true;
    }

    /** The cell transform from the position component (translation/rotation/scale). */
    static CellTransform transformOf(CellServerState state) {
        Object component = state.getComponentServerState(PositionComponentServerState.class);
        if (component instanceof PositionComponentServerState position) {
            Vector3f scale = position.getScaling();
            return new CellTransform(position.getRotation(), position.getTranslation(), scale);
        }
        return new CellTransform();
    }
}
