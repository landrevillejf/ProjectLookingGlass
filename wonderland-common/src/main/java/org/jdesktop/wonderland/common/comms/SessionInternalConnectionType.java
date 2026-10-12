package org.jdesktop.wonderland.common.comms;

public class SessionInternalConnectionType extends ConnectionType {
   public static final ConnectionType SESSION_INTERNAL_CLIENT_TYPE = new SessionInternalConnectionType();
   public static final short SESSION_INTERNAL_CLIENT_ID = -1;

   public SessionInternalConnectionType() {
      super("__WonderlandSessionInternalClient");
   }
}
