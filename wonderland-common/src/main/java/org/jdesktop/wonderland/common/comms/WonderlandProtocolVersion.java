package org.jdesktop.wonderland.common.comms;

public class WonderlandProtocolVersion extends DefaultProtocolVersion {
   public static final String PROTOCOL_NAME = "wonderland_client";
   public static final WonderlandProtocolVersion VERSION = new WonderlandProtocolVersion();

   private WonderlandProtocolVersion() {
      super(0, 1, 0);
   }
}
