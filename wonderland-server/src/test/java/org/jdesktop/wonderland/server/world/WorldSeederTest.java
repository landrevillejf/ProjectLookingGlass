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
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
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
                "Reception", "Clinic Directory");
        // environment + the nine authored cells
        assertThat(world.hierarchy()).hasSize(10);
        // every seeded cell is a child of the environment root
        assertThat(world.hierarchy())
                .filteredOn(c -> !"Environment".equals(c.getName()))
                .allMatch(c -> c.getParentID().equals(world.getEnvironmentCellID()));
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
    void aCellWithoutTheModelComponentSeedsAsTheEnvironmentClass() {
        WorldModel world = new WorldModel();
        WorldSeeder seeder = new WorldSeeder(world, CLINIC);

        CellServerState avatar = new AvatarCellServerState("Dr. Alan");
        avatar.setName("Avatar");

        assertThat(seeder.seedCell(avatar)).isTrue();
        assertThat(only(world, "Avatar").getClassName())
                .isEqualTo(WorldModel.ENVIRONMENT_CLASS);
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
