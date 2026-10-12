package org.jdesktop.wonderland.common.cell;

import org.jdesktop.wonderland.common.comms.ConnectionType;

public class CellCacheConnectionType extends ConnectionType {
   public static final ConnectionType CLIENT_TYPE = new CellCacheConnectionType("__CellCacheClient");

   private CellCacheConnectionType(String type) {
      super(type);
   }
}
