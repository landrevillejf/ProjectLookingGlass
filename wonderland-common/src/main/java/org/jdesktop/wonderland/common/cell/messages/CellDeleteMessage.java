package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.security.ChildrenAction;
import org.jdesktop.wonderland.common.cell.security.ModifyAction;
import org.jdesktop.wonderland.common.security.annotation.Actions;

@Actions({ModifyAction.class, ChildrenAction.class})
@ExperimentalAPI
public class CellDeleteMessage extends CellEditMessage {
   private CellID cellID;

   public CellDeleteMessage(CellID cellID) {
      super(CellEditMessage.EditType.DELETE_CELL);
      this.cellID = cellID;
   }

   public CellID getCellID() {
      return this.cellID;
   }
}
