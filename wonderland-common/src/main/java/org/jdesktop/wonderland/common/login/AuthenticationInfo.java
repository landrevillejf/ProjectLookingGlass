package org.jdesktop.wonderland.common.login;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class AuthenticationInfo implements Cloneable {
   private Type type;
   private String authURL;

   public AuthenticationInfo() {
   }

   public AuthenticationInfo(Type type, String authURL) {
      this.type = type;
      this.authURL = authURL;
   }

   @XmlElement
   public String getAuthURL() {
      return this.authURL;
   }

   public void setAuthURL(String authURL) {
      this.authURL = authURL;
   }

   @XmlElement
   public Type getType() {
      return this.type;
   }

   public void setType(Type type) {
      this.type = type;
   }

   public AuthenticationInfo clone() {
      return new AuthenticationInfo(this.getType(), this.getAuthURL());
   }

   public static enum Type {
      NONE,
      WEB_SERVICE,
      EITHER;
   }
}
