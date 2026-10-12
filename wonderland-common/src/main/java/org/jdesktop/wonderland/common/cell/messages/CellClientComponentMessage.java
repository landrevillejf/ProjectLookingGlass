package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;

public class CellClientComponentMessage extends CellMessage {
   private CellComponentClientState clientState = null;
   private String clientClassName = null;
   private ComponentAction action = null;

   public CellClientComponentMessage(CellID cellID, String className, CellComponentClientState clientState) {
      super(cellID);
      this.clientState = clientState;
      this.clientClassName = className;
   }

   public CellClientComponentMessage(CellID cellID, String className) {
      super(cellID);
      this.clientClassName = className;
      this.clientState = null;
   }

   public ComponentAction getComponentAction() {
      return this.action;
   }

   public CellComponentClientState getCellComponentClientState() {
      return this.clientState;
   }

   public String getCellComponentClientClassName() {
      return this.clientClassName;
   }

   public static CellClientComponentMessage newAddMessage(CellID cellID, String className, CellComponentClientState clientState) {
      CellClientComponentMessage msg = new CellClientComponentMessage(cellID, className, clientState);
      msg.action = CellClientComponentMessage.ComponentAction.ADD;
      return msg;
   }

   public static CellClientComponentMessage newRemoveMessage(CellID cellID, String className) {
      CellClientComponentMessage msg = new CellClientComponentMessage(cellID, className);
      msg.action = CellClientComponentMessage.ComponentAction.REMOVE;
      return msg;
   }

   public static enum ComponentAction {
      ADD,
      REMOVE;
   }
}
