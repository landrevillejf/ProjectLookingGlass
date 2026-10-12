package org.jdesktop.wonderland.common.cell.security;

import java.util.ResourceBundle;
import org.jdesktop.wonderland.common.security.Action;

public class ModifyAction extends Action {
   private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org/jdesktop/wonderland/common/cell/security/Bundle");
   private static final String NAME = "Modify";

   public ModifyAction() {
      super("Modify", (Class)null, BUNDLE.getString("Modify"), BUNDLE.getString("Modify_Description"));
   }
}
