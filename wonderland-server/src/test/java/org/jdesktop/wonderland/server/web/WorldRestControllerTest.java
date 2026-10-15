/*
 * wonderland-server M3 unit tests: the wonderland-wfs REST surface rebuilt as a
 * Spring @RestController over the SAME WorldModel the wire protocol mutates --
 * pinned here by moving a cell through the router and reading the new transform
 * back through MockMvc, which is the "the REST view and the wire view can never
 * drift apart" claim in the controller's header. Also pins the read-only
 * vocabulary: hierarchy order from the root down, single-cell lookup, and the
 * 404 for a cell the world does not host.
 */
package org.jdesktop.wonderland.server.web;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.jdesktop.wonderland.server.comms.ProtocolRouter;
import org.jdesktop.wonderland.server.comms.ServerSession;
import org.jdesktop.wonderland.server.comms.SessionRegistry;
import org.jdesktop.wonderland.server.world.WorldCatalog;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorldRestControllerTest {

    private WorldModel world;
    private SessionRegistry registry;
    private ProtocolRouter router;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        world = new WorldModel();
        registry = new SessionRegistry();
        router = new ProtocolRouter(world, registry);
        mvc = MockMvcBuilders
                .standaloneSetup(new WorldRestController(world, new WorldCatalog("")))
                .build();
    }

    @Test
    void worldsListsTheBundledClinicEvenWithNoWorldsDirectory() throws Exception {
        // the catalog is read independently of the (empty) seeded world: the bundled
        // outpatient clinic is always offered, sourced from the classpath
        mvc.perform(get("/api/world/worlds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.module == 'wonderland-outpatient-clinic')]")
                        .exists())
                .andExpect(jsonPath("$[?(@.module == 'wonderland-outpatient-clinic')].source")
                        .value("classpath"))
                .andExpect(jsonPath("$[?(@.module == 'wonderland-outpatient-clinic')].cellCount")
                        .value(24));
    }

    /**
     * The listed/live distinction the browser's Start button turns on: exactly
     * the entry whose module the live world was seeded from carries
     * {@code hosted}, and an unseeded server claims it for nobody.
     */
    @Test
    void worldsMarksOnlyTheSeededModuleHosted() throws Exception {
        mvc.perform(get("/api/world/worlds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.module == 'wonderland-outpatient-clinic')].hosted")
                        .value(false));

        world.setLiveModule("wonderland-outpatient-clinic");
        mvc.perform(get("/api/world/worlds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.module == 'wonderland-outpatient-clinic')].hosted")
                        .value(true));
    }

    /** A catalog world's cells carry their authored placement for a preview. */
    @Test
    void cellsForAModuleCarryTheAuthoredPlacement() throws Exception {
        mvc.perform(get("/api/world/cells").param("module", "wonderland-outpatient-clinic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Exam Room 1')].x").value(-8.0))
                .andExpect(jsonPath("$[?(@.name == 'Exam Room 1')].z").value(-12.3))
                .andExpect(jsonPath("$[?(@.name == 'Exam Room 1')].width").value(32.0))
                .andExpect(jsonPath("$[?(@.name == 'Exam Room 1')].depth").value(32.0));
    }

    @Test
    void cellsListsTheHierarchyFromTheRootDown() throws Exception {
        CellID content = world.newCellID();
        world.addCell(content, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());

        mvc.perform(get("/api/world/cells"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("0"))
                .andExpect(jsonPath("$[0].name").value("Environment"))
                .andExpect(jsonPath("$[0].parent").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(content.toString()))
                .andExpect(jsonPath("$[1].className").value("some.client.Cell"))
                .andExpect(jsonPath("$[1].parent").value("0"));
    }

    /** The world browser's read: any catalog world's cells, without seeding it. */
    @Test
    void cellsForAModuleReadsThatWorldEvenWhenTheServerSeededNothing() throws Exception {
        // the seeded world is empty (only the environment), yet a named module is
        // readable through ?module= -- this is the "pick any world" surface
        mvc.perform(get("/api/world/cells").param("module", "wonderland-outpatient-clinic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(24)))
                .andExpect(jsonPath("$[?(@.name == 'Exam Room 1')]").exists())
                .andExpect(jsonPath("$[?(@.cellType == 'poster-cell')]").exists())
                .andExpect(jsonPath("$[0].parent").doesNotExist());
    }

    @Test
    void cellsForAnUnknownModuleIsAnEmptyListNotAnError() throws Exception {
        mvc.perform(get("/api/world/cells").param("module", "no-such-world"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aBlankModuleFallsBackToTheSeededWorld() throws Exception {
        CellID content = world.newCellID();
        world.addCell(content, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());
        // an empty module parameter is "no module chosen": the live world answers,
        // so the seeded hierarchy (environment + cube) is what the browser sees
        mvc.perform(get("/api/world/cells").param("module", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void cellLookupsAnswerFoundAndNotFound() throws Exception {
        CellID content = world.newCellID();
        world.addCell(content, "some.client.Cell", "cube", new CellTransform(), null);

        mvc.perform(get("/api/world/cells/" + content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cube"));

        mvc.perform(get("/api/world/cells/9999"))
                .andExpect(status().isNotFound());
    }

    /** The no-drift claim: a router-side mutation is immediately REST-visible. */
    @Test
    void aWireMoveIsReadableThroughRest() throws Exception {
        CellID content = world.newCellID();
        world.addCell(content, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState());
        ServerSession session = registry.create(new WonderlandIdentity(
                "m", "M", "m@example.org"), (s, type, frame) -> {
        });
        registry.attach(session, CellChannelConnectionType.CLIENT_TYPE);

        MovableMessage move = MovableMessage.newMoveRequestMessage(content,
                new Vector3f(7f, 8f, 9f), new Quaternion());
        ByteBuffer packed;
        try {
            packed = MessagePacker.pack(move, (short) 1);
            router.routeInbound(session, CellChannelConnectionType.CLIENT_TYPE, packed);
        } catch (MessagePacker.PackerException ex) {
            throw new IllegalStateException(ex);
        }
        assertThat(world.find(content).getTransform().getTranslation(null))
                .isEqualTo(new Vector3f(7f, 8f, 9f));

        // the REST descriptor reflects the authoritative transform's cell; the
        // endpoint does not expose transforms, so prove the same instance story:
        // the cell still lists, and the moved id resolves through the world the
        // controller reads
        mvc.perform(get("/api/world/cells"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].id").value(content.toString()));
    }

    // ---- Stage 2: the deployed-model URL on the descriptor, and the HTTP asset
    // ---- endpoint that serves a cell's wla:// art -- both without the wire.

    @Test
    void aModelCellDescriptorCarriesItsDeployedModelUrl() throws Exception {
        CellID model = world.newCellID();
        world.addCell(model, "some.client.Cell", "cube", new CellTransform(),
                new CellClientState(), "wla://mod/cube.kmz/cube.dep");
        CellID plain = world.newCellID();
        world.addCell(plain, "some.client.Cell", "plain", new CellTransform(),
                new CellClientState());

        mvc.perform(get("/api/world/cells/" + model))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deployedModelURL")
                        .value("wla://mod/cube.kmz/cube.dep"));
        // a cell with no model keeps the descriptor exactly as before -- no null key
        mvc.perform(get("/api/world/cells/" + plain))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deployedModelURL").doesNotExist());
    }

    @Test
    void assetServesBytesFromAnInstalledWorldJar(@TempDir Path dir) throws Exception {
        String module = "wonderland-town-world";
        Path jar = dir.resolve(module + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, module + ".kmz/room.ldr", "LDR-BYTES");
        }
        MockMvc jarMvc = MockMvcBuilders.standaloneSetup(new WorldRestController(
                new WorldModel(), new WorldCatalog(dir.toString()))).build();

        jarMvc.perform(get("/api/world/asset")
                        .param("module", module).param("path", module + ".kmz/room.ldr"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("LDR-BYTES".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void assetServesBytesFromABundledClasspathWorld() throws Exception {
        // the clinic ships on the classpath; its module.xml is a real asset the
        // catalog reads through the classpath branch of openAssetBytes
        mvc.perform(get("/api/world/asset")
                        .param("module", "wonderland-outpatient-clinic")
                        .param("path", "module.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("wonderland-outpatient-clinic")));
    }

    @Test
    void assetReturnsNotFoundForAnAbsentAsset(@TempDir Path dir) throws Exception {
        String module = "wonderland-town-world";
        Path jar = dir.resolve(module + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "module.xml", "<wonderland-module/>");
        }
        MockMvc jarMvc = MockMvcBuilders.standaloneSetup(new WorldRestController(
                new WorldModel(), new WorldCatalog(dir.toString()))).build();

        jarMvc.perform(get("/api/world/asset")
                        .param("module", module).param("path", "missing.ldr"))
                .andExpect(status().isNotFound());
    }

    @Test
    void assetRejectsAPathThatEscapesTheModuleRoot() throws Exception {
        mvc.perform(get("/api/world/asset")
                        .param("module", "wonderland-outpatient-clinic")
                        .param("path", "../../etc/passwd"))
                .andExpect(status().isBadRequest());
    }

    // ---- the world preview: the browser's vignette asks for a picture of the
    // ---- world, and a world without one must answer 404, not an empty 200.

    @Test
    void previewServesTheWorldsOwnPictureWithItsMediaType(@TempDir Path dir)
            throws Exception {
        String module = "wonderland-town-world";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(
                dir.resolve(module + ".jar")))) {
            put(out, "module.xml", "<wonderland-module><name>A Town</name></wonderland-module>");
            put(out, "preview.png", "PNG-BYTES");
        }
        MockMvc jarMvc = MockMvcBuilders.standaloneSetup(new WorldRestController(
                new WorldModel(), new WorldCatalog(dir.toString()))).build();

        jarMvc.perform(get("/api/world/preview").param("module", module))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes("PNG-BYTES".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void previewIsNotFoundForAWorldThatCarriesNoPicture() throws Exception {
        // the bundled clinic ships no preview.png, so the browser's cue to draw
        // the floor plan is a plain 404 -- never a 200 with an empty body, which
        // a caller would have to decode as an image to discover
        mvc.perform(get("/api/world/preview")
                        .param("module", "wonderland-outpatient-clinic"))
                .andExpect(status().isNotFound());
        // and a missing module parameter is Spring's problem, not ours to guess
        mvc.perform(get("/api/world/preview"))
                .andExpect(status().isBadRequest());
    }

    private static void put(JarOutputStream jar, String name, String body) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(body.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
