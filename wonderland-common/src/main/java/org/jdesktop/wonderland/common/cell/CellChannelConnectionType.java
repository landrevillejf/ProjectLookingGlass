package org.jdesktop.wonderland.common.cell;

import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.comms.ConnectionType;

@InternalAPI
public class CellChannelConnectionType extends ConnectionType {
   public static final ConnectionType CLIENT_TYPE = new CellChannelConnectionType("__CellClient");

   private CellChannelConnectionType(String type) {
      super(type);
   }
}
