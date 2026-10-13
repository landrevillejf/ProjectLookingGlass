/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client M6b: the seam through which a scene-graph avatar
 * renderer installs itself without wonderland-client importing up.
 *
 * wonderland-client cannot depend on wonderland-renderer (the renderer sits
 * on top of client, so that would cycle). The 2006 client's AvatarCell
 * constructed an AvatarJME inline; the port keeps AvatarCell in the client
 * module and lets the renderer inject its own implementation at boot time
 * through this holder. When nothing is installed -- a headless test JVM, the
 * M2f-era code path -- createCellRenderer() returns null and the cell keeps
 * the original "no renderer" quirk intact, and the trigger dispatch guards
 * stay inert. When the live renderer is installed, it takes over.
 *
 * The holder is a package-visible static slot rather than a Spring bean:
 * Wonderland's client module has no dependency-injection framework in the
 * port, and the desktop's bootstrap installs the renderer once at startup.
 */
package org.jdesktop.wonderland.client.cell.view;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellRenderer;

@FunctionalInterface
public interface AvatarRendererInstaller {
    /**
     * Build (or return a cached) renderer for {@code cell} under the requested
     * {@code type}. Implementations return null for a type they do not own,
     * mirroring the Cell base class contract; the caller never sees a
     * half-built renderer.
     */
    CellRenderer create(AvatarCell cell, Cell.RendererType type);
}
