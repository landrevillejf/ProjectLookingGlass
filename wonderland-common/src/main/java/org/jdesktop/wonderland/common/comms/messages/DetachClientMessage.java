package org.jdesktop.wonderland.common.comms.messages;

import org.jdesktop.wonderland.common.messages.Message;

public class DetachClientMessage extends Message {
   private short clientID;

   public DetachClientMessage(short clientID) {
      this.clientID = clientID;
   }

   public short getClientID() {
      return this.clientID;
   }
}
