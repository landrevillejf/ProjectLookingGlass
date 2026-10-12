package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@InternalAPI
public class ViewCreateResponseMessage extends ResponseMessage {
   private CellID viewCellID;

   public ViewCreateResponseMessage(MessageID messageID, CellID viewCellID) {
      super(messageID);
      this.viewCellID = viewCellID;
   }

   public CellID getViewCellID() {
      return this.viewCellID;
   }
}
