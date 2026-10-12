package org.jdesktop.wonderland.common.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public abstract class ResponseMessage extends Message {
   public ResponseMessage(MessageID messageID) {
      super(messageID);
   }
}
