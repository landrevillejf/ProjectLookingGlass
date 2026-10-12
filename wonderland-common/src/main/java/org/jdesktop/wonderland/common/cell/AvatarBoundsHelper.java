package org.jdesktop.wonderland.common.cell;

import com.jme.bounding.BoundingBox;
import com.jme.bounding.BoundingSphere;
import com.jme.bounding.BoundingVolume;
import com.jme.math.Matrix4f;
import com.jme.math.Vector3f;

public class AvatarBoundsHelper {
   public static float AVATAR_CELL_SIZE = 1.0F;
   public static float PROXIMITY_SIZE = 101.0F;
   public static float MAX_VIEW_DISTANCE = 300.0F;

   public static BoundingVolume getCellBounds(Vector3f center) {
      BoundingVolume tileBounds = new BoundingBox(center, AVATAR_CELL_SIZE, AVATAR_CELL_SIZE, AVATAR_CELL_SIZE);
      return tileBounds;
   }

   public static BoundingSphere getProximityBounds(Vector3f center) {
      return new BoundingSphere(PROXIMITY_SIZE, center);
   }

   public static BoundingVolume getFrustumBounds(CellTransform avatarT3D) {
      Vector3f p = new Vector3f(0.0F, 0.0F, -MAX_VIEW_DISTANCE / 2.0F * 0.95F);
      avatarT3D.transform(p);
      return new BoundingSphere(MAX_VIEW_DISTANCE / 2.0F, p);
   }

   public static Matrix4f computeViewPlatformTransform(Vector3f userPosition, Vector3f direction, Vector3f up) {
      Matrix4f mat = new Matrix4f();
      Vector3f axisX = new Vector3f();
      Vector3f axisY = new Vector3f();
      Vector3f axisZ = new Vector3f(direction);
      axisZ.negate();
      axisZ.normalize();
      axisX.cross(up, axisZ);
      axisX.normalize();
      axisY.cross(axisZ, axisX);
      axisY.normalize();
      mat.m00 = axisX.x;
      mat.m10 = axisX.y;
      mat.m20 = axisX.z;
      mat.m30 = 0.0F;
      mat.m01 = axisY.x;
      mat.m11 = axisY.y;
      mat.m21 = axisY.z;
      mat.m31 = 0.0F;
      mat.m02 = axisZ.x;
      mat.m12 = axisZ.y;
      mat.m22 = axisZ.z;
      mat.m32 = 0.0F;
      mat.m03 = userPosition.x;
      mat.m13 = userPosition.y;
      mat.m23 = userPosition.z;
      mat.m33 = 1.0F;
      return mat;
   }
}
