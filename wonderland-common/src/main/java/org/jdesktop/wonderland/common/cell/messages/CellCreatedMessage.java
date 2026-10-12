package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

public class CellCreatedMessage extends ResponseMessage {
   private final CellID cellID;

   public CellCreatedMessage(MessageID messageID, CellID cellID) {
      super(messageID);
      this.cellID = cellID;
   }

   public CellID getCellID() {
      return this.cellID;
   }
}
