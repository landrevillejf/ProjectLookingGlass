package org.jdesktop.wonderland.common.cell;

import com.jme.bounding.BoundingVolume;
import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.io.Serializable;
import java.util.logging.Logger;
import org.jogamp.vecmath.Matrix4d;
import org.jogamp.vecmath.Quat4d;
import org.jogamp.vecmath.Vector3d;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.Math3DUtils;

@ExperimentalAPI
public class CellTransform implements Serializable {
   private Quaternion rotation;
   private Vector3f translation;
   private float scale;

   public CellTransform() {
      this((Quaternion)null, (Vector3f)null, 1.0F);
   }

   public CellTransform(Quaternion rotate, Vector3f translate, float scale) {
      this.scale = 1.0F;
      if (rotate == null) {
         this.rotation = new Quaternion();
      } else {
         this.rotation = rotate.clone();
      }

      if (translate == null) {
         this.translation = new Vector3f();
      } else {
         this.translation = translate.clone();
      }

      this.scale = scale;
   }

   /** @deprecated */
   public CellTransform(Quaternion rotate, Vector3f translate, Vector3f scale) {
      this.scale = 1.0F;
      if (rotate == null) {
         this.rotation = new Quaternion();
      } else {
         this.rotation = rotate.clone();
      }

      if (translate == null) {
         this.translation = new Vector3f();
      } else {
         this.translation = translate.clone();
      }

      if (scale == null) {
         this.scale = 0.0F;
      } else {
         this.scale = scale.x;
         // The warning is the real message (this constructor keeps only scale.x,
         // so a non-uniform Vector3f is silently flattened); the Thread.dumpStack()
         // that the 2006 original paired with it is a debug aid that only spams a
         // full stack trace per call -- e.g. once per seeded cell on server boot.
         Logger.getLogger(CellTransform.class.getName()).warning("Non uniform scale is not supported, please use another CellTransform constructor");
      }

   }

   public CellTransform(Quaternion rotate, Vector3f translation) {
      this(rotate, translation, 1.0F);
   }

   private CellTransform(CellTransform orig) {
      this.scale = 1.0F;
      this.rotation = new Quaternion(orig.rotation);
      this.translation = new Vector3f(orig.translation);
      this.scale = orig.scale;
   }

   public CellTransform clone(CellTransform result) {
      if (result == null) {
         return new CellTransform(this);
      } else {
         result.set(this);
         return result;
      }
   }

   private void set(CellTransform source) {
      this.rotation.set(source.rotation);
      this.scale = source.scale;
      this.translation.set(source.translation);
   }

   public void transform(BoundingVolume ret) {
      assert ret != null;

      ret.transform(this.rotation, this.translation, new Vector3f(this.scale, this.scale, this.scale), ret);
   }

   public Vector3f transform(Vector3f ret) {
      ret.multLocal(this.scale);
      this.rotation.multLocal(ret);
      ret.addLocal(this.translation);
      return ret;
   }

   public void getLookAt(Vector3f position, Vector3f look) {
      position.set(0.0F, 0.0F, 0.0F);
      this.transform(position);
      look.set(0.0F, 0.0F, 1.0F);
      this.transform(look);
      look.normalizeLocal();
   }

   public CellTransform mul(CellTransform in) {
      Quat4d q = new Quat4d((double)this.rotation.x, (double)this.rotation.y, (double)this.rotation.z, (double)this.rotation.w);
      Vector3d t = new Vector3d((double)this.translation.x, (double)this.translation.y, (double)this.translation.z);
      Matrix4d m = new Matrix4d(q, t, (double)this.scale);
      Quat4d q1 = new Quat4d((double)in.rotation.x, (double)in.rotation.y, (double)in.rotation.z, (double)in.rotation.w);
      Vector3d t1 = new Vector3d((double)in.translation.x, (double)in.translation.y, (double)in.translation.z);
      Matrix4d m1 = new Matrix4d(q1, t1, (double)in.scale);
      m.mul(m1);
      m.get(q);
      m.get(t);
      this.scale = (float)m.getScale();
      this.rotation.set((float)q.x, (float)q.y, (float)q.z, (float)q.w);
      this.translation.set((float)t.x, (float)t.y, (float)t.z);
      return this;
   }

