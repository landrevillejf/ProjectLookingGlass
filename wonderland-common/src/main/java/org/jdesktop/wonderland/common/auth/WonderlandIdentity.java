package org.jdesktop.wonderland.common.auth;

import java.io.Serializable;

public class WonderlandIdentity implements Serializable {
   private String username;
   private String fullname;
   private String email;

   public WonderlandIdentity(String username, String fullname, String email) {
      this.username = username;
      this.fullname = fullname;
      this.email = email;
   }

   public String getUsername() {
      return this.username;
   }

   public String getFullName() {
      return this.fullname;
   }

   public String getEmail() {
      return this.email;
   }

   public String toString() {
      return "WonderlandIdentity: username=" + this.getUsername() + " fullname=" + this.getFullName() + " email=" + this.getEmail();
   }

   public boolean equals(Object obj) {
      if (obj == null) {
         return false;
      } else if (this.getClass() != obj.getClass()) {
         return false;
      } else {
         WonderlandIdentity other = (WonderlandIdentity)obj;
         if (this.username == null) {
            if (other.username != null) {
               return false;
            }
         } else if (!this.username.equals(other.username)) {
            return false;
         }

         return true;
      }
   }

   public int hashCode() {
      int hash = 5;
      hash = 41 * hash + (this.username != null ? this.username.hashCode() : 0);
      return hash;
   }
}
