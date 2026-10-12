package org.jdesktop.wonderland.common.cell.messages;

import com.jme.bounding.BoundingVolume;
import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.messages.Message;

@InternalAPI
public class CellHierarchyMessage extends Message {
   protected ActionType msgType;
   protected CellID cellID;
   private CellID parentID;
   private BoundingVolume localBounds;
   private BoundingVolume computedBounds;
   private String cellClassName;
   private CellTransform cellTransform;
   private CellClientState setupData;
   private String avatarID;
   private String cellName;
   private CellTransform cellLocal2VW;

   public CellHierarchyMessage(ActionType msgType, String cellClassName, BoundingVolume localBounds, CellID cellID, CellID parentID, CellTransform cellTransform, CellClientState setupData, String cellName) {
      this.msgType = msgType;
      this.cellClassName = cellClassName;
      this.cellID = cellID;
      this.parentID = parentID;
      this.localBounds = localBounds;
      this.cellTransform = cellTransform;
      this.setupData = setupData;
      this.cellName = cellName;
   }

   private CellHierarchyMessage(ActionType msgType) {
      this.msgType = msgType;
   }

   public CellHierarchyMessage() {
   }

   public ActionType getActionType() {
      return this.msgType;
   }

   public CellID getCellID() {
      return this.cellID;
   }

   public CellID getParentID() {
      return this.parentID;
   }

   public BoundingVolume getLocalBounds() {
      return this.localBounds;
   }

   public BoundingVolume getComputedBounds() {
      return this.computedBounds;
   }

   public String getCellClassName() {
      return this.cellClassName;
   }

   public CellTransform getCellTransform() {
      return this.cellTransform;
   }

   public CellClientState getSetupData() {
      return this.setupData;
   }

   private void setViewID(String avatarID) {
      this.avatarID = avatarID;
   }

   public String getViewID() {
      return this.avatarID;
   }

   public String getCellName() {
      return this.cellName;
   }

   public static CellHierarchyMessage newSetAvatarMessage(String avatarID) {
      CellHierarchyMessage ret = new CellHierarchyMessage(CellHierarchyMessage.ActionType.SET_VIEW);
      ret.setViewID(avatarID);
      return ret;
   }

   public static CellHierarchyMessage newLoadClientAvatar(CellID cellID) {
      CellHierarchyMessage ret = new CellHierarchyMessage(CellHierarchyMessage.ActionType.LOAD_CLIENT_AVATAR);
      return ret;
   }

   public static enum ActionType {
      LOAD_CELL,
      UNLOAD_CELL,
      CHANGE_PARENT,
      DELETE_CELL,
      CONFIGURE_CELL,
      SET_VIEW,
      LOAD_CLIENT_AVATAR;
   }
}
