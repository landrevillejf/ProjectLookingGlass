/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 * Notified when a Cell's status changes, both per-cell and through the
 * CellManager singleton's global registration.
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.common.cell.CellStatus;

public interface CellStatusChangeListener {

    void cellStatusChanged(Cell cell, CellStatus status);
}
