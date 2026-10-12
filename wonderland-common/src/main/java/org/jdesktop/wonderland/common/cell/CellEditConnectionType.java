package org.jdesktop.wonderland.common.cell;

import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.comms.ConnectionType;

@InternalAPI
public class CellEditConnectionType extends ConnectionType {
   public static final ConnectionType CLIENT_TYPE = new CellEditConnectionType("__CellEditClient");

   private CellEditConnectionType(String type) {
      super(type);
   }
}
