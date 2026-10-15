/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link WorldPlan}, the pure math behind the browser's
 * vignette. Every bullet in the class javadoc is a decision, so every one of
 * them is a case here: the environment root dropped, unplaced cells skipped,
 * a missing footprint drawn as a unit square, one uniform scale (aspect
 * preserved) and centering, +z pointing up, and the degenerate all-in-one-point
 * world inflated instead of dividing by zero.
 */
class WorldPlanTest {

    private static final double EPS = 1e-9;

    private static WorldCatalog.Cell cell(String name, Double x, Double z,
            Double width, Double depth) {
        return new WorldCatalog.Cell("id-" + name, null,
                "org.jdesktop.wonderland.modules.cell.TestCell", name,
                x, z, width, depth);
    }

    private static WorldCatalog.Cell environment(Double x, Double z) {
        return new WorldCatalog.Cell("env", null,
                "org.jdesktop.wonderland.modules.cell.EnvironmentCell",
                "Environment", x, z, 100.0, 100.0);
    }

    @Test
    @DisplayName("a world with no placed cell has no plan")
    void emptyAndUnplacedWorldsAreEmptyPlans() {
        assertSame(WorldPlan.EMPTY, WorldPlan.of(List.of()));
        assertSame(WorldPlan.EMPTY, WorldPlan.of(List.of(
                cell("Room", null, null, null, null))));
        assertTrue(WorldPlan.EMPTY.isEmpty());
    }

    @Test
    @DisplayName("the environment floor is not a room on the plan")
    void environmentRootIsSkipped() {
        WorldPlan plan = WorldPlan.of(List.of(
                environment(0.0, 0.0),
                cell("Room", 1.0, 1.0, 2.0, 2.0)));

        assertEquals(1, plan.items().size());
        assertEquals("Room", plan.items().get(0).label());
    }

    @Test
    @DisplayName("two rooms normalize to their real relative spots and sizes")
    void normalizesPlacedCells() {
        // two 2x2 rooms ten apart on x: spanX 8..-1 -> 8 wide, spanZ 2
        WorldPlan plan = WorldPlan.of(List.of(
                cell("Near", 0.0, 0.0, 2.0, 2.0),
                cell("Far", 6.0, 0.0, 2.0, 2.0)));

        List<WorldPlan.Item> items = plan.items();
        assertEquals(2, items.size());
        // one uniform scale fits the long axis into the padded square:
        // (1 - 2*PAD) / 8, and the short axis stays centered inside
        double scale = (1 - 2 * WorldPlan.PAD) / 8.0;
        assertEquals(2.0 * scale, items.get(0).w(), EPS);
        assertEquals(2.0 * scale, items.get(0).h(), EPS);
        // the far room is exactly its world offset further right
        assertEquals(items.get(0).x() + 6.0 * scale, items.get(1).x(), EPS);
        assertEquals(items.get(0).y(), items.get(1).y(), EPS);
        // padded on the long axis: content spans exactly 1 - 2*PAD of the
        // square, and the short axis stays centered inside what remains
        assertEquals(WorldPlan.PAD, items.get(0).x(), EPS);
        assertEquals(1 - WorldPlan.PAD,
                items.get(1).x() + items.get(1).w(), EPS);
    }

    @Test
    @DisplayName("the world's +z axis points up on screen")
    void northIsUp() {
        WorldPlan plan = WorldPlan.of(List.of(
                cell("South", 0.0, -5.0, 1.0, 1.0),
                cell("North", 0.0, 5.0, 1.0, 1.0)));

        WorldPlan.Item south = plan.items().get(0);
        WorldPlan.Item north = plan.items().get(1);
        // panel y grows downward, so the larger world z must paint smaller y
        assertTrue(north.y() < south.y(),
                "north y=" + north.y() + " south y=" + south.y());
    }

    @Test
    @DisplayName("a missing footprint draws a unit square, not a zero or a guess")
    void sizelessCellsDrawUnitSquares() {
        WorldPlan plan = WorldPlan.of(List.of(cell("Room", 0.0, 0.0, null, null)));

        WorldPlan.Item only = plan.items().get(0);
        // a unit square is the whole content: inflated? no - span is 1, and the
        // padded square is 0.88 wide, so the box is 0.88 with margin 0.06
        assertEquals(1 - 2 * WorldPlan.PAD, only.w(), EPS);
        assertEquals(only.w(), only.h(), EPS);
        assertEquals(WorldPlan.PAD, only.x(), EPS);
    }

    @Test
    @DisplayName("rooms sharing one point inflate to a floor epsilon instead of dividing by zero")
    void degenerateWorldStaysFinite() {
        WorldPlan plan = WorldPlan.of(List.of(
                cell("A", 0.0, 0.0, 1e-6, 1e-6),
                cell("B", 0.0, 0.0, 1e-6, 1e-6)));

        for (WorldPlan.Item item : plan.items()) {
            assertNotNull(item);
            assertTrue(Double.isFinite(item.x()) && Double.isFinite(item.y())
                    && Double.isFinite(item.w()) && Double.isFinite(item.h()));
            assertTrue(item.x() >= -EPS && item.x() + item.w() <= 1 + EPS);
            assertTrue(item.y() >= -EPS && item.y() + item.h() <= 1 + EPS);
        }
    }

