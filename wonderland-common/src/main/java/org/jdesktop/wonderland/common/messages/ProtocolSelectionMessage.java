package org.jdesktop.wonderland.common.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.comms.ProtocolVersion;

@ExperimentalAPI
public class ProtocolSelectionMessage extends Message {
   private String protocolName;
   private ProtocolVersion protocolVersion;

   public ProtocolSelectionMessage(String protocolName, ProtocolVersion protocolVersion) {
      this.protocolName = protocolName;
      this.protocolVersion = protocolVersion;
   }

   public String getProtocolName() {
      return this.protocolName;
   }

   public void setProtocolName(String protocolName) {
      this.protocolName = protocolName;
   }

   public ProtocolVersion getProtocolVersion() {
      return this.protocolVersion;
   }

   public void setProtocolVersion(ProtocolVersion protocolVersion) {
      this.protocolVersion = protocolVersion;
   }
}
