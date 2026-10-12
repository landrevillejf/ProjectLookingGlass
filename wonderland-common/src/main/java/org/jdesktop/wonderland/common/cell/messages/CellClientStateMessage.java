package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.state.CellClientState;

public class CellClientStateMessage extends CellMessage {
   private CellClientState clientState = null;

   public CellClientStateMessage(CellID cellID, CellClientState clientState) {
      super(cellID);
      this.clientState = clientState;
   }

   public CellClientState getClientState() {
      return this.clientState;
   }
}
