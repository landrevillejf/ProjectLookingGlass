package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class LoginFailureException extends Exception {
   public LoginFailureException() {
   }

   public LoginFailureException(String msg) {
      super(msg);
   }

   public LoginFailureException(Throwable cause) {
      super(cause.getMessage(), cause);
   }

   public LoginFailureException(String msg, Throwable cause) {
      super(msg, cause);
   }
}
