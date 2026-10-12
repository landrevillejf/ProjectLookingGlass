package org.jdesktop.wonderland.common.comms.messages;

import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

public class AttachedClientMessage extends ResponseMessage {
   private short clientID;

   public AttachedClientMessage(MessageID messageID, short clientID) {
      super(messageID);
      this.clientID = clientID;
   }

   public short getClientID() {
      return this.clientID;
   }
}
