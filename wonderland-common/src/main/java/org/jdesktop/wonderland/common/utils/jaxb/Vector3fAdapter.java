package org.jdesktop.wonderland.common.utils.jaxb;

import com.jme.math.Vector3f;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;

public class Vector3fAdapter extends XmlAdapter<Vector3fAdapter.Vector3fHandler, Vector3f> {
   public Vector3f unmarshal(Vector3fHandler v) throws Exception {
      return v == null ? null : new Vector3f(v.x, v.y, v.z);
   }

   public Vector3fHandler marshal(Vector3f v) throws Exception {
      return v == null ? null : new Vector3fHandler(v);
   }

   public static class Vector3fHandler {
      @XmlElement
      private float x;
      @XmlElement
      private float y;
      @XmlElement
      private float z;

      public Vector3fHandler() {
      }

      public Vector3fHandler(Vector3f v) {
         this.x = v.x;
         this.y = v.y;
         this.z = v.z;
      }
   }
}
