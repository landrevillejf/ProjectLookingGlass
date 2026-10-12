package org.jdesktop.wonderland.common.login;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.logging.Logger;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;

class AuthenticationServiceImpl implements AuthenticationService {
   private static final Logger logger = Logger.getLogger(AuthenticationServiceImpl.class.getName());
   private String authUrl;
   private String username;
   private String token;
   private String cookieName;

   public AuthenticationServiceImpl(String authUrl, String username) throws AuthenticationException {
      this.authUrl = authUrl;
      this.username = username;
      this.cookieName = this.getCookieName();
   }

   public String getAuthenticationURL() {
      return this.authUrl;
   }

   public String getUsername() {
      return this.username;
   }

   public void setUsername(String username) {
      this.username = username;
   }

   public String getAuthenticationToken() {
      return this.token;
   }

   public void setAuthenticationToken(String token) {
      this.token = token;
   }

   public void secureURLConnection(HttpURLConnection conn) {
      String existingCookies = conn.getRequestProperty("Cookie");
      StringBuffer cookieBuf;
      if (existingCookies == null) {
         cookieBuf = new StringBuffer();
      } else {
         cookieBuf = new StringBuffer(existingCookies);
      }

      try {
         cookieBuf.append(this.cookieName);
         cookieBuf.append("=");
         cookieBuf.append(URLEncoder.encode(this.getAuthenticationToken(), "UTF-8"));
         cookieBuf.append(";");
      } catch (UnsupportedEncodingException uee) {
         throw new IllegalStateException(uee);
      }

      conn.setRequestProperty("Cookie", cookieBuf.toString());
   }

   public boolean isTokenValid() throws AuthenticationException {
      return this.isTokenValid(this.getAuthenticationToken());
   }

   public boolean isTokenValid(String token) throws AuthenticationException {
      try {
         URL u = new URL(this.getAuthenticationURL() + "/isTokenValid");
         HttpURLConnection uc = (HttpURLConnection)u.openConnection();
         uc.setRequestMethod("POST");
         uc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
         uc.setDoOutput(true);
         uc.setDoInput(true);
         PrintWriter pr = new PrintWriter(new OutputStreamWriter(uc.getOutputStream()));
         pr.append("tokenid=" + URLEncoder.encode(token, "UTF-8"));
         pr.close();
         logger.fine("IsTokenValid response: " + uc.getResponseCode() + ": " + uc.getResponseMessage());
         BufferedReader br = new BufferedReader(new InputStreamReader(uc.getInputStream()));

         String line;
         while((line = br.readLine()) != null) {
            logger.finest("IsTokenValid response line: " + line);
            if (line.startsWith("boolean=")) {
               return Boolean.parseBoolean(line.substring("boolean=".length()));
            }
         }
      } catch (IOException ioe) {
         throw new AuthenticationException(ioe);
      }

      throw new AuthenticationException("No validation in response");
   }

   public String getCookieName() throws AuthenticationException {
      try {
         URL u = new URL(this.getAuthenticationURL() + "/getCookieNameForToken");
         HttpURLConnection uc = (HttpURLConnection)u.openConnection();
         uc.setDoInput(true);
         logger.fine("GetCookieName response: " + uc.getResponseCode() + " : " + uc.getResponseMessage());
         BufferedReader br = new BufferedReader(new InputStreamReader(uc.getInputStream()));

         String line;
         while((line = br.readLine()) != null) {
            logger.finest("GetCookieName response line: " + line);
            if (line.startsWith("string=")) {
               return line.substring("string=".length());
            }
         }
      } catch (IOException ioe) {
         throw new AuthenticationException(ioe);
      }

      throw new AuthenticationException("No cookie name in service output");
   }

   public Attributes getAttributes(String token, String... attributeNames) throws AuthenticationException {
      BasicAttributes out = new BasicAttributes();

      try {
         URL u = new URL(this.getAuthenticationURL() + "/attributes");
         HttpURLConnection uc = (HttpURLConnection)u.openConnection();
         uc.setRequestMethod("POST");
         uc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
         uc.setDoOutput(true);
         uc.setDoInput(true);
         PrintWriter pr = new PrintWriter(new OutputStreamWriter(uc.getOutputStream()));
         pr.append("subjectid=" + URLEncoder.encode(token, "UTF-8"));
         if (attributeNames != null && attributeNames.length > 0) {
            for(String name : attributeNames) {
               pr.append("&attribute_names=" + name);
            }
         }

         pr.close();
         logger.fine("GetAttributes Response: " + uc.getResponseCode() + ": " + uc.getResponseMessage());
         BufferedReader br = new BufferedReader(new InputStreamReader(uc.getInputStream()));
         this.parseAttributes(br, "userdetails", out);
         return out;
      } catch (IOException ioe) {
         throw new AuthenticationException(ioe);
      }
   }

   public Attributes read(String userId, String... attributeNames) throws AuthenticationException {
      BasicAttributes out = new BasicAttributes();

      try {
         URL u = new URL(this.getAuthenticationURL() + "/read");
         HttpURLConnection uc = (HttpURLConnection)u.openConnection();
         uc.setRequestMethod("POST");
         uc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
         uc.setDoOutput(true);
         uc.setDoInput(true);
         PrintWriter pr = new PrintWriter(new OutputStreamWriter(uc.getOutputStream()));
         pr.append("name=" + userId);
         pr.append("&admin=" + URLEncoder.encode(this.getAuthenticationToken(), "UTF-8"));
         if (attributeNames != null && attributeNames.length > 0) {
            for(String name : attributeNames) {
               pr.append("&attribute_names=" + name);
            }
         }

         pr.close();
         logger.fine("Read Response: " + uc.getResponseCode() + ": " + uc.getResponseMessage());
         BufferedReader br = new BufferedReader(new InputStreamReader(uc.getInputStream()));
         this.parseAttributes(br, "identitydetails", out);
         return out;
      } catch (IOException ioe) {
         throw new AuthenticationException(ioe);
      }
   }

   public void logout() throws AuthenticationException {
      try {
         URL u = new URL(this.getAuthenticationURL() + "/logout");
         HttpURLConnection uc = (HttpURLConnection)u.openConnection();
         uc.setRequestMethod("POST");
         uc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
         uc.setDoOutput(true);
         uc.setDoInput(true);
         PrintWriter pr = new PrintWriter(new OutputStreamWriter(uc.getOutputStream()));
         pr.append("subjectid=" + URLEncoder.encode(this.getAuthenticationToken(), "UTF-8"));
         pr.close();
         logger.fine("Logout response: " + uc.getResponseCode() + ": " + uc.getResponseMessage());
      } catch (IOException ioe) {
         throw new AuthenticationException(ioe);
      }
   }

   private void parseAttributes(BufferedReader reader, String prefix, BasicAttributes attrs) throws IOException {
      String namePrefix = prefix + ".attribute.name=";
      String valuePrefix = prefix + ".attribute.value=";
      Attribute curAttr = null;

      String line;
      while((line = reader.readLine()) != null) {
         logger.finest("ParseAttributes response line: " + line);
         if (line.startsWith(namePrefix)) {
            String name = line.substring(namePrefix.length());
            curAttr = new BasicAttribute(name);
            attrs.put(curAttr);
         } else if (line.startsWith(valuePrefix)) {
            String value = line.substring(valuePrefix.length());
            curAttr.add(value);
         }
      }

   }
}
