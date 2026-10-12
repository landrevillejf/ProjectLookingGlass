package org.jdesktop.wonderland.common.cell.messages;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.security.MoveAction;
import org.jdesktop.wonderland.common.security.annotation.Actions;

@Actions({MoveAction.class})
@InternalAPI
public class MovableMessage extends CellMessage {
   private Vector3f translation;
   private Quaternion rotation;
   private float scale = 1.0F;
   private ActionType actionType;

   protected MovableMessage(CellID cellID, ActionType actionType) {
      super(cellID);
      this.actionType = actionType;
   }

   public Vector3f getTranslation() {
      return this.translation;
   }

   protected void setTranslation(Vector3f locationVW) {
      this.translation = locationVW;
   }

   public Quaternion getRotation() {
      return this.rotation;
   }

   protected void setRotation(Quaternion orientation) {
      this.rotation = orientation;
   }

   public float getScale() {
      return this.scale;
   }

   public void setScale(float scale) {
      this.scale = scale;
   }

   public ActionType getActionType() {
      return this.actionType;
   }

   public static MovableMessage newMovedMessage(CellID cellID, CellTransform transform) {
      MovableMessage ret = new MovableMessage(cellID, MovableMessage.ActionType.MOVE_REQUEST);
      ret.setTranslation(transform.getTranslation((Vector3f)null));
      ret.setRotation(transform.getRotation((Quaternion)null));
      ret.setScale(transform.getScaling());
      return ret;
   }

   public static MovableMessage newMovedMessage(CellID cellID, Vector3f translation, Quaternion rotation) {
      return newMovedMessage(cellID, translation, rotation, 1.0F);
   }

   public static MovableMessage newMovedMessage(CellID cellID, Vector3f translation, Quaternion rotation, float scale) {
      MovableMessage ret = new MovableMessage(cellID, MovableMessage.ActionType.MOVED);
      ret.setTranslation(translation);
      ret.setRotation(rotation);
      ret.setScale(scale);
      return ret;
   }

   public static MovableMessage newMoveRequestMessage(CellID cellID, CellTransform transform) {
      return newMoveRequestMessage(cellID, transform.getTranslation((Vector3f)null), transform.getRotation((Quaternion)null), transform.getScaling());
   }

   public static MovableMessage newMoveRequestMessage(CellID cellID, Vector3f translation, Quaternion rotation) {
      return newMoveRequestMessage(cellID, translation, rotation, 1.0F);
   }

   public static MovableMessage newMoveRequestMessage(CellID cellID, Vector3f translation, Quaternion rotation, float scale) {
      MovableMessage ret = new MovableMessage(cellID, MovableMessage.ActionType.MOVE_REQUEST);
      ret.setTranslation(translation);
      ret.setRotation(rotation);
      ret.setScale(scale);
      return ret;
   }

   public CellTransform getCellTransform() {
      return new CellTransform(this.rotation, this.translation, this.scale);
   }

   public static enum ActionType {
      MOVED,
      MOVE_REQUEST;
   }
}
