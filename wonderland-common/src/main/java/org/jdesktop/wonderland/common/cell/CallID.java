package org.jdesktop.wonderland.common.cell;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class CallID {
   public static String getCallID(CellID cellID) {
      String callID = cellID.toString();
      if (callID.equals("0")) {
         callID = "00";
      }

      return callID;
   }
}
