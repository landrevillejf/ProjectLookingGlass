package org.jdesktop.wonderland.common.cell.messages;

import java.math.BigInteger;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.messages.Message;

@ExperimentalAPI
public abstract class CellMessage extends Message {
   private CellID cellID;
   private BigInteger senderID;

   public CellMessage() {
      this((CellID)null);
   }

   public CellMessage(CellID cellID) {
      this.cellID = cellID;
   }

   public CellID getCellID() {
      return this.cellID;
   }

   public void setCellID(CellID cellID) {
      this.cellID = cellID;
   }

   public BigInteger getSenderID() {
      return this.senderID;
   }

   public void setSenderID(BigInteger senderID) {
      this.senderID = senderID;
   }
}
