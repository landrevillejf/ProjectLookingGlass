package org.jdesktop.wonderland.common.login;

import java.net.HttpURLConnection;

public interface CredentialManager {
   String getAuthenticationURL();

   String getUsername();

   String getAuthenticationToken();

   void secureURLConnection(HttpURLConnection var1);
}
