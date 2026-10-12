package org.jdesktop.wonderland.common.cell.state;

import com.jme.bounding.BoundingBox;
import com.jme.bounding.BoundingSphere;
import com.jme.bounding.BoundingVolume;
import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.io.Serializable;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.jdesktop.wonderland.common.cell.state.annotation.ServerState;
import org.jdesktop.wonderland.common.utils.jaxb.QuaternionAdapter;
import org.jdesktop.wonderland.common.utils.jaxb.Vector3fAdapter;

@XmlRootElement(
   name = "position-component"
)
@ServerState
public class PositionComponentServerState extends CellComponentServerState {
   @XmlElement(
      name = "translation"
   )
   @XmlJavaTypeAdapter(Vector3fAdapter.class)
   public Vector3f translation = new Vector3f();
   @XmlElement(
      name = "bounds"
   )
   public Bounds bounds = new Bounds();
   @XmlElement(
      name = "scale"
   )
   @XmlJavaTypeAdapter(Vector3fAdapter.class)
   public Vector3f scaling = new Vector3f(1.0F, 1.0F, 1.0F);
   @XmlElement(
      name = "rotation"
   )
   @XmlJavaTypeAdapter(QuaternionAdapter.class)
   public Quaternion rotation = new Quaternion();

   public String getServerComponentClassName() {
      return null;
   }

   @XmlTransient
   public Vector3f getTranslation() {
      return this.translation;
   }

   public void setTranslation(Vector3f translation) {
      this.translation = translation;
   }

   @XmlTransient
   public Bounds getBounds() {
      return this.bounds;
   }

   public void setBounds(Bounds bounds) {
      this.bounds = bounds;
   }

   public void setBounds(BoundingVolume boundingVolume) {
      this.bounds = new Bounds(boundingVolume);
   }

   @XmlTransient
   public Vector3f getScaling() {
      return this.scaling;
   }

   public void setScaling(Vector3f scaling) {
      this.scaling = scaling;
   }

   @XmlTransient
   public Quaternion getRotation() {
      return this.rotation;
   }

   public void setRotation(Quaternion rotation) {
      this.rotation = rotation;
   }

   public String toString() {
      return "[BasicCellSetup] origin=(" + this.translation.x + "," + this.translation.y + "," + this.translation.z + ") rotation=(" + this.rotation.x + "," + this.rotation.y + "," + this.rotation.z + ") @ " + this.rotation.w + " scaling=(" + this.scaling.x + "," + this.scaling.y + "," + this.scaling.z + ") bounds=" + this.bounds.type + "@" + this.bounds.x + ", " + this.bounds.y + " " + this.bounds.z;
   }

   public static class Bounds implements Serializable {
      @XmlElement(
         name = "type"
      )
      public BoundsType type;
      @XmlElement(
         name = "x"
      )
      public double x;
      @XmlElement(
         name = "y"
      )
      public double y;
      @XmlElement(
         name = "z"
      )
      public double z;

      public Bounds() {
         this.type = PositionComponentServerState.Bounds.BoundsType.SPHERE;
         this.x = (double)1.0F;
         this.y = (double)1.0F;
         this.z = (double)1.0F;
      }

      public Bounds(BoundingVolume bv) {
         this.type = PositionComponentServerState.Bounds.BoundsType.SPHERE;
         this.x = (double)1.0F;
         this.y = (double)1.0F;
         this.z = (double)1.0F;
         if (bv instanceof BoundingBox) {
            this.type = PositionComponentServerState.Bounds.BoundsType.BOX;
            this.x = (double)((BoundingBox)bv).xExtent;
            this.y = (double)((BoundingBox)bv).yExtent;
            this.z = (double)((BoundingBox)bv).zExtent;
         } else if (bv instanceof BoundingSphere) {
            this.type = PositionComponentServerState.Bounds.BoundsType.SPHERE;
            this.x = (double)((BoundingSphere)bv).radius;
         }

      }

      public static enum BoundsType {
         SPHERE,
         BOX;
      }
   }
}
