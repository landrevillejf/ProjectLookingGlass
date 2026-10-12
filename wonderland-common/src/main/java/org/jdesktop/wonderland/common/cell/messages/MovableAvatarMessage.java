package org.jdesktop.wonderland.common.cell.messages;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;

public class MovableAvatarMessage extends MovableMessage {
   private int trigger;
   private boolean pressed;
   private String animationName = null;
   private float height;
   private boolean collision;

   protected MovableAvatarMessage(CellID cellID, MovableMessage.ActionType action) {
      super(cellID, action);
   }

   public static MovableAvatarMessage newMoveRequestMessage(CellID cellID, Vector3f position, Quaternion rotation, int trigger, boolean pressed, String animationName, float height, boolean collision) {
      MovableAvatarMessage ret = new MovableAvatarMessage(cellID, MovableMessage.ActionType.MOVE_REQUEST);
      ret.setTranslation(position);
      ret.setRotation(rotation);
      ret.setTrigger(trigger);
      ret.setPressed(pressed);
      ret.setAnimationName(animationName);
      ret.setHeight(height);
      ret.setCollision(collision);
      return ret;
   }

   public static CellMessage newMovedMessage(CellID cellID, CellTransform transform, int trigger, boolean pressed, String animationName, float height, boolean collision) {
      MovableAvatarMessage ret = new MovableAvatarMessage(cellID, MovableMessage.ActionType.MOVED);
      ret.setTranslation(transform.getTranslation((Vector3f)null));
      ret.setRotation(transform.getRotation((Quaternion)null));
      ret.setTrigger(trigger);
      ret.setPressed(pressed);
      ret.setAnimationName(animationName);
      ret.setHeight(height);
      ret.setCollision(collision);
      return ret;
   }

   public int getTrigger() {
      return this.trigger;
   }

   protected void setTrigger(int trigger) {
      this.trigger = trigger;
   }

   public boolean isPressed() {
      return this.pressed;
   }

   protected void setPressed(boolean pressed) {
      this.pressed = pressed;
   }

   protected void setAnimationName(String animationName) {
      this.animationName = animationName;
   }

   public String getAnimationName() {
      return this.animationName;
   }

   public float getHeight() {
      return this.height;
   }

   protected void setHeight(float height) {
      this.height = height;
   }

   public boolean isCollision() {
      return this.collision;
   }

   protected void setCollision(boolean collision) {
      this.collision = collision;
   }
}
