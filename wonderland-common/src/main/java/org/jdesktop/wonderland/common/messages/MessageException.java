package org.jdesktop.wonderland.common.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class MessageException extends RuntimeException {
   public MessageException() {
   }

   public MessageException(String msg) {
      super(msg);
   }

   public MessageException(Throwable cause) {
      super(cause);
   }

   public MessageException(String msg, Throwable cause) {
      super(msg, cause);
   }
}
