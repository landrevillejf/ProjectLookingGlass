/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 * Notified when a CellComponent is added to or removed from a Cell.
 */
package org.jdesktop.wonderland.client.cell;

public interface ComponentChangeListener {

    void componentChanged(Cell cell, ChangeType type, CellComponent component);

    enum ChangeType {
        ADDED,
        REMOVED
    }
}
