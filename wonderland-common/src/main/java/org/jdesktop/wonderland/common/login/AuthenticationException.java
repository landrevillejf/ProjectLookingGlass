package org.jdesktop.wonderland.common.login;

public class AuthenticationException extends Exception {
   private String message;

   public AuthenticationException() {
   }

   public AuthenticationException(String msg) {
      this.message = msg;
   }

   public AuthenticationException(Throwable cause) {
      super(cause);
   }

   public AuthenticationException(String msg, Throwable cause) {
      super(cause);
      this.message = msg;
   }

   public String getMessage() {
      return this.message;
   }
}
