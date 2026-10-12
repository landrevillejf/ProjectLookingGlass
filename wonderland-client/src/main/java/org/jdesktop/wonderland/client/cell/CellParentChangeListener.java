/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 * Notified when a Cell's parent changes (Serializable in the original, kept:
 * listeners were handed across the module classloader).
 */
package org.jdesktop.wonderland.client.cell;

import java.io.Serializable;

public interface CellParentChangeListener extends Serializable {

    void parentChanged(Cell cell, Cell parent);
}
