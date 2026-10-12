package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.state.CellComponentServerState;
import org.jdesktop.wonderland.common.security.annotation.Actions;

@Actions({org.jdesktop.wonderland.common.cell.security.ComponentAction.class})
public class CellServerComponentMessage extends CellMessage {
   private CellComponentServerState serverState = null;
   private String serverClassName = null;
   private ComponentAction action = null;

   public CellServerComponentMessage(CellID cellID, CellComponentServerState serverState) {
      super(cellID);
      this.serverState = serverState;
      this.serverClassName = serverState.getServerComponentClassName();
   }

   public CellServerComponentMessage(CellID cellID, String className) {
      super(cellID);
      this.serverClassName = className;
      this.serverState = null;
   }

   public ComponentAction getComponentAction() {
      return this.action;
   }

   public CellComponentServerState getCellComponentServerState() {
      return this.serverState;
   }

   public String getCellComponentServerClassName() {
      return this.serverClassName;
   }

   public static CellServerComponentMessage newAddMessage(CellID cellID, CellComponentServerState serverState) {
      CellServerComponentMessage msg = new CellServerComponentMessage(cellID, serverState);
      msg.action = CellServerComponentMessage.ComponentAction.ADD;
      return msg;
   }

   public static CellServerComponentMessage newAddMessage(CellID cellID, String className) {
      CellServerComponentMessage msg = new CellServerComponentMessage(cellID, className);
      msg.action = CellServerComponentMessage.ComponentAction.ADD;
      return msg;
   }

   public static CellServerComponentMessage newRemoveMessage(CellID cellID, String className) {
      CellServerComponentMessage msg = new CellServerComponentMessage(cellID, className);
      msg.action = CellServerComponentMessage.ComponentAction.REMOVE;
      return msg;
   }

   public static enum ComponentAction {
      ADD,
      REMOVE;
   }
}
