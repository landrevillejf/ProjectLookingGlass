package org.jdesktop.wonderland.common.modules;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import org.jdesktop.wonderland.common.login.AuthenticationManager;
import org.jdesktop.wonderland.common.login.AuthenticationService;

public class ModuleUploader {
   private URL baseURL;
   private URL uploadURL;
   private String authURL;
   private static final String UPLOAD_SERVLET = "wonderland-web-modules/ModuleUploadServlet";

   public ModuleUploader(URL baseURL) throws MalformedURLException {
      this.baseURL = baseURL;
      this.uploadURL = new URL(baseURL, "wonderland-web-modules/ModuleUploadServlet");
   }

   protected URL getBaseURL() {
      return this.baseURL;
   }

   protected URL getUploadURL() {
      return this.uploadURL;
   }

   public void setAuthURL(String authURL) {
      this.authURL = authURL;
   }

   public String getAuthURL() {
      return this.authURL;
   }

   public void upload(File module) throws IOException {
      String boundary = MultiPartFormOutputStream.createBoundary();
      URLConnection uc = MultiPartFormOutputStream.createConnection(this.getUploadURL());
      if (!(uc instanceof HttpURLConnection)) {
         throw new IllegalStateException("Http URL required: " + this.getUploadURL());
      } else {
         HttpURLConnection conn = (HttpURLConnection)uc;
         conn.setRequestProperty("Accept", "*/*");
         conn.setRequestProperty("Content-Type", MultiPartFormOutputStream.getContentType(boundary));
         conn.setRequestProperty("Connection", "Keep-Alive");
         conn.setRequestProperty("Cache-Control", "no-cache");
         conn.setRequestProperty("Redirect", "false");
         if (this.getAuthURL() != null) {
            AuthenticationService as = AuthenticationManager.get(this.getAuthURL());
            if (as != null) {
               as.secureURLConnection(conn);
            }
         }

         MultiPartFormOutputStream up = new MultiPartFormOutputStream(conn.getOutputStream(), boundary);
         up.writeFile("moduleJAR", "application/java-archive", module.getName(), (InputStream)(new FileInputStream(module)));
         up.close();
         int responseCode = conn.getResponseCode();
         if (responseCode != 200) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuffer resp = new StringBuffer();

            String line;
            while((line = reader.readLine()) != null) {
               resp.append(line + "\n");
            }

            throw new IOException("Bad server response: " + responseCode + "\n" + resp.toString());
         }
      }
   }
}
