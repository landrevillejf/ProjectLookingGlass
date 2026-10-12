/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * The client-side registry that maps CellID -> live Cell and drives the
 * load / configure / unload / reparent lifecycle the server drives over the
 * hierarchy messages. M2d lands the headless registry contract only.
 *
 * M2d seam note (docs/wonderland-migration.md, "rebuild the unrecoverable"):
 * the 2006 interface named three types that cannot exist headless --
 *   - WonderlandSession getSession()  (pulls the SGS SimpleClient) is dropped;
 *     the concrete cache holds a MessageSink seam for the one place the
 *     original reached for the session (a CellReparentMessage when a parent is
 *     missing), so the registry needs no session to run or to be tested.
 *   - ViewCell setViewCell()/getViewCell() (the camera/avatar cell, whose
 *     setStatus pulls CellClientSession + MovableComponent) is reduced to a
 *     boolean "has view" notion on the impl; ViewCell itself lands with the
 *     renderer milestone (M2f).
 * getCellChannelConnection() was dropped with the rest in the M2d note, but
 * M2e restores it: the connection layer arrived and the 2006 ChannelComponent
 * ctor resolves its transport through exactly this accessor, so that member is
 * back to the original. Everything else is the original surface, verbatim.
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.bounding.BoundingVolume;
import java.util.Collection;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;

public interface CellCache {

    Cell getCell(CellID cellId);

    Cell[] getCells();

    EnvironmentCell getEnvironmentCell();

    /** M2e: the per-cell message transport the loaded ChannelComponent rides. */
    CellChannelConnection getCellChannelConnection();

    Collection<Cell> getRootCells();

    /**
     * Instantiates, registers and (re)parents the cell named by {@code className}.
     * Mirrors the 2006 signature minus the session-bound view handling; the
     * returned cell is DISK unless the registry walks it up.
     */
    Cell loadCell(CellID cellId, String className, BoundingVolume localBounds,
            CellID parentCellID, CellTransform cellTransform, CellClientState setup,
            String cellName);

    void configureCell(CellID cellId, CellClientState clientState, String cellName);

    void unloadCell(CellID cellId);

    void deleteCell(CellID cellId);

    void changeParent(CellID cellId, CellID parentCellID, CellTransform cellTransform);

    void addCellCacheListener(CellCacheListener listener);

    void removeCellCacheListener(CellCacheListener listener);

    CellStatistics getStatistics();

    interface CellCacheListener {
        void cellLoaded(CellID cellId, Cell cell);

        void cellLoadFailed(CellID cellId, String className, CellID parentCellID,
                Throwable cause);

        void cellUnloaded(CellID cellId, Cell cell);
    }
}
