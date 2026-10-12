package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.security.ChildrenAction;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.security.annotation.Actions;

@Actions({ChildrenAction.class})
@ExperimentalAPI
public class CellCreateMessage extends CellEditMessage {
   private CellID parentCellID;
   private CellServerState setup;

   public CellCreateMessage(CellID parentCellID, CellServerState setup) {
      super(CellEditMessage.EditType.CREATE_CELL);
      this.parentCellID = parentCellID;
      this.setup = setup;
   }

   public CellID getParentCellID() {
      return this.parentCellID;
   }

   public CellServerState getCellSetup() {
      return this.setup;
   }

   public void setCellSetup(CellServerState setup) {
      this.setup = setup;
   }
}
