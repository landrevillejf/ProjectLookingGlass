package org.jdesktop.wonderland.common;

import com.jme.bounding.BoundingBox;
import com.jme.bounding.BoundingSphere;
import com.jme.bounding.BoundingVolume;
import com.jme.math.Matrix3f;
import com.jme.math.Quaternion;
import com.jme.math.Vector3f;

@ExperimentalAPI
public class Math3DUtils {
   public static float pointLineDistance(Vector3f lineStart, Vector3f lineEnd, Vector3f point) {
      Vector3f a = new Vector3f(lineEnd);
      a.subtract(lineStart);
      Vector3f b = new Vector3f(lineStart);
      b.subtract(point);
      Vector3f cross = new Vector3f();
      cross.cross(a, b);
      return cross.length() / a.length();
   }

   public static void toEuler(Matrix3f matrix, Vector3f euler) {
      Vector3f v3d = new Vector3f();
      Vector3f zAxis = new Vector3f(0.0F, 0.0F, -1.0F);
      Vector3f yAxis = new Vector3f(0.0F, 1.0F, 0.0F);
      Vector3f xAxis = new Vector3f(1.0F, 0.0F, 0.0F);
      v3d.set(xAxis);
      matrix.mult(v3d, v3d);
      v3d.x = Math.abs(v3d.x);
      v3d.z = 0.0F;
      v3d.normalize();
      euler.x = xAxis.angleBetween(v3d);
      v3d.set(yAxis);
      matrix.mult(v3d, v3d);
      v3d.z = Math.abs(v3d.z);
      v3d.x = 0.0F;
      v3d.normalize();
      euler.y = yAxis.angleBetween(v3d);
      v3d.set(zAxis);
      matrix.mult(v3d, v3d);
      v3d.y = 0.0F;
      v3d.normalize();
      euler.z = zAxis.angleBetween(v3d);
      if (v3d.x < 0.0F) {
         euler.z = ((float)Math.PI * 2F) - euler.z;
      }

   }

   public static boolean epsilonEquals(float f1, float f2) {
      return epsilonEquals(f1, f2, 1.1920929E-7F);
   }

   public static boolean epsilonEquals(float f1, float f2, float epsilon) {
      float diff = f1 - f2;
      return !((diff < 0.0F ? -diff : diff) > epsilon);
   }

   public static boolean epsilonEquals(Vector3f t1, Vector3f t2) {
      return epsilonEquals(t1, t2, 1.1920929E-7F);
   }

   public static boolean epsilonEquals(Vector3f t1, Vector3f t2, float epsilon) {
      if (t1 == null && t2 == null) {
         return true;
      } else if (t1 != null && t2 != null) {
         return epsilonEquals(t1.x, t2.x, epsilon) && epsilonEquals(t1.y, t2.y, epsilon) && epsilonEquals(t1.z, t2.z, epsilon);
      } else {
         return false;
      }
   }

   public static boolean epsilonEquals(Quaternion q1, Quaternion q2) {
      return epsilonEquals(q1, q2, 1.1920929E-7F);
   }

   public static boolean epsilonEquals(Quaternion q1, Quaternion q2, float epsilon) {
      if (q1 == null && q2 == null) {
         return true;
      } else if (q1 != null && q2 != null) {
         float[] a1 = q1.toAngles((float[])null);
         float[] a2 = q2.toAngles((float[])null);
         return epsilonEquals(a1[0], a2[0], epsilon) && epsilonEquals(a1[1], a2[1], epsilon) && epsilonEquals(a1[2], a2[2], epsilon);
      } else {
         return false;
      }
   }

   public static boolean encloses(BoundingVolume parent, BoundingVolume child) {
      if (parent instanceof BoundingBox) {
         if (child instanceof BoundingBox) {
            return encloses((BoundingBox)parent, (BoundingBox)child);
         }

         if (child instanceof BoundingSphere) {
            return encloses((BoundingBox)parent, (BoundingSphere)child);
         }
      } else if (parent instanceof BoundingSphere) {
         if (child instanceof BoundingBox) {
            return encloses((BoundingSphere)parent, (BoundingBox)child);
         }

         if (child instanceof BoundingSphere) {
            return encloses((BoundingSphere)parent, (BoundingSphere)child);
         }
      }

      throw new UnsupportedOperationException("Unsupported bounds combination " + parent.getClass().getName() + " " + child.getClass().getName());
   }

