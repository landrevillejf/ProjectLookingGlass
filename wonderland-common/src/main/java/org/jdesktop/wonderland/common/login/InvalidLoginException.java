package org.jdesktop.wonderland.common.login;

public class InvalidLoginException extends AuthenticationException {
   public InvalidLoginException() {
   }

   public InvalidLoginException(String msg) {
      super(msg);
   }

   public InvalidLoginException(Throwable cause) {
      super(cause);
   }

   public InvalidLoginException(String msg, Throwable cause) {
      super(msg, cause);
   }
}
