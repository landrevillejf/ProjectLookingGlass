/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: the world's cell inventory as the HUD sees it.
 *
 * The inventory is not the scene graph: it is everything the world *contains*,
 * loaded or not, and it comes from the server (M3's /api/world/cells answers
 * exactly these four fields). Naming it as an interface rather than a list keeps
 * the HUD model headless -- the Swing panel implements it from a JSON read, the
 * test implements it from a fixture, and neither drags HTTP or Swing into the
 * module's pure layer.
 */
package org.jdesktop.wonderland.desktop;

import java.util.List;

/**
 * The cells a world holds, in the order the world publishes them.
 */
public interface WorldInventory {

    /**
     * One cell of the inventory.
     *
     * @param id        the cell id as the server prints it
     * @param parentId  the container cell's id, or null for a root cell
     * @param className the registered cell class (a Group cell, an Avatar cell…)
     * @param name      the author's cell name, may be null or blank
     */
    record Cell(String id, String parentId, String className, String name) {
    }

    /**
     * Every cell in the world, in publication order. The HUD walks in this order,
     * so a caller that returns an unordered copy shows the world in hash order --
     * the ordering rule the port learned the hard way in M7c.
     *
     * @return the inventory; never null, may be empty
     */
    List<Cell> cells();
}
