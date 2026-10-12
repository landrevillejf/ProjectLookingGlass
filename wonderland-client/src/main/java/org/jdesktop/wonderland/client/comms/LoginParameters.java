package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class LoginParameters {
   private String userName;
   private char[] password;

   public LoginParameters(String userName, char[] password) {
      this.userName = userName;
      this.password = password;
   }

   public String getUserName() {
      return this.userName;
   }

   void setUserName(String userName) {
      this.userName = userName;
   }

   public char[] getPassword() {
      return this.password;
   }

   void setPassword(char[] password) {
      this.password = password;
   }
}
