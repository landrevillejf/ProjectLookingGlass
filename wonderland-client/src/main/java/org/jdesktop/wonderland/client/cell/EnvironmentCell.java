/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * The single abstract root cell that holds global environment state (lighting,
 * sky). It adds nothing over Cell in 0.5 -- it exists so the cache can recognise
 * the environment cell by type. Faithful minimal port.
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.common.cell.CellID;

public abstract class EnvironmentCell extends Cell {

    public EnvironmentCell(CellID cellID, CellCache cellCache) {
        super(cellID, cellCache);
    }
}
