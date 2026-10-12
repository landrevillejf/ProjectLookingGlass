/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * The original was a bare singleton whose only live job was the global
 * cell-status-change fan-out every Cell fires through fireCellStatusChanged.
 * The module-loader / cell-type registries the class once promised never
 * existed in 0.5 decompiled, so nothing else is ported; M2d's cache plugs in
 * here when it has something to notify.
 */
package org.jdesktop.wonderland.client.cell;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.jdesktop.wonderland.common.cell.CellStatus;

public class CellManager {

    private static CellManager cellManager = null;

    private final Set<CellStatusChangeListener> statusChangeListeners =
            new CopyOnWriteArraySet<>();

    private CellManager() {
    }

    public static CellManager getCellManager() {
        if (cellManager == null) {
            cellManager = new CellManager();
        }
        return cellManager;
    }

    /** Test/teardown hook mirroring CellSelectionRegistry.resetForTests(). */
    public static void resetForTests() {
        cellManager = null;
    }

    void notifyCellStatusChange(Cell cell, CellStatus status) {
        for (CellStatusChangeListener listener : statusChangeListeners) {
            listener.cellStatusChanged(cell, status);
        }
    }

    public void addCellStatusChangeListener(CellStatusChangeListener listener) {
        statusChangeListeners.add(listener);
    }

    public void removeCellStatusChangeListener(CellStatusChangeListener listener) {
        statusChangeListeners.remove(listener);
    }
}
