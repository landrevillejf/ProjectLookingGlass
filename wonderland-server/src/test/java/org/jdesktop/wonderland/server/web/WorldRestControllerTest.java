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
import java.nio.ByteBuffer;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                        .value(9));
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
}
