/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: headless tests for the HUD's join.
 *
 * The model merges what the world publishes with what the session actually
 * mounted. Both halves are available headless -- a fixture inventory and a
 * CellFrameBridge driven through a no-op FrameMounter -- so the awkward cases
 * (a mounted cell nobody published, a parent chain that loops, a cell with no
 * name) are asserted in CI rather than looked for in a screenshot.
 */
package org.jdesktop.wonderland.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link WorldHudModel}: the inventory/mount join, the label
 * fallbacks, the depth walk and the summary line.
 */
class WorldHudModelTest {

    /** The bridge never reaches the scene graph here; the mounter is a stand-in. */
    private static final FrameMounter NO_OP_MOUNTER = new FrameMounter() {
        @Override
        public void mount(String cellId, CellRenderer renderer) {
        }

        @Override
        public void unmount(String cellId) {
        }

        @Override
        public void applyTransform(String cellId, double[] rowMajorFeed) {
        }

        @Override
        public void setVisible(String cellId, boolean visible) {
        }
    };

    /** The renderer is only ever tested for null by the bridge. */
    private static final CellRenderer STUB_RENDERER = new CellRenderer() {
        @Override
        public void cellTransformUpdate(CellTransform transform) {
        }

        @Override
        public void setStatus(CellStatus status, boolean increasing) {
        }

        @Override
        public CellStatus getStatus() {
            return null;
        }
    };

    /** An inventory read straight off a fixture list, in publication order. */
    private static final class FixtureInventory implements WorldInventory {
        private final List<Cell> cells;

        FixtureInventory(Cell... cells) {
            this(Arrays.asList(cells));
        }

        FixtureInventory(List<Cell> cells) {
            this.cells = cells;
        }

        @Override
        public List<Cell> cells() {
            return cells;
        }
    }

    private CellFrameBridge bridge;
    private WorldHudModel model;

    @BeforeEach
    void setUp() {
        bridge = new CellFrameBridge(NO_OP_MOUNTER);
        model = new WorldHudModel(bridge);
    }

    private static WorldInventory.Cell cell(String id, String parentId,
            String className, String name) {
        return new WorldInventory.Cell(id, parentId, className, name);
    }

