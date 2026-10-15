/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * The single root cell that holds global environment state (lighting, sky). It
 * adds nothing over Cell in 0.5 -- it exists so the cache can recognise the
 * environment cell by type (the getEnvironmentCell() cast).
 *
 * <p>Concrete, not abstract, and that is the fix rather than a looseness:
 * CellCacheBasicImpl.instantiateCell reflects the class name the server
 * broadcasts open, and the server's default (WorldModel.ENVIRONMENT_CLASS) names
 * THIS class. An abstract here threw InstantiationException on every live
 * walk-in -- a SEVERE log that surfaced as the desktop's crash dialog -- because
 * 0.5 shipped the desktop's concrete subclass name as world data while this port
 * supplies no such subclass in production. The environment root is now directly
 * instantiable, exactly as the concrete ViewCell the router already names.</p>
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.common.cell.CellID;

public class EnvironmentCell extends Cell {

    public EnvironmentCell(CellID cellID, CellCache cellCache) {
        super(cellID, cellCache);
    }
}
