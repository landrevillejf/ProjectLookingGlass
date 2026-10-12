package org.jdesktop.wonderland.common.cell.messages;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@InternalAPI
public class MovableMessageResponse extends ResponseMessage {
   private Vector3f translation;
   private Quaternion rotation;
   private float scale = 1.0F;
   private ActionType actionType;

   private MovableMessageResponse(MessageID messageID, ActionType actionType) {
      super(messageID);
      this.actionType = actionType;
   }

   public Vector3f getTranslation() {
      return this.translation;
   }

   private void setLocation(Vector3f locationVW) {
      this.translation = locationVW;
   }

   public Quaternion getRotation() {
      return this.rotation;
   }

   public void setRotation(Quaternion rotation) {
      this.rotation = rotation;
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

   public static MovableMessageResponse newMoveModifiedMessage(MessageID msgID, Vector3f translation, Quaternion rotation, float scale) {
      MovableMessageResponse ret = new MovableMessageResponse(msgID, MovableMessageResponse.ActionType.MOVE_MODIFIED);
      ret.setLocation(translation);
      ret.setRotation(rotation);
      ret.setScale(scale);
      return ret;
   }

   public static enum ActionType {
      MOVE_MODIFIED;
   }
}
