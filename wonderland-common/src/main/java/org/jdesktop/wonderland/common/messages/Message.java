package org.jdesktop.wonderland.common.messages;

import java.io.Serializable;
import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public abstract class Message implements Serializable {
   private MessageID messageID;

   protected Message() {
      this(MessageID.generateMessageID());
   }

   protected Message(MessageID messageID) {
      this.messageID = messageID;
   }

   public MessageID getMessageID() {
      return this.messageID;
   }

   void setMessageID(MessageID messageID) {
      this.messageID = messageID;
   }
}
