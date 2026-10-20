/*
 * wonderland-server (world seeding) unit tests: the WorldSeeder is the last mile
 * that turns a classpath world module into authoritative WorldModel cells. These
 * prove the three behaviours that matter and that the M3b socket test depends on:
 *   - OFF by default (seed=none) leaves the world as just the Environment root,
 *     so an empty server (and the socket fan-out assertion) is unaffected;
 *   - seeding the shipped outpatient-clinic module populates the hierarchy with
 *     every named cell the directory sign lists, each parented to the environment;
 *   - a world that cannot load degrades to an empty server rather than throwing,
 *     because a bad world must never stop the server from booting.
 * Headless: WorldModel and the loader are pure; no Spring context, no sockets.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.math.Vector3f;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.AvatarCellServerState;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellClientState;
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
import org.jdesktop.wonderland.common.cell.view.ViewCellClientState;
import org.jdesktop.wonderland.modules.asset.WorldContents;
import org.jdesktop.wonderland.modules.asset.WorldStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WorldSeederTest {

    private static final String CLINIC = "wonderland-outpatient-clinic";

    private static List<String> names(WorldModel world) {
        return world.hierarchy().stream().map(WorldCell::getName).toList();
    }

    /** The one cell seeded under {@code name}, or fail if absent. */
    private static WorldCell only(WorldModel world, String name) {
        return world.hierarchy().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell named " + name));
    }

    @Test
    void seedingIsOffByDefaultAndLeavesAnEmptyWorld() {
        WorldModel world = new WorldModel();
        new WorldSeeder(world, WorldSeeder.SEED_NONE).run(null);

        assertThat(world.hierarchy())
                .as("no seed -> only the environment root")
                .extracting(WorldCell::getName)
                .containsExactly("Environment");
    }

    @Test
    void anEmptyOrNullSeedIsTreatedAsNoSeed() {
        // the constructor nulls an absent module to the sentinel, and an empty
        // string short-circuits run() -- both leave the world untouched
        WorldModel empty = new WorldModel();
        new WorldSeeder(empty, "").run(null);
        assertThat(names(empty)).containsExactly("Environment");

        WorldModel absent = new WorldModel();
        new WorldSeeder(absent, null).run(null);
        assertThat(names(absent)).containsExactly("Environment");
    }

    @Test
    void seedingTheClinicPopulatesEveryNamedCell() {
        WorldModel world = new WorldModel();
        new WorldSeeder(world, CLINIC).run(null);

        assertThat(names(world)).contains(
                "Floor",
                "Exam Room 1", "Exam Room 1 Waiting",
                "Exam Room 2", "Exam Room 2 Waiting",
                "Patient Education", "Observation Room",
                "Reception", "Clinic Directory",
                // the hall, the three sofas and the residents the world grew
                // to read like the 2009 lobby rather than a strip of empty rooms
                "Outpatient Clinic Hall", "Waiting Sofa A", "Waiting Sofa B",
                "Observation Sofa", "Receptionist", "Waiting Patient",
                // the wall art: GUI cells, seeded as placed cells like any other
                "Health Notices", "Clinic Window", "Framed Picture");
        // environment + the twenty-four authored cells
        assertThat(world.hierarchy()).hasSize(25);
        // every seeded cell is a child of the environment root
        assertThat(world.hierarchy())
                .filteredOn(c -> !"Environment".equals(c.getName()))
                .allMatch(c -> c.getParentID().equals(world.getEnvironmentCellID()));
    }

    /**
     * A successful seed is also a claim of hosting: the world records which
     * module it now runs, and the REST catalog marks exactly that entry
     * {@code hosted} -- the fact a browser needs to tell "the world I picked
     * is live" from "some world is live". A seed that never ran (none) or
     * failed (an unloadable world) must not leave the mark set.
     */
    @Test
    void aSeededWorldRecordsTheModuleItHosts() {
        WorldModel seeded = new WorldModel();
        new WorldSeeder(seeded, CLINIC).run(null);
        assertThat(seeded.getLiveModule()).isEqualTo(CLINIC);

        WorldModel unseeded = new WorldModel();
        new WorldSeeder(unseeded, WorldSeeder.SEED_NONE).run(null);
        assertThat(unseeded.getLiveModule())
                .as("no seed -> no hosting claim")
                .isNull();
    }

    @Test
    void aModelCellCarriesTheRendererClassNameAsInertData() {
        WorldModel world = new WorldModel();
        new WorldSeeder(world, CLINIC).run(null);

        assertThat(world.hierarchy())
                .filteredOn(c -> "Exam Room 1".equals(c.getName()))
                .allMatch(c -> WorldSeeder.MODEL_CELL_CLASS.equals(c.getClassName()));
    }

    @Test
    void anUnloadableWorldDegradesToAnEmptyServer() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, "no-such-world-module");

        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();
        assertThat(names(world)).containsExactly("Environment");
    }

    // ---- branch-level seams: the decisions seedCell/transformOf/seed make are
    // ---- proved directly with synthetic states so a store is not in the way.

    @Test
    void aMissingOrUnnamedCellIsSkipped() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        assertThat(seeder.seedCell(null)).isFalse();

        CellServerState nameless = new ModelCellServerState();
        nameless.setName(null);
        assertThat(seeder.seedCell(nameless)).isFalse();

        CellServerState blank = new ModelCellServerState();
        blank.setName("");
        assertThat(seeder.seedCell(blank)).isFalse();

        assertThat(names(world)).containsExactly("Environment");
    }

    @Test
    void aCellCarryingTheModelComponentSeedsAsTheRendererClass() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        CellServerState model = new ModelCellServerState();
        model.setName("Exam Room 9");
        model.addComponentServerState(new ModelCellComponentServerState());

        assertThat(seeder.seedCell(model)).isTrue();
        assertThat(only(world, "Exam Room 9").getClassName())
                .isEqualTo(WorldSeeder.MODEL_CELL_CLASS);
    }

    @Test
    void aModelCellCapturesItsDeployedModelUrlServerSide() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        ModelCellComponentServerState component = new ModelCellComponentServerState();
        component.setDeployedModelURL("wla://" + CLINIC + "/exam-room.kmz/exam-room.dep");
        CellServerState model = new ModelCellServerState();
        model.setName("Exam Room 9");
        model.addComponentServerState(component);

        seeder.seedCell(model);
        assertThat(only(world, "Exam Room 9").getDeployedModelURL())
                .as("the wla:// url is stored on the cell for REST + the asset endpoint")
                .isEqualTo("wla://" + CLINIC + "/exam-room.kmz/exam-room.dep");
        // ... and folded into the shared wire format: the client state a model
        // cell carries is a ModelCellClientState holding the same URL, so the
        // LOAD_CELL the router pushes delivers it to a walking-in client.
        assertThat(only(world, "Exam Room 9").getClientState())
                .isInstanceOfSatisfying(ModelCellClientState.class,
                        ms -> assertThat(ms.getDeployedModelURL())
                                .isEqualTo("wla://" + CLINIC + "/exam-room.kmz/exam-room.dep"));
    }

    @Test
    void aNonModelCellCarriesNoDeployedModelUrl() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        CellServerState avatar = new AvatarCellServerState("Dr. Alan");
        avatar.setName("Avatar");
        seeder.seedCell(avatar);

        assertThat(only(world, "Avatar").getDeployedModelURL()).isNull();
        // a non-model cell keeps a plain client state -- the wire does not gain a
        // model URL for a cell that has no model
        assertThat(only(world, "Avatar").getClientState())
                .isNotInstanceOf(ModelCellClientState.class);
    }

    @Test
    void anAvatarCellSeedsAsTheAvatarClass() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        CellServerState avatar = new AvatarCellServerState("Dr. Alan");
        avatar.setName("Avatar");

        assertThat(seeder.seedCell(avatar)).isTrue();
        // a resident is walked and shown as a body with a name over it, not as
        // inert environment geometry -- this is what puts people in the lobby
        assertThat(only(world, "Avatar").getClassName())
                .isEqualTo(WorldSeeder.AVATAR_CELL_CLASS);
        // ... and the wire state must be the type the client's ViewCell cast
        // demands. Before this the non-model branch gave a plain CellClientState,
        // so a walking-in client loading the AvatarCell threw ClassCastException
        // in loadCell -> a SEVERE the desktop surfaced as its crash dialog.
        assertThat(only(world, "Avatar").getClientState())
                .isInstanceOf(ViewCellClientState.class);
    }

    @Test
    void transformIsLiftedFromThePositionComponentWhenPresent() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        PositionComponentServerState position = new PositionComponentServerState();
        position.setTranslation(new Vector3f(1f, 2f, 3f));
        CellServerState cell = new ModelCellServerState();
        cell.setName("Placed");
        cell.addComponentServerState(position);

        seeder.seedCell(cell);
        Vector3f translation = only(world, "Placed").getTransform()
                .getTranslation(null);
        assertThat(translation).isEqualTo(new Vector3f(1f, 2f, 3f));
    }

    @Test
    void transformFallsBackToIdentityWithoutAPositionComponent() {
        CellServerState cell = new ModelCellServerState();
        cell.setName("Floating");

        CellTransform transform = WorldSeeder.transformOf(cell);
        assertThat(transform.getTranslation(null))
                .isEqualTo(new Vector3f(0f, 0f, 0f));
        assertThat(transform.getScaling()).isEqualTo(1f);
    }

    @Test
    void aStoreThatThrowsDegradesToAnEmptyWorld() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        WorldStore broken = new WorldStore() {
            public Optional<InputStream> open(String module, String path) {
                throw new IllegalStateException("store is down");
            }
            public boolean present(String module, String path) {
                throw new IllegalStateException("store is down");
            }
            public List<String> listAssets(String module, String suffix) {
                throw new IllegalStateException("store is down");
            }
        };

        assertThat(seeder.seed(broken, CLINIC))
                .as("a throwing store yields zero seeded cells, not a crash")
                .isZero();
        assertThat(names(world)).containsExactly("Environment");
    }

    @Test
    void seedingFromAWorldJarInTheDirectoryPopulatesTheWorld(@TempDir Path dir)
            throws IOException {
        Path jar = dir.resolve("wonderland-town-world.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "module.xml",
                    "<wonderland-module><name>wonderland-town-world</name></wonderland-module>");
            put(out, "wfs/town-wfs/room-wlc.xml",
                    "<model-cell><name>Room</name></model-cell>");
        }

        WorldModel world = new WorldModel();
        new WorldSeeder(world, "wonderland-town-world", new WorldCatalog(dir.toString()))
                .run(null);

        assertThat(names(world)).containsExactly("Environment", "Room");
    }

    @Test
    void aCorruptInstalledWorldJarFallsBackToTheClasspathSeed(@TempDir Path dir)
            throws IOException {
        // A file in the worlds directory that ends in .jar but is not a real zip
        // makes openAllStores throw when it opens each jar. run() must not abort
        // the boot over an unrelated broken jar: it catches the IOException and
        // seeds the bundled clinic from the classpath store instead.
        Path bad = dir.resolve("wonderland-broken-world.jar");
        Files.write(bad, "this is not a zip file".getBytes(StandardCharsets.UTF_8));

        WorldModel world = new WorldModel();
        assertThatCode(() -> new WorldSeeder(world, CLINIC,
                new WorldCatalog(dir.toString())).run(null)).doesNotThrowAnyException();

        assertThat(names(world)).contains("Floor", "Reception");
        assertThat(world.getLiveModule()).isEqualTo(CLINIC);
    }

    @Test
    void seedContentsSkipsAnUnnamedCellButKeepsNamedOnes() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        CellServerState named = new ModelCellServerState();
        named.setName("Kept");
        CellServerState nameless = new ModelCellServerState();
        nameless.setName("");
        WorldContents contents = new WorldContents("mod", List.of(
                new WorldContents.LoadedCell(named, null),
                new WorldContents.LoadedCell(nameless, null)));

        assertThat(seeder.seedContents(contents))
                .as("only the named cell counts as seeded")
                .isEqualTo(1);
        assertThat(names(world)).containsExactly("Environment", "Kept");
    }

    private static void put(JarOutputStream jar, String name, String body) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(body.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
