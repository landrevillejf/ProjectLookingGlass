package org.jdesktop.wonderland.common.cell.messages;

import java.util.Set;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.security.ModifyAction;
import org.jdesktop.wonderland.common.cell.state.CellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.security.annotation.Actions;

@Actions({ModifyAction.class})
@ExperimentalAPI
public class CellServerStateUpdateMessage extends CellMessage {
   private CellServerState cellState = null;
   private Set componentStateSet = null;

   public CellServerStateUpdateMessage(CellID cellID, CellServerState cellState, Set componentStateSet) {
      super(cellID);
      this.cellState = cellState;
      this.componentStateSet = componentStateSet;
   }

   public CellServerState getCellServerState() {
      return this.cellState;
   }

   public Set getCellComponentServerStateSet() {
      return this.componentStateSet;
   }
}