    @Test
    void nullBridgeIsRejected() {
        assertThatThrownBy(() -> new WorldHudModel(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutAnInventoryTheHudShowsWhatIsMounted() {
        bridge.mountCell("A", STUB_RENDERER);

        assertThat(model.getInventory()).isNull();
        assertThat(model.rows()).singleElement().satisfies(row -> {
            assertThat(row.cellId()).isEqualTo("A");
            assertThat(row.label()).isEqualTo("A");
            assertThat(row.className()).isNull();
            assertThat(row.depth()).isZero();
            assertThat(row.state()).isEqualTo(WorldHudModel.CellState.MOUNTED);
        });
    }

    @Test
    void anInventoryThatReportsNullIsReadAsEmpty() {
        model.setInventory(new FixtureInventory((List<WorldInventory.Cell>) null));

        assertThat(model.rows()).isEmpty();
        assertThat(model.summary()).isEqualTo("0 cells: 0 shown, 0 hidden, 0 pending");
    }

    @Test
    void rowsFollowPublicationOrderNotHashOrder() {
        model.setInventory(new FixtureInventory(
                cell("z", null, "Root", "Zulu"),
                cell("m", "z", "Group", "Mike"),
                cell("a", "m", "Group", "Alpha")));

        assertThat(model.rows()).extracting(WorldHudModel.Row::cellId)
                .containsExactly("z", "m", "a");
        assertThat(model.rows()).extracting(WorldHudModel.Row::depth)
                .containsExactly(0, 1, 2);
    }

    @Test
    void theThreeStatesComeOutOfTheBridge() {
        model.setInventory(new FixtureInventory(
                cell("shown", null, "Group", "Shown"),
                cell("hidden", null, "Group", "Hidden"),
                cell("pending", null, "Group", "Pending")));
        bridge.mountCell("shown", STUB_RENDERER);
        bridge.mountCell("hidden", STUB_RENDERER);
        bridge.setCellVisible("hidden", false);

        assertThat(states()).containsExactly(WorldHudModel.CellState.MOUNTED,
                WorldHudModel.CellState.HIDDEN,
                WorldHudModel.CellState.PENDING);
        assertThat(model.count(WorldHudModel.CellState.MOUNTED)).isEqualTo(1);
        assertThat(model.count(WorldHudModel.CellState.HIDDEN)).isEqualTo(1);
        assertThat(model.count(WorldHudModel.CellState.PENDING)).isEqualTo(1);
    }

    @Test
    void aMountedCellTheWorldNeverPublishedStillAppears() {
        model.setInventory(new FixtureInventory(
                cell("published", null, "Group", "Published")));
        bridge.mountCell("stray", STUB_RENDERER);
        bridge.setCellVisible("stray", false);

        assertThat(model.rows()).extracting(WorldHudModel.Row::cellId)
                .containsExactly("published", "stray");
        // The inventory has no name or class for it, so the id is the honest
        // label; the scene-graph state is still the bridge's, not a made-up one.
        WorldHudModel.Row stray = model.rows().get(1);
        assertThat(stray.label()).isEqualTo("stray");
        assertThat(stray.className()).isNull();
        assertThat(stray.depth()).isZero();
        assertThat(stray.state()).isEqualTo(WorldHudModel.CellState.HIDDEN);
    }

    @Test
    void theLabelFallsBackFromNameToClassToId() {
        model.setInventory(new FixtureInventory(
                cell("named", null, "Group", "  Spaced  "),
                cell("blank", null, "Group", "   "),
                cell("classy", null, "Group", null),
                cell("bare", null, null, null)));

        assertThat(model.rows()).extracting(WorldHudModel.Row::label)
                .containsExactly("Spaced", "Group", "Group", "bare");
    }

    @Test
    void aBlankClassNameFallsThroughToTheIdToo() {
        // The class arm has two ways to fail: className null (covered by "bare"
        // above) and className present but blank. A blank class is not a label,
        // so the id is the only honest one -- this is labelFor()'s isBlank() side
        // on the className arm.
        model.setInventory(new FixtureInventory(
                cell("blankclass", null, "   ", null)));

        assertThat(model.rows()).extracting(WorldHudModel.Row::label)
                .containsExactly("blankclass");
    }

    @Test
    void aParentCycleTerminatesInsteadOfHanging() {
        model.setInventory(new FixtureInventory(
                cell("self", "self", "Group", "Self parent"),
                cell("x", "y", "Group", "X"),
                cell("y", "x", "Group", "Y")));

        // A cell that contains itself has no ancestor that is not itself, so it
        // reads as a root; the two-cell ring is capped at one step instead of
        // walking forever.
        assertThat(model.rows()).extracting(WorldHudModel.Row::depth)
                .containsExactly(0, 1, 1);
    }

    @Test
    void aParentOutsideTheInventoryStillCountsAsDepthOne() {
        model.setInventory(new FixtureInventory(
                cell("orphan", "gone", "Group", "Orphan")));

        assertThat(model.rows().get(0).depth()).isEqualTo(1);
    }

    @Test
    void nullRowsInTheInventoryAreSteppedOver() {
        model.setInventory(new FixtureInventory(
                null,
                cell(null, null, "Group", "No id"),
                cell("real", null, "Group", "Real")));

        assertThat(model.rows()).extracting(WorldHudModel.Row::cellId)
                .containsExactly("real");
    }

    @Test
    void theSummaryIsBuiltFromTheSameRowsTheHudLists() {
        model.setInventory(new FixtureInventory(
                cell("a", null, "Group", "A"),
                cell("b", null, "Group", "B"),
                cell("c", null, "Group", "C")));
        bridge.mountCell("a", STUB_RENDERER);
        bridge.mountCell("b", STUB_RENDERER);
        bridge.setCellVisible("b", false);
        bridge.mountCell("stray", STUB_RENDERER);

        assertThat(model.summary())
                .isEqualTo("4 cells: 2 shown, 1 hidden, 1 pending");
    }

    @Test
    void clearingTheInventoryLeavesOnlyTheMountedRows() {
        model.setInventory(new FixtureInventory(
                cell("pending", null, "Group", "Pending")));
        bridge.mountCell("A", STUB_RENDERER);
        assertThat(model.rows()).hasSize(2);

        model.setInventory(null);

        assertThat(model.getInventory()).isNull();
        assertThat(model.rows()).extracting(WorldHudModel.Row::cellId)
                .containsExactly("A");
    }

    @Test
    void rowsAreAnUnmodifiableSnapshot() {
        bridge.mountCell("A", STUB_RENDERER);

        assertThatThrownBy(() -> model.rows().add(
                new WorldHudModel.Row("B", "B", null, 0,
                        WorldHudModel.CellState.PENDING)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unmountingFlipsTheRowBackToPending() {
        model.setInventory(new FixtureInventory(
                cell("A", null, "Group", "Alpha")));
        bridge.mountCell("A", STUB_RENDERER);
        assertThat(model.rows().get(0).state())
                .isEqualTo(WorldHudModel.CellState.MOUNTED);

        bridge.unmountCell("A");

        assertThat(model.rows().get(0).state())
                .isEqualTo(WorldHudModel.CellState.PENDING);
    }

    @Test
    void aSecondInventoryArrivesThroughTheConstructorAndCanBeReplaced() {
        WorldInventory first = new FixtureInventory(
                cell("A", null, "Group", "Alpha"));
        WorldInventory second = new FixtureInventory(
                cell("B", null, "Group", "Bravo"));
        WorldHudModel loaded = new WorldHudModel(bridge, first);
        assertThat(loaded.getInventory()).isSameAs(first);

        loaded.setInventory(second);

        assertThat(loaded.rows()).extracting(WorldHudModel.Row::cellId)
                .containsExactly("B");
    }

    private List<WorldHudModel.CellState> states() {
        return model.rows().stream().map(WorldHudModel.Row::state).toList();
    }

    @Test
    void anEmptyWorldAndAnEmptyBridgePrintAnEmptyHud() {
        model.setInventory(new FixtureInventory(Collections.emptyList()));

        assertThat(model.rows()).isEmpty();
    }
}
