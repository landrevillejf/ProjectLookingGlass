package org.jdesktop.wonderland.common.cell.view;

import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.state.CellClientState;

public class ViewCellClientState extends CellClientState {
   private WonderlandIdentity identity;

   public ViewCellClientState() {
   }

   public ViewCellClientState(WonderlandIdentity identity) {
      this.identity = identity;
   }

   public WonderlandIdentity getIdentity() {
      return this.identity;
   }
}
