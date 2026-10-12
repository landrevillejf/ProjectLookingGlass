package org.jdesktop.wonderland.common;

import javax.xml.bind.annotation.adapters.XmlAdapter;

@ExperimentalAPI
public class JarURIAdapter extends XmlAdapter<String, JarURI> {
   private String hostNameAndPort = null;

   public JarURIAdapter() {
   }

   public JarURIAdapter(String hostNameAndPort) {
      this.hostNameAndPort = hostNameAndPort;
   }

   public JarURI unmarshal(String uri) throws Exception {
      JarURI jarURI = new JarURI(uri);
      if (uri != null) {
         String moduleName = jarURI.getModuleName();
         String assetPath = jarURI.getAssetPath();
         jarURI = new JarURI(moduleName, this.hostNameAndPort, assetPath);
      }

      return jarURI;
   }

   public String marshal(JarURI jarURI) throws Exception {
      return jarURI.toString();
   }
}
