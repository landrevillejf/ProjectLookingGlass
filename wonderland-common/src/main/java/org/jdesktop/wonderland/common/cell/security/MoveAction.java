package org.jdesktop.wonderland.common.cell.security;

import java.util.ResourceBundle;
import org.jdesktop.wonderland.common.security.Action;

public class MoveAction extends Action {
   private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org/jdesktop/wonderland/common/cell/security/Bundle");
   private static final String NAME = "Move";

   public MoveAction() {
      super("Move", ModifyAction.class, BUNDLE.getString("Move"), BUNDLE.getString("Move_Description"));
   }
}
