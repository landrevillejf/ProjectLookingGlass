package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellID;

@ExperimentalAPI
public class CellServerStateRequestMessage extends CellMessage {
   public CellServerStateRequestMessage(CellID cellID) {
      super(cellID);
   }
}
