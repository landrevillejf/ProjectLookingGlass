package org.jdesktop.wonderland.common.utils.jaxb;

import com.jme.bounding.BoundingBox;
import com.jme.bounding.BoundingSphere;
import com.jme.bounding.BoundingVolume;
import com.jme.math.Vector3f;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;

public class BoundingVolumeAdapter extends XmlAdapter<BoundingVolumeAdapter.BoundingVolumeHandler, BoundingVolume> {
   public BoundingVolume unmarshal(BoundingVolumeHandler v) throws Exception {
      return v == null ? null : v.createBounds();
   }

   public BoundingVolumeHandler marshal(BoundingVolume v) throws Exception {
      return v == null ? null : new BoundingVolumeHandler(v);
   }

   public static class BoundingVolumeHandler {
      @XmlElement
      private BoundsType boundsType;
      @XmlElement
      private float center_x;
      @XmlElement
      private float center_y;
      @XmlElement
      private float center_z;
      @XmlElement
      private float dimension_x = 0.0F;
      @XmlElement
      private float dimension_y = 0.0F;
      @XmlElement
      private float dimension_z = 0.0F;

      public BoundingVolumeHandler() {
      }

      public BoundingVolumeHandler(BoundingVolume v) {
         if (v instanceof BoundingSphere) {
            this.boundsType = BoundingVolumeAdapter.BoundingVolumeHandler.BoundsType.SPHERE;
            Vector3f center = ((BoundingSphere)v).getCenter();
            this.center_x = center.x;
            this.center_y = center.y;
            this.center_z = center.z;
            this.dimension_x = ((BoundingSphere)v).getRadius();
         } else {
            if (!(v instanceof BoundingBox)) {
               throw new RuntimeException("Unsupported bounds type " + v.getClass().getName());
            }

            this.boundsType = BoundingVolumeAdapter.BoundingVolumeHandler.BoundsType.BOX;
            Vector3f center = ((BoundingBox)v).getCenter();
            this.center_x = center.x;
            this.center_y = center.y;
            this.center_z = center.z;
            Vector3f dimension = ((BoundingBox)v).getExtent((Vector3f)null);
            this.dimension_x = dimension.x;
            this.dimension_y = dimension.y;
            this.dimension_z = dimension.z;
         }

      }

      public BoundingVolume createBounds() {
         switch (this.boundsType) {
            case SPHERE:
               return new BoundingSphere(this.dimension_x, new Vector3f(this.center_x, this.center_y, this.center_z));
            case BOX:
               return new BoundingBox(new Vector3f(this.center_x, this.center_y, this.center_z), this.dimension_x, this.dimension_y, this.dimension_z);
            default:
               throw new RuntimeException("Unsupported bounds type " + this.boundsType);
         }
      }

      public static enum BoundsType {
         BOX,
         SPHERE;
      }
   }
}
