package org.jdesktop.wonderland.common.cell.security;

import java.util.ResourceBundle;
import org.jdesktop.wonderland.common.security.Action;

public class ChildrenAction extends Action {
   private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org/jdesktop/wonderland/common/cell/security/Bundle");
   private static final String NAME = "ChangeCellChildren";

   public ChildrenAction() {
      super("ChangeCellChildren", ModifyAction.class, BUNDLE.getString("ChangeCellChildren_DisplayName"), BUNDLE.getString("ChangeCellChildren_Description"));
   }
}
