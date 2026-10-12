package org.jdesktop.wonderland.common.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class ErrorMessage extends ResponseMessage {
   private String errorMessage;
   private Throwable errorCause;

   public ErrorMessage(MessageID messageID) {
      this(messageID, (String)null, (Throwable)null);
   }

   public ErrorMessage(MessageID messageID, String errorMessage) {
      this(messageID, errorMessage, (Throwable)null);
   }

   public ErrorMessage(MessageID messageID, Throwable errorCause) {
      this(messageID, (String)null, errorCause);
   }

   public ErrorMessage(MessageID messageID, String errorMessage, Throwable errorCause) {
      super(messageID);
      this.errorMessage = errorMessage;
      this.errorCause = errorCause;
   }

   public String getErrorMessage() {
      return this.errorMessage;
   }

   public void setErrorMessage(String errorMessage) {
      this.errorMessage = errorMessage;
   }

   public Throwable getErrorCause() {
      return this.errorCause;
   }

   public void setErrorCause(Throwable errorCause) {
      this.errorCause = errorCause;
   }
}
