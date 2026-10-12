package org.jdesktop.wonderland.common.cell.component.state;

import java.io.Serializable;

public class PhysicsProperties implements Serializable {
   private float mass = 0.0F;

   public float getMass() {
      return this.mass;
   }

   public void setMass(float mass) {
      this.mass = mass;
   }
}
