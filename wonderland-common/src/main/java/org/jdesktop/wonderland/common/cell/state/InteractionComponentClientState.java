package org.jdesktop.wonderland.common.cell.state;

public class InteractionComponentClientState extends CellComponentClientState {
   private boolean collidable;
   private boolean selectable;

   public boolean isCollidable() {
      return this.collidable;
   }

   public void setCollidable(boolean collidable) {
      this.collidable = collidable;
   }

   public boolean isSelectable() {
      return this.selectable;
   }

   public void setSelectable(boolean pickable) {
      this.selectable = pickable;
   }
}
