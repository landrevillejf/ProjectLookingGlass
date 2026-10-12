package org.jdesktop.wonderland.client.cell.registry.spi;

import org.jdesktop.wonderland.common.cell.state.CellComponentServerState;

public interface CellComponentFactorySPI {
   <T extends CellComponentServerState> T getDefaultCellComponentServerState();

   String getDisplayName();

   String getDescription();
}
