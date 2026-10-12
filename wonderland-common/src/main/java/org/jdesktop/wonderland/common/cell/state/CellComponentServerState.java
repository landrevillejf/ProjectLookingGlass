package org.jdesktop.wonderland.common.cell.state;

import java.io.Serializable;

public abstract class CellComponentServerState implements CellState, Serializable {
   public abstract String getServerComponentClassName();
}
