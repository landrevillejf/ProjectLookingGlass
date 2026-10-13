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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jdesktop.wonderland.desktop.WorldInventory;

/**
 * The bridge between the two halves of the Wonderland integration: it presents
 * a REST {@link WorldCatalog.WorldView} as the
 * {@link WorldInventory} the {@code wonderland-desktop} HUD model consumes.
 *
 * <p>No new transport lives here on purpose. The catalog already knows how to
 * read {@code /api/world/cells}, already normalises the server address and
 * already refuses a malformed document; the bridge's {@code WorldInventory}
 * already names exactly the four fields that document carries. Re-implementing
 * the read inside {@code wonderland-desktop} would put a second HTTP client and
 * a second JSON parser in the tree, and the two could then disagree about the
 * same world &mdash; the one thing the HUD must not do, because a HUD row that
 * contradicts the scene graph is worse than a missing row.</p>
 *
 * <p>The mapping is a straight copy of the four fields, in publication order:
 * {@link WorldInventory} requires it (its javadoc records the ordering rule the
 * port learned the hard way in M7c), and {@link WorldCatalog.WorldView} already
 * preserves the server's order.</p>
 */
final class WorldCatalogInventory implements WorldInventory {

    private final List<WorldInventory.Cell> cells;

    private WorldCatalogInventory(WorldCatalog.WorldView world) {
        List<WorldInventory.Cell> out = new ArrayList<>(world.size());
        for (WorldCatalog.Cell cell : world.cells()) {
            out.add(new WorldInventory.Cell(cell.id, cell.parent,
                    cell.className, cell.name));
        }
        this.cells = Collections.unmodifiableList(out);
    }

    /**
     * Reads the world {@code server} publishes and presents it as an inventory.
     *
     * @param server the server address, in any of the forms
     *               {@link WorldCatalog#normalizeServer(String)} accepts
     * @return the published inventory
     * @throws IOException when the server is unreachable or its document is not
     *                     a world; the HUD reports that as a failed read rather
     *                     than as an empty world
     */
    static WorldInventory read(String server) throws IOException {
        return new WorldCatalogInventory(WorldCatalog.load(server));
    }

    /**
     * Presents an already-read catalog as an inventory &mdash; no HTTP, which is
     * what lets the HUD be driven from a fixture.
     */
    static WorldInventory of(WorldCatalog.WorldView world) {
        return new WorldCatalogInventory(world);
    }

    @Override
    public List<WorldInventory.Cell> cells() {
        return cells;
    }
}
