package org.jdesktop.wonderland.common.login;

import javax.naming.directory.Attributes;

public interface AuthenticationService extends CredentialManager {
   boolean isTokenValid() throws AuthenticationException;

   boolean isTokenValid(String var1) throws AuthenticationException;

   String getCookieName() throws AuthenticationException;

   Attributes getAttributes(String var1, String... var2) throws AuthenticationException;

   Attributes read(String var1, String... var2) throws AuthenticationException;

   void logout() throws AuthenticationException;
}
