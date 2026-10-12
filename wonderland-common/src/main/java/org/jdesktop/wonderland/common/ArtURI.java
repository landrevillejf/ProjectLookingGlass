package org.jdesktop.wonderland.common;

import java.net.URISyntaxException;

@ExperimentalAPI
public class ArtURI extends ModuleURI {
   public ArtURI(String uri) throws URISyntaxException {
      super(uri);
   }

   public ArtURI(String moduleName, String hostNameAndPort, String assetPath) {
      super("wla", moduleName, hostNameAndPort, assetPath);
   }

   public String getRelativePathInModule() {
      return "art/" + this.getAssetPath();
   }
}
