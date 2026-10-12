package org.jdesktop.wonderland.common;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ExperimentalAPI
public abstract class AssetURI implements Serializable {
   private String uri = null;
   private static Map uriMap = new HashMap();

   public AssetURI() {
   }

   public AssetURI(String uri) {
      this.uri = uri;
   }

   public String getURI() {
      return this.uri;
   }

   public abstract String getRelativeCachePath();

   public abstract void setServerHostAndPort(String var1);

   public abstract URL toURL() throws MalformedURLException;

   public String toString() {
      return this.toExternalForm();
   }

   public String toExternalForm() {
      return this.uri;
   }

   public String getProtocol() {
      int index = this.uri.indexOf("://");
      return index == -1 ? null : this.uri.substring(0, index);
   }

   public static AssetURI uriFactory(String uri) {
      int index = uri.indexOf("://");
      if (index == -1) {
         return null;
      } else {
         String protocol = uri.substring(0, index);
         Class clazz = (Class)uriMap.get(protocol);
         if (clazz == null) {
            return null;
         } else {
            try {
               Constructor constructor = clazz.getConstructor(String.class);
               return (AssetURI)constructor.newInstance(uri);
            } catch (Exception ex) {
               Logger.getLogger(AssetURI.class.getName()).log(Level.WARNING, (String)null, ex);
               return null;
            }
         }
      }
   }

   public boolean equals(Object obj) {
      if (obj == null) {
         return false;
      } else if (this.getClass() != obj.getClass()) {
         return false;
      } else {
         AssetURI other = (AssetURI)obj;
         if (this.uri == null) {
            if (other.uri != null) {
               return false;
            }
         } else if (!this.uri.equals(other.uri)) {
            return false;
         }

         return true;
      }
   }

   public int hashCode() {
      int hash = 7;
      hash = 79 * hash + (this.uri != null ? this.uri.hashCode() : 0);
      return hash;
   }

   static {
      uriMap.put("wla", ArtURI.class);
      uriMap.put("wlhttp", WlHttpURI.class);
      uriMap.put("wlj", JarURI.class);
      uriMap.put("wlcontent", ContentURI.class);
   }
}
