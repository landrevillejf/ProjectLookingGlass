package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.security.ModifyAction;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.security.annotation.Actions;

@Actions({ModifyAction.class})
@ExperimentalAPI
public class CellServerStateSetMessage extends CellMessage {
   private CellServerState serverState;

   public CellServerStateSetMessage(CellID cellID, CellServerState serverState) {
      super(cellID);
      this.serverState = serverState;
   }

   public CellServerState getCellServerState() {
      return this.serverState;
   }
}
