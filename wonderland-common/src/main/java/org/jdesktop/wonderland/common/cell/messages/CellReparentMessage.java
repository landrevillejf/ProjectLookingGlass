package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.security.ChildrenAction;
import org.jdesktop.wonderland.common.cell.security.ModifyAction;
import org.jdesktop.wonderland.common.security.annotation.Actions;

@Actions({ModifyAction.class, ChildrenAction.class})
@ExperimentalAPI
public class CellReparentMessage extends CellEditMessage {
   private CellID parentCellID = null;
   private CellID cellID = null;
   private CellTransform childTransform = null;

   public CellReparentMessage(CellID cellID, CellID parentCellID, CellTransform childTransform) {
      super(CellEditMessage.EditType.REPARENT_CELL);
      this.cellID = cellID;
      this.parentCellID = parentCellID;
      this.childTransform = childTransform;
   }

   public CellID getCellID() {
      return this.cellID;
   }

   public CellID getParentCellID() {
      return this.parentCellID;
   }

   public CellTransform getChildCellTransform() {
      return this.childTransform;
   }
}
