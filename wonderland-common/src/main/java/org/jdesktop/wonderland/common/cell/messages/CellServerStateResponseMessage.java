package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@InternalAPI
public class CellServerStateResponseMessage extends ResponseMessage {
   private CellServerState serverState;

   public CellServerStateResponseMessage(MessageID messageID, CellServerState serverState) {
      super(messageID);
      this.serverState = serverState;
   }

   public CellServerState getCellServerState() {
      return this.serverState;
   }
}
