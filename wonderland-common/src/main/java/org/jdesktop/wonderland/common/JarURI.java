package org.jdesktop.wonderland.common;

import java.net.URISyntaxException;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlJavaTypeAdapter(JarURIAdapter.class)
@ExperimentalAPI
public class JarURI extends ModuleURI {
   public JarURI() {
   }

   public JarURI(String uri) throws URISyntaxException {
      super(uri);
   }

   public JarURI(String moduleName, String hostNameAndPort, String assetPath) {
      super("wlj", moduleName, hostNameAndPort, assetPath);
   }

   public String getRelativePathInModule() {
      return this.getAssetPath();
   }
}
