package org.jdesktop.wonderland.common.comms.messages;

import java.math.BigInteger;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.messages.Message;

public class SessionInitializationMessage extends Message {
   private BigInteger sessionID;
   private WonderlandIdentity userID;

   public SessionInitializationMessage(BigInteger sessionID, WonderlandIdentity userID) {
      this.sessionID = sessionID;
      this.userID = userID;
   }

   public BigInteger getSessionID() {
      return this.sessionID;
   }

   public WonderlandIdentity getUserID() {
      return this.userID;
   }
}
