package org.jdesktop.wonderland.common.messages;

import java.io.IOException;
import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class ExtractMessageException extends IOException {
   private MessageID messageID;
   private short clientID;

   public ExtractMessageException(MessageID messageID, short clientID) {
      this(messageID, clientID, (Throwable)null);
   }

   public ExtractMessageException(MessageID messageID, short clientID, Throwable cause) {
      this.initCause(cause);
      this.messageID = messageID;
      this.clientID = clientID;
   }

   public MessageID getMessageID() {
      return this.messageID;
   }

   public short getClientID() {
      return this.clientID;
   }
}
