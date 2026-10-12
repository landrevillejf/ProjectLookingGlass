package org.jdesktop.wonderland.common.cell.state;

public class MovableAvatarComponentClientState extends CellComponentClientState {
   private int trigger = -1;
   private String animationName = null;

   public void setTrigger(int trigger) {
      this.trigger = trigger;
   }

   public int getTrigger() {
      return this.trigger;
   }

   public void setAnimationName(String animationName) {
      this.animationName = animationName;
   }

   public String getAnimationName() {
      return this.animationName;
   }
}
