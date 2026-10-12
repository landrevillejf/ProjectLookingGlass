package org.jdesktop.wonderland.common.cell.security;

import java.util.ResourceBundle;
import org.jdesktop.wonderland.common.security.Action;

public class ComponentAction extends Action {
   private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org/jdesktop/wonderland/common/cell/security/Bundle");
   private static final String NAME = "ChangeCellComponent";

   public ComponentAction() {
      super("ChangeCellComponent", ModifyAction.class, BUNDLE.getString("Change_Components"), BUNDLE.getString("Change_Components_Description"));
   }
}
