package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.security.ChildrenAction;
import org.jdesktop.wonderland.common.security.annotation.Actions;

@Actions({ChildrenAction.class})
@ExperimentalAPI
public class CellDuplicateMessage extends CellEditMessage {
   private CellID cellID;
   private String cellName = "";

   public CellDuplicateMessage(CellID cellID, String cellName) {
      super(CellEditMessage.EditType.DUPLICATE_CELL);
      this.cellID = cellID;
      this.cellName = cellName;
   }

   public CellID getCellID() {
      return this.cellID;
   }

   public String getCellName() {
      return this.cellName;
   }
}
