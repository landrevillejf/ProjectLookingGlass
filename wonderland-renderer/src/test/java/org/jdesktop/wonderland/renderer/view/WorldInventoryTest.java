/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * The arithmetic P0.2 and P1.1 actually run on. WorldScene builds a WorldInventory
 * from the volumes it already trusts and hands it to the two kernels; that hand-off
 * is the whole feature, and it is all float bookkeeping, so the claims the live path
 * makes -- "a far cell drops", "a wall stops a walker", "an unauthored cell is a
 * room not a zero" -- are asserted here rather than argued from a probe screenshot.
 */
package org.jdesktop.wonderland.renderer.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.jdesktop.wonderland.client.collision.AvatarCollider;
import org.jdesktop.wonderland.client.collision.Obstacle;
import org.jdesktop.wonderland.client.cull.WorldPVS;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.Test;

class WorldInventoryTest {

    /** One placed cell at a fixed floor line, for the tests that do not vary it. */
    private static WorldInventory.Placement cell(String name, float x, float z,
            float footprint) {
        return new WorldInventory.Placement(name, x, 1f, z, footprint, 2f);
    }

    @Test
    void anEmptyWorldIsTheIdentityForEveryQuestion() {
        WorldInventory empty = WorldInventory.empty();
        assertThat(empty.size()).isZero();
        assertThat(empty.placements()).isEmpty();
        assertThat(empty.obstacles()).isEmpty();
        assertThat(empty.reach()).isEqualTo(1f);
        // A collider over nothing registers no obstacles, so it lets a move pass.
        assertThat(empty.colliderAt(0f, 0f).isEmpty()).isTrue();
    }

    @Test
    void aPlacementWithNoSaneNumbersBecomesARoomRatherThanADegenerateZero() {
        // A footprint of zero would register a cell nobody can ever see AND let a
        // walker through every wall; the class refuses that in its own constructor.
        WorldInventory.Placement bad = new WorldInventory.Placement(
                "  ", Float.NaN, 0f, Float.POSITIVE_INFINITY, 0f, -3f);
        assertThat(bad.name()).isEqualTo("unnamed");
        assertThat(bad.footprint())
                .isEqualTo(WorldInventory.CELL_FALLBACK_FOOTPRINT_M);
        assertThat(bad.height())
                .isEqualTo(WorldInventory.CELL_BLOCKING_HEIGHT_M);
        // every non-finite coordinate collapses to the origin, never a NaN poison
        assertThat(bad.x()).isZero();
        assertThat(bad.z()).isZero();
    }

    @Test
    void everyPlacedCellBecomesExactlyOneBlockingBox() {
        WorldInventory inventory = new WorldInventory(List.of(
                cell("room-a", 0f, 0f, 4f),
                cell("room-b", 20f, 0f, 5f)));
        List<Obstacle> obstacles = inventory.obstacles();
        assertThat(obstacles).hasSize(2);
        Obstacle a = obstacles.get(0);
        assertThat(a.id()).isEqualTo("room-a");
        // Obstacle.of names the box from centre + half-extents; the footprint is
        // used on both horizontal axes so a long room blocks along its short one.
        assertThat(a.minX()).isCloseTo(-4f, within(1e-5f));
        assertThat(a.maxX()).isCloseTo(4f, within(1e-5f));
        assertThat(a.minZ()).isCloseTo(-4f, within(1e-5f));
        assertThat(a.maxZ()).isCloseTo(4f, within(1e-5f));
    }

    @Test
    void thePvsKeepsANearCellAndDropsOneOnTheFarSideOfTheWorld() {
        WorldInventory inventory = new WorldInventory(List.of(
                cell("here", 0f, 0f, 3f),
                cell("there", 1000f, 0f, 3f)));
        // The class comment's claim, proved: a coarse grid and a fine one answer
        // the same set -- the grid only chooses the work, never the outcome.
        for (float grid : new float[] {50f, 5f, 0f}) {
            WorldPVS pvs = inventory.pvs(grid, 60f);
            assertThat(pvs.size()).isEqualTo(2);
            assertThat(pvs.visibleFrom(0f, 0f, 0f).ids())
                    .contains("here")
                    .doesNotContain("there");
            // 0f means "use the world's own reach", so it cannot throw on the grid.
            assertThat(inventory.pvs(grid, 60f)).isNotNull();
        }
    }

    @Test
    void theReachIsTheFurthestCellMeasuredFromTheMiddleOfEverything() {
        WorldInventory inventory = new WorldInventory(List.of(
                cell("a", -100f, 0f, 2f),
                cell("b", 100f, 0f, 2f)));
        // centre is x = 0; the furthest centre is 100 m out plus its own footprint.
        assertThat(inventory.reach()).isCloseTo(102f, within(1e-3f));
    }

    @Test
    void aWallStopsAWalkerAndNamesTheCellThatRefusedTheStep() {
        WorldInventory inventory = new WorldInventory(List.of(
                cell("walkway", 0f, 0f, 1f),
                cell("wall", 6f, 0f, 1f)));
        AvatarCollider collider = inventory.colliderAt(0f, 0f);
        assertThat(collider.isEmpty()).isFalse();

        CellTransform request = new CellTransform();
        request.setTranslation(new com.jme.math.Vector3f(20f, 0f, 0f));
        String hit = collider.clamp(request);

        // The step is refused at the wall's near face (x = 5), grown by the
        // walker's radius, so the walker lands short of 20 rather than through it.
        assertThat(hit).isEqualTo("wall");
        com.jme.math.Vector3f landed = request.getTranslation(null);
        assertThat(landed.x).isLessThan(5.4f);
        assertThat(landed.x).isGreaterThan(4f);
    }

    @Test
    void anEmptyWorldLetsTheWholeStepThroughUnchanged() {
        AvatarCollider collider = WorldInventory.empty().colliderAt(0f, 0f);
        CellTransform request = new CellTransform();
        request.setTranslation(new com.jme.math.Vector3f(500f, 0f, 500f));
        assertThat(collider.clamp(request)).isNull();
        assertThat(request.getTranslation(null).x).isEqualTo(500f);
    }
}
