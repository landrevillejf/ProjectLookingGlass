/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * The base of every cell component. The original walked the status ORDINAL
 * sequence one step at a time (setComponentStatus below) so a component never
 * sees a jump DISK -> ACTIVE without the intermediate setStatus call; that
 * contract is kept verbatim -- M2d's ChannelComponent and every future component
 * depend on it. The walk is package-private on purpose: only Cell drives it.
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;

public class CellComponent {

    protected Cell cell;
    protected CellStatus status;

    public CellComponent(Cell cell) {
        this.status = CellStatus.DISK;
        this.cell = cell;
    }

    /** Subclass hook: called once per status step, in ordinal order. */
    protected void setStatus(CellStatus status, boolean increasing) {
        this.status = status;
    }

    final void setComponentStatus(CellStatus status, boolean increasing) {
        int currentStatus = this.status.ordinal();
        int requiredStatus = status.ordinal();
        if (currentStatus != requiredStatus) {
            int dir = requiredStatus > currentStatus ? 1 : -1;
            while (currentStatus != requiredStatus) {
                currentStatus += dir;
                setStatus(CellStatus.values()[currentStatus], increasing);
            }
        }
    }

    public void setClientState(CellComponentClientState clientState) {
        // no state on the base component; overridden by stateful components
    }
}