   public static boolean encloses(BoundingBox parent, BoundingSphere child) {
      Vector3f pCenter = parent.getCenter();
      Vector3f pExtent = parent.getExtent((Vector3f)null);
      Vector3f cCenter = child.getCenter();
      float radius = child.getRadius();
      if (!(cCenter.x + radius > pCenter.x + pExtent.x) && !(cCenter.y + radius > pCenter.y + pExtent.y) && !(cCenter.z + radius > pCenter.z + pExtent.z)) {
         return !(cCenter.x - radius < pCenter.x - pExtent.x) && !(cCenter.y - radius < pCenter.y - pExtent.y) && !(cCenter.z - radius < pCenter.z - pExtent.z);
      } else {
         return false;
      }
   }

   public static boolean encloses(BoundingBox parent, BoundingBox child) {
      Vector3f pExtent = parent.getExtent((Vector3f)null);
      Vector3f cExtent = child.getExtent((Vector3f)null);
      Vector3f pCenter = parent.getCenter();
      Vector3f cCenter = child.getCenter();
      if (!(cCenter.x + cExtent.x > pCenter.x + pExtent.x) && !(cCenter.y + cExtent.y > pCenter.y + pExtent.y) && !(cCenter.z + cExtent.z > pCenter.z + pExtent.z)) {
         return !(cCenter.x - cExtent.x < pCenter.x - pExtent.x) && !(cCenter.y - cExtent.y < pCenter.y - pExtent.y) && !(cCenter.z - cExtent.z < pCenter.z - pExtent.z);
      } else {
         return false;
      }
   }

   public static boolean encloses(BoundingSphere parent, BoundingBox child) {
      Vector3f cCenter = child.getCenter();
      Vector3f cExtent = child.getExtent((Vector3f)null);
      Vector3f tmp = new Vector3f();
      tmp.x = cCenter.x + cExtent.x;
      tmp.y = cCenter.y + cExtent.y;
      tmp.z = cCenter.z + cExtent.z;
      if (!parent.contains(tmp)) {
         return false;
      } else {
         tmp.x = cCenter.x - cExtent.x;
         if (!parent.contains(tmp)) {
            return false;
         } else {
            tmp.z = cCenter.z - cExtent.z;
            if (!parent.contains(tmp)) {
               return false;
            } else {
               tmp.x = cCenter.x + cExtent.x;
               if (!parent.contains(tmp)) {
                  return false;
               } else {
                  tmp.x = cCenter.x + cExtent.x;
                  tmp.y = cCenter.y - cExtent.y;
                  tmp.z = cCenter.z + cExtent.z;
                  if (!parent.contains(tmp)) {
                     return false;
                  } else {
                     tmp.x = cCenter.x - cExtent.x;
                     if (!parent.contains(tmp)) {
                        return false;
                     } else {
                        tmp.z = cCenter.z - cExtent.z;
                        if (!parent.contains(tmp)) {
                           return false;
                        } else {
                           tmp.x = cCenter.x + cExtent.x;
                           return parent.contains(tmp);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public static boolean encloses(BoundingSphere parent, BoundingSphere child) {
      Vector3f childCenter = new Vector3f();
      Vector3f parentCenter = new Vector3f();
      child.getCenter(childCenter);
      parent.getCenter(parentCenter);
      float childR = child.getRadius();
      float parentR = parent.getRadius();
      if (!(childCenter.x + childR > parentCenter.x + parentR) && !(childCenter.y + childR > parentCenter.y + parentR) && !(childCenter.z + childR > parentCenter.z + parentR)) {
         return !(childCenter.x - childR < parentCenter.x - parentR) && !(childCenter.y - childR < parentCenter.y - parentR) && !(childCenter.z - childR < parentCenter.z - parentR);
      } else {
         return false;
      }
   }
}
