package org.jdesktop.wonderland.client.comms;

public class ServerUnavailableException extends LoginFailureException {
   public ServerUnavailableException() {
   }

   public ServerUnavailableException(String msg) {
      super(msg);
   }

   public ServerUnavailableException(Throwable cause) {
      super(cause);
   }

   public ServerUnavailableException(String msg, Throwable cause) {
      super(msg, cause);
   }
}
