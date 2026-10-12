package org.jdesktop.wonderland.common.utils.jaxb;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;

public class QuaternionAdapter extends XmlAdapter<QuaternionAdapter.QuaternionHandler, Quaternion> {
   public Quaternion unmarshal(QuaternionHandler q) throws Exception {
      if (q == null) {
         return null;
      } else {
         Vector3f axis = new Vector3f(q.x, q.y, q.z);
         Quaternion ret = new Quaternion();
         ret.fromAngleAxis(q.angle, axis);
         return ret;
      }
   }

   public QuaternionHandler marshal(Quaternion q) throws Exception {
      return q == null ? null : new QuaternionHandler(q);
   }

   public static class QuaternionHandler {
      @XmlElement
      private float x;
      @XmlElement
      private float y;
      @XmlElement
      private float z;
      @XmlElement
      private float angle;

      public QuaternionHandler() {
      }

      public QuaternionHandler(Quaternion q) {
         Vector3f axis = new Vector3f();
         float a = q.toAngleAxis(axis);
         this.x = axis.x;
         this.y = axis.y;
         this.z = axis.z;
         this.angle = a;
      }
   }
}
