package org.jdesktop.wonderland.common.comms.messages;

import java.util.Properties;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;

public class AttachClientMessage extends Message {
   private ConnectionType type;
   private Properties properties;

   public AttachClientMessage(ConnectionType type, Properties properties) {
      this.type = type;
      this.properties = properties;
   }

   public ConnectionType getClientType() {
      return this.type;
   }

   public Properties getProperties() {
      return this.properties;
   }
}
