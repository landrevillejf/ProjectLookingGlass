package org.jdesktop.wonderland.common.login;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class AuthenticationManager {
   private static final Logger logger = Logger.getLogger(AuthenticationManager.class.getName());
   private static final Map services = new HashMap();

   public static synchronized AuthenticationService get(String url) {
      return getImpl(url);
   }

   public static synchronized AuthenticationService login(AuthenticationInfo info, String username, Object... credentials) throws AuthenticationException {
      AuthenticationServiceImpl out = getImpl(info.getAuthURL());
      if (out == null) {
         out = new AuthenticationServiceImpl(info.getAuthURL(), username);
         services.put(info.getAuthURL(), out);
      }

      if (out.getAuthenticationToken() != null && out.isTokenValid()) {
         return out;
      } else {
         String token;
         switch (info.getType()) {
            case NONE:
               token = noAuthLogin(info.getAuthURL(), username, credentials);
               break;
            case WEB_SERVICE:
               token = webServiceLogin(info.getAuthURL(), username, credentials);
               break;
            default:
               throw new AuthenticationException("Login type " + info.getType() + " not supported");
         }

         out.setUsername(username);
         out.setAuthenticationToken(token);
         return out;
      }
   }

   private static String noAuthLogin(String url, String username, Object... credentials) throws AuthenticationException {
      String fullname = null;
      String email = null;
      if (credentials.length == 1) {
         fullname = (String)credentials[0];
      } else {
         if (credentials.length != 2) {
            throw new AuthenticationException("Expected 1 or 2 arguments  to noAuthLogin(fullname, <email>), got " + credentials.length + ".");
         }

         fullname = (String)credentials[0];
         email = (String)credentials[1];
      }

      try {
         URL u = new URL(url + "/noauth");
         HttpURLConnection uc = (HttpURLConnection)u.openConnection();
         uc.setRequestMethod("POST");
         uc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
         uc.setDoOutput(true);
         uc.setDoInput(true);
         PrintWriter pr = new PrintWriter(new OutputStreamWriter(uc.getOutputStream()));
         pr.append("username=" + URLEncoder.encode(username, "UTF-8"));
         pr.append("&fullname=" + URLEncoder.encode(fullname, "UTF-8"));
         if (email != null) {
            pr.append("&email=" + URLEncoder.encode(email, "UTF-8"));
         }

         pr.close();
         logger.fine("NoAuthLogin Response: " + uc.getResponseCode() + " : " + uc.getResponseMessage());
         if (uc.getResponseCode() == 403) {
            throw new AuthenticationException("Invalid username or password");
         }

         BufferedReader br = new BufferedReader(new InputStreamReader(uc.getInputStream()));

         String line;
         while((line = br.readLine()) != null) {
            logger.finest("NoAuthLogin response line: " + line);
            if (line.startsWith("string=")) {
               return line.substring("string=".length());
            }
         }
      } catch (IOException ioe) {
         throw new AuthenticationException(ioe);
      }

      throw new AuthenticationException("No token returned");
   }

   private static String webServiceLogin(String url, String username, Object... credentials) throws AuthenticationException {
      String password = null;
      if (credentials.length == 1) {
         password = (String)credentials[0];

         try {
            URL u = new URL(url + "/authenticate");
            HttpURLConnection uc = (HttpURLConnection)u.openConnection();
            uc.setRequestMethod("POST");
            uc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            uc.setDoOutput(true);
            uc.setDoInput(true);
            PrintWriter pr = new PrintWriter(new OutputStreamWriter(uc.getOutputStream()));
            pr.append("username=" + URLEncoder.encode(username, "UTF-8"));
            pr.append("&password=" + URLEncoder.encode(password, "UTF-8"));
            pr.close();
            logger.fine("WebServiceLogin Response: " + uc.getResponseCode() + " : " + uc.getResponseMessage());
            if (uc.getResponseCode() == 403) {
               throw new AuthenticationException("Invalid username or password");
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(uc.getInputStream()));

            String line;
            while((line = br.readLine()) != null) {
               logger.finest("WebService response line: " + line);
               if (line.startsWith("string=")) {
                  return line.substring("string=".length());
               }
            }
         } catch (IOException ioe) {
            throw new AuthenticationException(ioe);
         }

         throw new AuthenticationException("No token returned");
      } else {
         throw new AuthenticationException("Expected 1 argument  to webServiceLogin(password), got " + credentials.length + ".");
      }
   }

   private static synchronized AuthenticationServiceImpl getImpl(String url) {
      return (AuthenticationServiceImpl)services.get(url);
   }
}
