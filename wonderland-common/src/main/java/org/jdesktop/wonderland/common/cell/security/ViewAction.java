package org.jdesktop.wonderland.common.cell.security;

import java.util.ResourceBundle;
import org.jdesktop.wonderland.common.security.Action;

public class ViewAction extends Action {
   private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org/jdesktop/wonderland/common/cell/security/Bundle");
   private static final String NAME = "View";

   public ViewAction() {
      super("View", (Class)null, BUNDLE.getString("View"), BUNDLE.getString("View_Description"));
   }
}