   public CellTransform mul(CellTransform t1, CellTransform t2) {
      this.set(t1);
      this.mul(t2);
      return this;
   }

   public CellTransform sub(CellTransform t1) {
      this.rotation.subtract(t1.rotation);
      this.scale -= t1.scale;
      this.translation.subtract(t1.translation);
      return this;
   }

   public Vector3f getTranslation(Vector3f translation) {
      if (translation == null) {
         return new Vector3f(this.translation);
      } else {
         translation.set(this.translation);
         return translation;
      }
   }

   public void setTranslation(Vector3f translation) {
      if (this.translation == null) {
         this.translation = new Vector3f();
      } else {
         this.translation.set(translation);
      }

   }

   public Quaternion getRotation(Quaternion rotation) {
      if (rotation == null) {
         rotation = new Quaternion(this.rotation);
      } else {
         rotation.set(this.rotation);
      }

      return rotation;
   }

   public void setRotation(Quaternion rotation) {
      if (this.rotation == null) {
         this.rotation = new Quaternion(rotation);
      } else {
         this.rotation.set(rotation);
      }

   }

   public Vector3f getScaling(Vector3f scale) {
      if (scale == null) {
         scale = new Vector3f(this.scale, this.scale, this.scale);
      } else {
         scale.set(this.scale, this.scale, this.scale);
      }

      return scale;
   }

   public float getScaling() {
      return this.scale;
   }

   public void setScaling(float scale) {
      this.scale = scale;
   }

   public CellTransform invert() {
      Quat4d q = new Quat4d((double)this.rotation.x, (double)this.rotation.y, (double)this.rotation.z, (double)this.rotation.w);
      Vector3d t = new Vector3d((double)this.translation.x, (double)this.translation.y, (double)this.translation.z);
      Matrix4d m = new Matrix4d(q, t, (double)this.scale);
      m.invert();
      m.get(q);
      m.get(t);
      this.scale = (float)m.getScale();
      this.rotation.set((float)q.x, (float)q.y, (float)q.z, (float)q.w);
      this.translation.set((float)t.x, (float)t.y, (float)t.z);
      return this;
   }

   public boolean epsilonEquals(CellTransform o) {
      if (o == null) {
         return false;
      } else if (!Math3DUtils.epsilonEquals(this.translation, o.translation, 0.001F)) {
         return false;
      } else if (!Math3DUtils.epsilonEquals(this.rotation, o.rotation, ((float)Math.PI / 180F))) {
         return false;
      } else {
         return Math3DUtils.epsilonEquals(this.scale, this.scale, 1.0E-4F);
      }
   }

   public boolean equals(Object o) {
      boolean ret = true;
      if (o instanceof CellTransform) {
         CellTransform e = (CellTransform)o;
         if (e.rotation != null && !e.rotation.equals(this.rotation)) {
            ret = false;
         } else if (e.rotation == null && this.rotation != null) {
            ret = false;
         }

         if (e.translation != null && !e.translation.equals(this.translation)) {
            ret = false;
         } else if (e.translation == null && this.translation != null) {
            ret = false;
         }

         if (e.scale != this.scale) {
            ret = false;
         }
      } else {
         ret = false;
      }

      return ret;
   }

   public int hashCode() {
      int hash = 3;
      hash = 13 * hash + (this.rotation != null ? this.rotation.hashCode() : 0);
      hash = 13 * hash + (this.translation != null ? this.translation.hashCode() : 0);
      hash = 13 * hash + (int)(this.scale * 1000.0F);
      return hash;
   }

   public String toString() {
      return "[tx=" + this.translation.x + " ty=" + this.translation.y + " tz=" + this.translation.z + "] [rot=" + this.printQuat(this.rotation) + "] [s=" + this.scale + "]";
   }

   private String printQuat(Quaternion q) {
      Vector3f axis = new Vector3f();
      float angle = q.toAngleAxis(axis);
      return axis + ":" + Math.toDegrees((double)angle);
   }
}
