package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.messages.Message;

@ExperimentalAPI
public abstract class CellEditMessage extends Message {
   private EditType editType;

   protected CellEditMessage(EditType editType) {
      this.editType = editType;
   }

   public EditType getEditType() {
      return this.editType;
   }

   public void setEditType(EditType editType) {
      this.editType = editType;
   }

   public static enum EditType {
      CREATE_CELL,
      DELETE_CELL,
      DUPLICATE_CELL,
      REPARENT_CELL;
   }
}
