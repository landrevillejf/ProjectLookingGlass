package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class ConnectionFailureException extends Exception {
   public ConnectionFailureException() {
   }

   public ConnectionFailureException(String msg) {
      super(msg);
   }

   public ConnectionFailureException(Throwable cause) {
      super(cause);
   }

   public ConnectionFailureException(String msg, Throwable cause) {
      super(msg, cause);
   }
}
