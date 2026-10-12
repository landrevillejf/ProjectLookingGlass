package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellID;

@InternalAPI
public class CellHierarchyUnloadMessage extends CellHierarchyMessage {
   public CellHierarchyUnloadMessage(CellID cellID) {
      this.cellID = cellID;
      this.msgType = CellHierarchyMessage.ActionType.UNLOAD_CELL;
   }

   public CellHierarchyUnloadMessage() {
      this.msgType = CellHierarchyMessage.ActionType.UNLOAD_CELL;
   }
}
