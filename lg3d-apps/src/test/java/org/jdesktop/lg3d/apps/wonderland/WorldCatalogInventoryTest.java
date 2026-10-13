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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.wonderland.desktop.WorldInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the adapter that lets the HUD read the REST catalog: the
 * four wire fields must arrive unchanged and in publication order, because the
 * two consumers of the contract disagree on everything else.
 *
 * <p>{@link WorldCatalog} deliberately pre-formats labels (the panel wants a
 * name, else a simple class, else an id) while {@code WorldHudModel} does its own
 * label fallback and needs the raw class string for the tooltip. So the adapter
 * has exactly one job &mdash; carry the four fields across &mdash; and the tests
 * below are mostly assertions that it did not also translate something.</p>
 */
class WorldCatalogInventoryTest {

    /** The same three-level world the panel and catalog tests parse. */
    private static final String SAMPLE = "["
            + "{\"id\":\"1\",\"parent\":null,\"className\":\"org.jdesktop.wonderland.modules.cell.EnvironmentCell\",\"name\":\"Environment\"},"
            + "{\"id\":\"2\",\"parent\":\"1\",\"className\":\"org.jdesktop.wonderland.modules.cell.TestCell\",\"name\":\"Room\"},"
            + "{\"id\":\"3\",\"parent\":\"2\",\"className\":\"org.jdesktop.wonderland.modules.meshmodel.MeshModelCell\",\"name\":null}"
            + "]";

    private static WorldInventory inventoryOf(String body) throws Exception {
        return WorldCatalogInventory.of(WorldCatalog.parse(body));
    }

    @Test
    @DisplayName("the four wire fields cross unchanged")
    void fieldsCrossUnchanged() throws Exception {
        List<WorldInventory.Cell> cells = inventoryOf(SAMPLE).cells();

        assertEquals(3, cells.size());
        WorldInventory.Cell room = cells.get(1);
        assertEquals("2", room.id());
        assertEquals("1", room.parentId());
        // The full class name, not WorldCatalog.Cell.label()'s shortened form:
        // the model falls back on its own terms.
        assertEquals("org.jdesktop.wonderland.modules.cell.TestCell",
                room.className());
        assertEquals("Room", room.name());
    }

    @Test
    @DisplayName("publication order is preserved")
    void orderIsPreserved() throws Exception {
        List<WorldInventory.Cell> cells = inventoryOf(SAMPLE).cells();

        // WorldInventory's contract is the ordering rule, not a suggestion: the
        // HUD walks this list to compute depth, so a reordered copy would show
        // the world in hash order.
        assertEquals(List.of("1", "2", "3"),
                List.of(cells.get(0).id(), cells.get(1).id(), cells.get(2).id()));
    }

    @Test
    @DisplayName("an absent name stays absent rather than being filled in")
    void missingNameStaysMissing() throws Exception {
        WorldInventory.Cell model = inventoryOf(SAMPLE).cells().get(2);

        // The catalog's own label() would say "MeshModelCell"; the inventory must
        // not decide that, or the model's fallback is silently bypassed and a
        // pending cell reads as a mounted one in the status census.
        assertNull(model.name());
        assertEquals("org.jdesktop.wonderland.modules.meshmodel.MeshModelCell",
                model.className());
    }

    @Test
    @DisplayName("an empty world adapts to an empty inventory")
    void emptyWorldAdaptsToEmptyInventory() throws Exception {
        assertTrue(inventoryOf("[]").cells().isEmpty());
    }

    @Test
    @DisplayName("the adapted list cannot be edited through the HUD")
    void cellsAreNotModifiable() throws Exception {
        List<WorldInventory.Cell> cells = inventoryOf(SAMPLE).cells();

        assertThrows(UnsupportedOperationException.class,
                () -> cells.add(new WorldInventory.Cell("9", null, "x", "Y")));
    }
}
