package org.jdesktop.wonderland.client.cell.registry.spi;

import java.awt.Image;
import java.util.Properties;
import org.jdesktop.wonderland.common.cell.state.CellServerState;

public interface CellFactorySPI {
   String[] getExtensions();

   <T extends CellServerState> T getDefaultCellServerState(Properties var1);

   String getDisplayName();

   Image getPreviewImage();
}
