package org.jdesktop.wonderland.common.cell;

import com.jme.bounding.BoundingBox;
import com.jme.bounding.BoundingVolume;
import com.jme.math.FastMath;
import com.jme.math.Vector3f;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.common.Math3DUtils;

public class ProximityListenerRecord implements Serializable {
   protected static final Logger logger = Logger.getLogger(ProximityListenerRecord.class.getName());
   protected ProximityListenerWrapper proximityListener;
   private BoundingVolume[] localProxBounds;
   private BoundingVolume[] worldProxBounds;
   protected Map lastContainerIndexMap = null;
   protected final Serializable lock = new Serializable() {
   };

   public ProximityListenerRecord() {
   }

   public ProximityListenerRecord(ProximityListenerWrapper proximityListener, BoundingVolume[] localBounds) {
      this.proximityListener = proximityListener;
      this.setProximityBounds(localBounds);
   }

   public void setProximityBounds(BoundingVolume[] localBounds) {
      this.localProxBounds = new BoundingVolume[localBounds.length];
      this.worldProxBounds = new BoundingVolume[localBounds.length];
      int i = 0;

      for(BoundingVolume b : localBounds) {
         this.localProxBounds[i] = b.clone((BoundingVolume)null);
         this.worldProxBounds[i] = b.clone((BoundingVolume)null);
         if (i > 0 && !Math3DUtils.encloses(this.localProxBounds[i - 1], this.localProxBounds[i])) {
            throw new IllegalArgumentException("Proximity Bounds incorrectly ordered");
         }

         ++i;
      }

   }

   public BoundingVolume[] getWorldBounds() {
      BoundingVolume[] out = new BoundingVolume[this.worldProxBounds.length];

      for(int i = 0; i < this.worldProxBounds.length; ++i) {
         this.worldProxBounds[i].clone(out[i]);
      }

      return out;
   }

   public void updateWorldBounds(CellTransform worldTransform) {
      if (this.localProxBounds != null) {
         int i = 0;
         synchronized(this.lock) {
            for(BoundingVolume lb : this.localProxBounds) {
               this.worldProxBounds[i] = lb.clone(this.worldProxBounds[i]);
               worldTransform.transform(this.worldProxBounds[i]);
               if (logger.isLoggable(Level.FINEST)) {
                  logger.finest("Update world bounds: " + this.worldProxBounds[i] + " on " + this);
               }

               ++i;
            }

         }
      }
   }

   public void viewCellMoved(CellID viewCellID, CellTransform viewCellTransform) {
      this.viewCellMoved(viewCellID, viewCellTransform, false);
   }

   public void cleanup(CellID viewCellID) {
      this.viewCellMoved(viewCellID, new CellTransform(), true);
   }

   protected void viewCellMoved(CellID viewCellID, CellTransform viewCellTransform, boolean cleanup) {
      Vector3f viewCellWorldTranslation = viewCellTransform.getTranslation((Vector3f)null);
      synchronized(this.lock) {
         int currentContainerIndex = -1;

         for(int i = 0; !cleanup && i < this.worldProxBounds.length; ++i) {
            if (logger.isLoggable(Level.FINEST)) {
               logger.finest("Checking if " + this.worldProxBounds[i] + " contains " + viewCellWorldTranslation + " on " + this);
            }

            boolean contains;
            if (this.worldProxBounds[i] instanceof BoundingBox) {
               contains = this.contains((BoundingBox)this.worldProxBounds[i], viewCellWorldTranslation);
            } else {
               contains = this.worldProxBounds[i].contains(viewCellWorldTranslation);
            }

            if (contains) {
               currentContainerIndex = i;
            } else {
               i = this.worldProxBounds.length;
            }
         }

         int lastContainerIndex = -1;
         if (this.getIndexMap().containsKey(viewCellID)) {
            lastContainerIndex = (Integer)this.getIndexMap().get(viewCellID);
         }

         if (lastContainerIndex != currentContainerIndex) {
            logger.warning("View cell " + viewCellID + " lastContainerIndex: " + lastContainerIndex + " currentContainerIndex: " + currentContainerIndex + " on " + this);
            if (currentContainerIndex < lastContainerIndex) {
               for(int l = lastContainerIndex; l > currentContainerIndex; --l) {
                  this.proximityListener.viewEnterExit(false, this.worldProxBounds[l], l, viewCellID);
               }

               if (currentContainerIndex == -1) {
                  this.getIndexMap().remove(viewCellID);
               } else {
                  this.getIndexMap().put(viewCellID, currentContainerIndex);
               }
            } else {
               for(int l = lastContainerIndex + 1; l <= currentContainerIndex; ++l) {
                  this.proximityListener.viewEnterExit(true, this.worldProxBounds[l], l, viewCellID);
               }

               this.getIndexMap().put(viewCellID, currentContainerIndex);
            }

         }
      }
   }

   private boolean contains(BoundingBox bounds, Vector3f point) {
      Vector3f center = bounds.getCenter();
      Vector3f extent = bounds.getExtent((Vector3f)null);
      return (double)(FastMath.abs(center.x - point.x) - extent.x) <= 0.01 && (double)(FastMath.abs(center.y - point.y) - extent.y) <= 0.01 && (double)(FastMath.abs(center.z - point.z) - extent.z) <= 0.01;
   }

   public void viewCellExited(CellID viewCellID) {
      synchronized(this.lock) {
         if (this.getIndexMap().containsKey(viewCellID)) {
            int lastContainerIndex = (Integer)this.getIndexMap().remove(viewCellID);

            for(int curIdx = lastContainerIndex; curIdx >= 0; --curIdx) {
               this.proximityListener.viewEnterExit(false, this.worldProxBounds[curIdx], curIdx, viewCellID);
            }
         }

      }
   }

   protected Map getIndexMap() {
      synchronized(this.lock) {
         if (this.lastContainerIndexMap == null) {
            this.lastContainerIndexMap = new HashMap();
         }
      }

      return this.lastContainerIndexMap;
   }

   public boolean equals(Object o) {
      if (!(o instanceof ProximityListenerRecord)) {
         return false;
      } else {
         ProximityListenerRecord plr = (ProximityListenerRecord)o;
         if (plr.proximityListener == null) {
            return this.proximityListener == null;
         } else {
            return plr.proximityListener.equals(this.proximityListener);
         }
      }
   }

   public int hashCode() {
      int hash = 7;
      hash = 89 * hash + (this.proximityListener != null ? this.proximityListener.hashCode() : 0);
      return hash;
   }

   public interface ProximityListenerWrapper extends Serializable {
      void viewEnterExit(boolean var1, BoundingVolume var2, int var3, CellID var4);
   }
}
