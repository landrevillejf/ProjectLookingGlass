package org.jdesktop.wonderland.common.cell.state;

import com.jme.bounding.BoundingSphere;
import com.jme.bounding.BoundingVolume;
import com.jme.math.Vector3f;
import java.io.Serializable;

public class BoundingVolumeHint implements Serializable {
   private boolean doSystemPlacement = true;
   private BoundingVolume boundsHint;

   public BoundingVolumeHint() {
      this.boundsHint = new BoundingSphere(1.0F, Vector3f.ZERO);
   }

   public BoundingVolumeHint(boolean doSystemPlacement, BoundingVolume boundsHint) {
      this.boundsHint = new BoundingSphere(1.0F, Vector3f.ZERO);
      this.doSystemPlacement = doSystemPlacement;
      this.boundsHint = boundsHint;
   }

   public boolean isDoSystemPlacement() {
      return this.doSystemPlacement;
   }

   public BoundingVolume getBoundsHint() {
      return this.boundsHint;
   }
}
