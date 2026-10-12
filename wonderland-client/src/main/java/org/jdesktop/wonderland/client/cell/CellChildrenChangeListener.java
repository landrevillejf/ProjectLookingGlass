/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 * Notified when a child is added to or removed from a Cell's subtree level.
 */
package org.jdesktop.wonderland.client.cell;

import java.io.Serializable;

public interface CellChildrenChangeListener extends Serializable {

    void childAdded(Cell parent, Cell child);

    void childRemoved(Cell parent, Cell child);
}
