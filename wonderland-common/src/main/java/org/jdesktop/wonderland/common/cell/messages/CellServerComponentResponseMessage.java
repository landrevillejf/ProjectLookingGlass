package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.state.CellComponentServerState;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@InternalAPI
public class CellServerComponentResponseMessage extends ResponseMessage {
   private CellComponentServerState serverState;

   public CellServerComponentResponseMessage(MessageID messageID, CellComponentServerState serverState) {
      super(messageID);
      this.serverState = serverState;
   }

   public CellComponentServerState getCellComponentServerState() {
      return this.serverState;
   }
}
