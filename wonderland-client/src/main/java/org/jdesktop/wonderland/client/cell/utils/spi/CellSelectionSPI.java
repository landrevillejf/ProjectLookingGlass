package org.jdesktop.wonderland.client.cell.utils.spi;

import org.jdesktop.wonderland.client.cell.registry.spi.CellFactorySPI;
import org.jdesktop.wonderland.client.cell.utils.CellCreationException;
import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public interface CellSelectionSPI {
   CellFactorySPI getCellSelection(String var1) throws CellCreationException;
}
