package org.jdesktop.wonderland.common.cell.view;

import org.jdesktop.wonderland.common.auth.WonderlandIdentity;

/** @deprecated */
public class AvatarCellClientState extends ViewCellClientState {
   private String avatarConfigURL = null;

   public AvatarCellClientState(WonderlandIdentity identity) {
      super(identity);
      this.avatarConfigURL = this.avatarConfigURL;
   }

   public String getAvatarConfigURL() {
      return this.avatarConfigURL;
   }
}
