package org.jdesktop.wonderland.common.comms;

import java.io.Serializable;
import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class ConnectionType implements Serializable {
   private String type;

   public ConnectionType(String type) {
      this.type = type;
   }

   public boolean equals(Object obj) {
      if (obj == null) {
         return false;
      } else if (!(obj instanceof ConnectionType)) {
         return false;
      } else {
         ConnectionType other = (ConnectionType)obj;
         return this.type == other.type || this.type != null && this.type.equals(other.type);
      }
   }

   public int hashCode() {
      int hash = 7;
      hash = 37 * hash + (this.type != null ? this.type.hashCode() : 0);
      return hash;
   }

   public String toString() {
      return this.type;
   }
}
