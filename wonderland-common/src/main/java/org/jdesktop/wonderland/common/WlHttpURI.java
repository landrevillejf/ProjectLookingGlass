package org.jdesktop.wonderland.common;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

@ExperimentalAPI
public class WlHttpURI extends AssetURI {
   private URL url = null;

   public WlHttpURI(String uri) throws URISyntaxException, MalformedURLException {
      super(uri);
      this.url = uriToURL(uri);
   }

   public WlHttpURI(URL url) throws URISyntaxException, MalformedURLException {
      super(urlToURI(url));
      this.url = url;
   }

   public String getRelativeCachePath() {
      return "wlhttp" + File.separator + this.url.getHost() + File.separator + this.url.getPath();
   }

   public void setServerHostAndPort(String hostNameAndPort) {
   }

   public URL toURL() throws MalformedURLException {
      return this.url;
   }

   public String getBaseURL() {
      return this.url.getPort() == -1 ? this.url.getProtocol() + "://" + this.url.getHost() : this.url.getProtocol() + "://" + this.url.getHost() + ":" + this.url.getPort();
   }

   public String getRelativePath() {
      return this.url.getPath();
   }

   private static URL uriToURL(String uri) throws MalformedURLException {
      if (uri != null && uri.startsWith("wlhttp://")) {
         String url = uri.substring(2);
         return new URL(url);
      } else {
         throw new MalformedURLException("Invalid wlhttp URI " + uri);
      }
   }

   private static String urlToURI(URL url) throws MalformedURLException {
      if (url != null && url.getProtocol().equals("http")) {
         String uri = "wl" + url.toExternalForm();
         return uri;
      } else {
         throw new MalformedURLException("Invalid http URL " + url.toExternalForm());
      }
   }
}
