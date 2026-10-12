package org.jdesktop.wonderland.common;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

@ExperimentalAPI
public abstract class ModuleURI extends AssetURI {
   private String protocol = null;
   private String moduleName = null;
   private String hostName = null;
   private int hostPort = -1;
   private String assetPath = null;

   public ModuleURI() {
   }

   public ModuleURI(String uri) throws URISyntaxException {
      super(uri);
      this.parseURI(uri);
   }

   public ModuleURI(String protocol, String moduleName, String hostName, int hostPort, String assetPath) {
      super(toURI(protocol, moduleName, hostName, hostPort, assetPath));
      this.protocol = protocol;
      this.moduleName = moduleName;
      this.hostName = hostName;
      this.hostPort = hostPort;
      this.assetPath = assetPath;
   }

   public ModuleURI(String protocol, String moduleName, String hostNameAndPort, String assetPath) {
      super(toURI(protocol, moduleName, hostNameAndPort, assetPath));
      this.protocol = protocol;
      this.moduleName = moduleName;
      this.assetPath = assetPath;
      this.parseHostNameAndPort(hostNameAndPort);
   }

   public URL toURL() throws MalformedURLException {
      return new URL(this.toString());
   }

   public String getModuleName() {
      return this.moduleName;
   }

   public String getAssetPath() {
      return this.assetPath;
   }

   public String getProtocol() {
      return this.protocol;
   }

   public int getHostPort() {
      return this.hostPort;
   }

   public String getHostName() {
      return this.hostName;
   }

   public String getServerURL() {
      if (this.hostName == null) {
         return null;
      } else {
         String url = "http://" + this.hostName;
         if (this.hostPort != -1) {
            url = url + ":" + this.hostPort;
         }

         return url;
      }
   }

   public void setServerHostAndPort(String hostNameAndPort) {
      this.parseHostNameAndPort(hostNameAndPort);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder(this.protocol + "://" + this.moduleName);
      if (this.hostName != null) {
         sb.append("@" + this.hostName);
         if (this.hostPort != -1) {
            sb.append(":" + this.hostPort);
         }
      }

      sb.append("/" + this.assetPath);
      return sb.toString();
   }

   public String toExternalForm() {
      return this.protocol + "://" + this.moduleName + "/" + this.assetPath;
   }

   private static String toURI(String protocol, String moduleName, String hostName, int hostPort, String assetPath) {
      StringBuilder sb = new StringBuilder(protocol + "://" + moduleName);
      if (hostName != null) {
         sb.append("@" + hostName);
         if (hostPort != -1) {
            sb.append(":" + hostPort);
         }
      }

      sb.append("/" + assetPath);
      return sb.toString();
   }

   private static String toURI(String protocol, String moduleName, String hostNameAndPort, String assetPath) {
      StringBuilder sb = new StringBuilder(protocol + "://" + moduleName);
      if (hostNameAndPort != null) {
         sb.append("@" + hostNameAndPort);
      }

      sb.append("/" + assetPath);
      return sb.toString();
   }

   public boolean equals(Object obj) {
      if (obj == null) {
         return false;
      } else if (this.getClass() != obj.getClass()) {
         return false;
      } else {
         ModuleURI other = (ModuleURI)obj;
         if (this.protocol == other.protocol || this.protocol != null && this.protocol.equals(other.protocol)) {
            if (this.moduleName == other.moduleName || this.moduleName != null && this.moduleName.equals(other.moduleName)) {
               return this.assetPath == other.assetPath || this.assetPath != null && this.assetPath.equals(other.assetPath);
            } else {
               return false;
            }
         } else {
            return false;
         }
      }
   }

   public int hashCode() {
      int hash = 7;
      hash = 47 * hash + (this.protocol != null ? this.protocol.hashCode() : 0);
      hash = 47 * hash + (this.moduleName != null ? this.moduleName.hashCode() : 0);
      hash = 47 * hash + (this.assetPath != null ? this.assetPath.hashCode() : 0);
      return hash;
   }

   private void parseURI(String uri) throws URISyntaxException {
      if (uri == null) {
         throw new URISyntaxException(uri, "URI is NULL");
      } else {
         int protocolIndex = uri.indexOf("://");
         if (protocolIndex != -1 && protocolIndex != 0) {
            this.protocol = uri.substring(0, protocolIndex);
            protocolIndex += 3;
            int atIndex = uri.indexOf("@", protocolIndex);
            int slashIndex = uri.indexOf("/", protocolIndex);
            if (atIndex != -1 && atIndex < slashIndex) {
               this.moduleName = uri.substring(protocolIndex, atIndex);
            } else {
               if (slashIndex == -1) {
                  throw new URISyntaxException(uri, "Cannot find module name in URI");
               }

               this.moduleName = uri.substring(protocolIndex, slashIndex);
            }

            if (atIndex != -1 && atIndex < slashIndex) {
               int colonIndex = uri.indexOf(":", atIndex + 1);
               if (colonIndex != -1 && colonIndex < slashIndex) {
                  this.hostName = uri.substring(atIndex + 1, colonIndex);

                  try {
                     this.hostPort = new Integer(uri.substring(colonIndex + 1, slashIndex));
                  } catch (NumberFormatException var7) {
                     this.hostPort = -1;
                     throw new URISyntaxException(uri, "Invalid Host port given in URI");
                  }
               } else {
                  this.hostName = uri.substring(atIndex + 1, slashIndex);
                  this.hostPort = -1;
               }
            }

            this.assetPath = uri.substring(slashIndex + 1, uri.length());
         } else {
            throw new URISyntaxException(uri, "URI does not contain a protocol");
         }
      }
   }

   private void parseHostNameAndPort(String hostNameAndPort) {
      if (hostNameAndPort == null) {
         this.hostName = null;
         this.hostPort = -1;
      } else {
         int colonIndex = hostNameAndPort.indexOf(":");
         if (colonIndex != -1) {
            this.hostName = hostNameAndPort.substring(0, colonIndex);

            try {
               this.hostPort = new Integer(hostNameAndPort.substring(colonIndex + 1, hostNameAndPort.length()));
            } catch (NumberFormatException var4) {
               this.hostPort = -1;
            }
         } else {
            this.hostName = hostNameAndPort;
            this.hostPort = -1;
         }

      }
   }

   public abstract String getRelativePathInModule();

   public String getRelativeCachePath() {
      return "module" + File.separator + this.getModuleName() + File.separator + this.getRelativePathInModule();
   }
}
