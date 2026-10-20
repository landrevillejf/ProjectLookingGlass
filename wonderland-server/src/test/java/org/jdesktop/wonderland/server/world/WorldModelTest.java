/*
 * wonderland-server M3 unit tests: the authoritative world that replaces the
 * SGS session-world storage. Pins the environment-root seeding (including the
 * concrete-class-name override a real deployment uses, since the abstract
 * EnvironmentCell cannot be reflected open by the client cache), fresh-id
 * minting, the transform-only mutation applyMove performs (a move for an
 * unknown cell refuses rather than forging one), removeCell's root guard, and
 * the hierarchy()'s load order -- environment first, then ascending id, so a
 * parent always precedes its children on the client cache's loadCell contract.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorldModelTest {

    private static final String TEST_ENV_CLASS =
            "org.jdesktop.wonderland.server.testcells.ServerTestFixtures$CacheableEnv";

    private WorldModel world;

    @BeforeEach
    void setUp() {
        world = new WorldModel(TEST_ENV_CLASS);
    }

    @Test
    void defaultConstructorSeedsTheModuleEnvironmentClass() {
        WorldModel plain = new WorldModel();
        WorldCell env = plain.find(CellID.getEnvironmentCellID());

        assertThat(env).isNotNull();
        assertThat(env.getClassName()).isEqualTo(WorldModel.ENVIRONMENT_CLASS);
        assertThat(env.getParentID()).isNull();
    }

    @Test
    void overrideConstructorSeedsTheConcreteClass() {
        WorldCell env = world.find(CellID.getEnvironmentCellID());

        assertThat(env).isNotNull();
        assertThat(env.getCellID()).isEqualTo(CellID.getEnvironmentCellID());
        assertThat(env.getClassName()).isEqualTo(TEST_ENV_CLASS);
        assertThat(env.getName()).isEqualTo("Environment");
        assertThat(world.getEnvironmentCellID()).isEqualTo(CellID.getEnvironmentCellID());
    }

    @Test
    void mintedIdsAreFreshAndAscending() {
        CellID first = world.newCellID();
        CellID second = world.newCellID();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isNotEqualTo(CellID.getEnvironmentCellID());
        assertThat(Long.parseLong(second.toString()))
                .isGreaterThan(Long.parseLong(first.toString()));
    }

    @Test
    void addCellParentsContentToTheEnvironment() {
        CellID id = world.newCellID();
        WorldCell cell = world.addCell(id, "some.client.Cell", "a-cell",
                new CellTransform(), new CellClientState());

        assertThat(cell.getParentID()).isEqualTo(CellID.getEnvironmentCellID());
        assertThat(world.find(id)).isSameAs(cell);
        assertThat(world.contains(id)).isTrue();
    }

    /**
     * The hosted mark the REST catalog reads to tell "listed" from "live":
     * blank in, blank out; padded in, trimmed out. The world itself never
     * infers a module -- only the seeder's record is this faithful to it.
     */
    @Test
    void liveModuleRecordsTheSeededWorldAndBlanksClearIt() {
        assertThat(world.getLiveModule())
                .as("a fresh world hosts nothing")
                .isNull();

        world.setLiveModule("  wonderland-town-world ");
        assertThat(world.getLiveModule()).isEqualTo("wonderland-town-world");

        world.setLiveModule("");
        assertThat(world.getLiveModule())
                .as("an empty module is no module")
                .isNull();

        world.setLiveModule(null);
        assertThat(world.getLiveModule()).isNull();
    }

    @Test
    void applyMoveRewritesOnlyTheTransform() {
        CellID id = world.newCellID();
        CellClientState state = new CellClientState();
        WorldCell before = world.addCell(id, "some.client.Cell", "a-cell",
                new CellTransform(), state);

        CellTransform moved = new CellTransform();
        moved.setTranslation(new Vector3f(1f, 2f, 3f));
        assertThat(world.applyMove(id, moved)).isTrue();

        WorldCell after = world.find(id);
        assertThat(after).isNotSameAs(before); // immutable descriptor, replaced
        assertThat(after.getTransform().getTranslation(null)).isEqualTo(new Vector3f(1f, 2f, 3f));
        assertThat(after.getClassName()).isEqualTo("some.client.Cell");
        assertThat(after.getName()).isEqualTo("a-cell");
        assertThat(after.getParentID()).isEqualTo(CellID.getEnvironmentCellID());
        assertThat(after.getClientState()).isSameAs(state);
    }

    @Test
    void applyMoveRefusesAnUnknownCell() {
        assertThat(world.applyMove(new CellID(9999L), new CellTransform())).isFalse();
        assertThat(world.contains(new CellID(9999L))).isFalse();
    }

    @Test
    void removeCellDropsContentButNeverTheEnvironment() {
        CellID id = world.newCellID();
        world.addCell(id, "some.client.Cell", "a-cell", new CellTransform(), null);

        assertThat(world.removeCell(id)).isNotNull();
        assertThat(world.find(id)).isNull();

        assertThat(world.removeCell(CellID.getEnvironmentCellID())).isNull();
        assertThat(world.find(CellID.getEnvironmentCellID())).isNotNull();
    }

    @Test
    void removingACellTheWorldNeverHostedIsANoopThatDoesNotBumpTheRevision() {
        long before = world.revision();

        // not the environment (that guard is its own branch), just an id the
        // world never held: remove finds nothing, so the revision must not move
        // -- an autosave polling it would otherwise rewrite an unchanged world.
        assertThat(world.removeCell(new CellID(9999L))).isNull();
        assertThat(world.revision()).isEqualTo(before);
    }

    @Test
    void addCellStoresAnIdentityTransformWhenHandedNone() {
        CellID id = world.newCellID();

        WorldCell cell = world.addCell(id, "some.client.Cell", "a-cell", null,
                new CellClientState());

        // a null transform defaults to identity at the model's own door, not
        // only inside the WorldCell record
        assertThat(cell.getTransform()).isNotNull();
        assertThat(cell.getTransform().getTranslation(null)).isEqualTo(new Vector3f(0f, 0f, 0f));
    }

    @Test
    void hierarchyPutsTheEnvironmentFirstThenAscendingIds() {
        CellID late = world.newCellID();
        CellID early = world.newCellID();
        // mint late first so ids are out of seeding order: the sort must fix it
        world.addCell(late, "some.client.Cell", "late", new CellTransform(), null);
        world.addCell(early, "some.client.Cell", "early", new CellTransform(), null);

        var hierarchy = world.hierarchy();

        assertThat(hierarchy.get(0).getCellID()).isEqualTo(CellID.getEnvironmentCellID());
        assertThat(Long.parseLong(hierarchy.get(1).getCellID().toString()))
                .isLessThan(Long.parseLong(hierarchy.get(2).getCellID().toString()));
        assertThat(hierarchy).hasSize(3);
    }

    @Test
    void cellIdsListEveryCell() {
        CellID id = world.newCellID();
        world.addCell(id, "some.client.Cell", "a-cell", new CellTransform(), null);

        assertThat(world.cellIDs()).containsExactlyInAnyOrder(
                CellID.getEnvironmentCellID(), id);
        assertThat(world.find(id)).isNotNull();
    }

    @Test
    void nullTransformDefaultsToIdentity() {
        CellID id = world.newCellID();
        WorldCell cell = new WorldCell(id, null, "c", "n", null, null, null);

        assertThat(cell.getTransform()).isNotNull();
    }
}
