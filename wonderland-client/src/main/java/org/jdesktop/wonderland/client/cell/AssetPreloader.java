package org.jdesktop.wonderland.client.cell;

import java.io.InputStream;
import java.net.URL;
import java.util.List;

public interface AssetPreloader {
   List<URL> getAssets();

   List<URL> assetLoaded(URL var1, InputStream var2);
}