    @Test
    @DisplayName("items keep the cells' publication order")
    void orderIsPreserved() {
        WorldPlan plan = WorldPlan.of(List.of(
                cell("One", 3.0, 0.0, 1.0, 1.0),
                cell("Two", -3.0, 0.0, 1.0, 1.0),
                cell("Three", 0.0, 3.0, 1.0, 1.0)));

        assertEquals(List.of("One", "Two", "Three"),
                plan.items().stream().map(WorldPlan.Item::label).toList());
    }

    // ---- the ground rule: the reason the first version of this vignette was
    // ---- unreadable is that a world's floor slab set the scale and every room
    // ---- became a speck with its name painted over its neighbours.

    @Test
    @DisplayName("the floor slab is the frame, not a room, and does not set the scale")
    void theGroundCellIsExcludedFromTheRoomsAndTheBounds() {
        // a 40x40 floor over three 10x10 rooms: the clinic's shape, in round numbers
        WorldPlan plan = WorldPlan.of(List.of(
                cell("Floor", 0.0, 0.0, 40.0, 40.0),
                cell("A", -10.0, 0.0, 10.0, 10.0),
                cell("B", 10.0, 0.0, 10.0, 10.0),
                cell("C", 0.0, 10.0, 10.0, 10.0)));

        assertEquals(3, plan.items().size());
        assertTrue(plan.items().stream().noneMatch(i -> i.label().equals("Floor")));
        assertEquals("Floor", plan.ground().orElseThrow().label());
        // scaled by the rooms' own 30-unit width, so one room is a third of the
        // drawing -- with the floor in the bounds it would have been a twelfth
        assertEquals(10.0 / 30.0, plan.items().get(0).w() / plan.contentBounds()[2], 1e-9);
    }

    @Test
    @DisplayName("an ordinary room is never mistaken for the ground")
    void aCellThatOnlyDwarfsItsNeighboursStaysARoom() {
        // 12x12 in a 20x20 extent: bigger than the rest, but nowhere near 3/4 of
        // the world on both axes, so it is still a room with a place on the plan
        WorldPlan plan = WorldPlan.of(List.of(
                cell("Hall", 0.0, 0.0, 12.0, 12.0),
                cell("Closet", -9.0, -9.0, 2.0, 2.0),
                cell("Annex", 9.0, 9.0, 2.0, 2.0)));

        assertEquals(3, plan.items().size());
        assertTrue(plan.ground().isEmpty());
    }

    @Test
    @DisplayName("a lone cell is never the ground: there must be rooms to be above")
    void aSingleCellWorldStillPlans() {
        WorldPlan plan = WorldPlan.of(List.of(cell("Floor", 0.0, 0.0, 40.0, 40.0)));

        // the guard is deliberate: excluding it would leave an empty plan for a
        // world that has exactly one placed cell, which reads as "nothing here"
        assertEquals(1, plan.items().size());
        assertTrue(plan.ground().isEmpty());
    }

    @Test
    @DisplayName("of two identical slabs one is the ground and the other is still a room")
    void theGroundTieBreakKeepsSomethingOnThePlan() {
        WorldPlan plan = WorldPlan.of(List.of(
                cell("Floor", 0.0, 0.0, 40.0, 40.0),
                cell("Grass", 0.0, 0.0, 40.0, 40.0)));

        // the first largest cell wins the ground slot, so a duplicated slab
        // cannot empty the plan -- one of them still draws, at the full square
        assertEquals("Floor", plan.ground().orElseThrow().label());
        assertEquals(List.of("Grass"),
                plan.items().stream().map(WorldPlan.Item::label).toList());
    }

    @Test
    @DisplayName("contentBounds is the box the rooms actually fill")
    void contentBoundsIsThePaintedExtent() {
        // two 2x2 rooms six apart on x: 8 wide, 2 deep, padded to 0.88 x 0.22
        WorldPlan plan = WorldPlan.of(List.of(
                cell("Near", 0.0, 0.0, 2.0, 2.0),
                cell("Far", 6.0, 0.0, 2.0, 2.0)));

        double[] bounds = plan.contentBounds(); // {minX, minY, w, h}
        assertEquals(WorldPlan.PAD, bounds[0], EPS);
        assertEquals(1 - 2 * WorldPlan.PAD, bounds[2], EPS);
        assertEquals(2 * (1 - 2 * WorldPlan.PAD) / 8.0, bounds[3], EPS);
        // the long axis is padded and the short one keeps the centering the
        // items were given, so a panel that fits this box keeps the world's
        // shape and its margins in one step
        assertEquals((1 - bounds[3]) / 2, bounds[1], EPS);
    }
}
