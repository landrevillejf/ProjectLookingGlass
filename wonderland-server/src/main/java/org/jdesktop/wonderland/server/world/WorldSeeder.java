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
 * store chosen by {@link WorldCatalog} -- a {@link JarWorldStore} when the seed
 * names an installed world jar in the configured directory, else a
 * {@link ManifestClasspathWorldStore} for a bundled classpath module -- and for
 * each cell mints a CellID and stores a
 * WorldCell carrying the client class name (inert data, so the server never
 * imports the renderer), the human name, the transform lifted from the position
 * component, and the cell's {@code wla://} deployed-model URL. The URL is carried
 * on two surfaces on purpose: the WorldCell field feeds the read-only REST
 * descriptor and the HTTP asset endpoint, while a ModelCellClientState folds the
 * same URL into the shared wire format so a client walking in over the socket
 * receives it in the LOAD_CELL message (the renderer's WonderlandModelCell reads
 * it back in setClientState()). See {@link WorldCell}. The
 * REST surface then lists it and the wire fan-out pushes the cell -- the same
 * WorldModel the router and controllers already read.
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.state.ModelCellClientState;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
import org.jdesktop.wonderland.modules.asset.JarWorldStore;
import org.jdesktop.wonderland.modules.asset.ManifestClasspathWorldStore;
import org.jdesktop.wonderland.modules.asset.WorldContents;
import org.jdesktop.wonderland.modules.asset.WorldLoader;
import org.jdesktop.wonderland.modules.asset.WorldStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * The bundled clean-room outpatient clinic, shipped on the server classpath and
     * therefore seedable with no worlds directory. The catalog names it too.
     */
    static final String CLINIC_MODULE = "wonderland-outpatient-clinic";

    private final WorldModel world;
    private final String seedModule;
    private final WorldCatalog catalog;

    @Autowired
    public WorldSeeder(WorldModel world,
            @Value("${wonderland.world.seed:" + SEED_NONE + "}") String seedModule,
            WorldCatalog catalog) {
        this.world = world;
        this.seedModule = seedModule == null ? SEED_NONE : seedModule.trim();
        this.catalog = catalog;
    }

    /** Two-arg form for a classpath-only seed (a bundled world, or a test). */
    WorldSeeder(WorldModel world, String seedModule) {
        this(world, seedModule, new WorldCatalog(""));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedModule.isEmpty() || SEED_NONE.equalsIgnoreCase(seedModule)) {
            return; // empty world, exactly as the tests expect
        }
        // A stock world jar in the configured worlds directory wins; otherwise the
        // seed names a bundled classpath module (the outpatient clinic).
        Optional<Path> jar = catalog.worldJar(seedModule);
        if (jar.isPresent()) {
            seedJar(jar.get(), seedModule);
        } else {
            seed(new ManifestClasspathWorldStore(), seedModule);
        }
    }

    /**
     * Seed a world from its module jar through {@link JarWorldStore}. A jar that
     * cannot be opened (corrupt, removed mid-boot) degrades to an empty server with
     * a warning, exactly like a world that cannot be read -- the never-throw
     * contract the class header promises, so {@link IOException} is caught here
     * rather than escaping to abort the boot.
     */
    private void seedJar(Path jarFile, String module) {
        try (JarWorldStore store = new JarWorldStore(jarFile)) {
            seed(store, module);
        } catch (IOException ex) {
            LOG.warn("world jar {} for '{}' could not be opened; serving an empty world ({})",
                    jarFile.getFileName(), module, ex.toString());
        }
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
        ModelCellComponentServerState model = (ModelCellComponentServerState) state.getComponentServerState(
                ModelCellComponentServerState.class);
        boolean isModel = model != null;
        String className = isModel ? MODEL_CELL_CLASS : WorldModel.ENVIRONMENT_CLASS;
        // The wla:// model URL is carried on BOTH surfaces: the WorldCell field
        // feeds the read-only REST descriptor + HTTP asset endpoint, while a
        // ModelCellClientState folds it into the shared wire format so a client
        // walking in over the socket receives it in the LOAD_CELL message (the
        // renderer's WonderlandModelCell reads it back in setClientState()).
        String modelURL = isModel ? model.getDeployedModelURL() : null;
        CellClientState clientState;
        if (isModel) {
            ModelCellClientState modelState = new ModelCellClientState();
            modelState.setDeployedModelURL(modelURL);
            clientState = modelState;
        } else {
            clientState = new CellClientState();
        }
        world.addCell(world.newCellID(), className, state.getName(),
                transformOf(state), clientState, modelURL);
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
