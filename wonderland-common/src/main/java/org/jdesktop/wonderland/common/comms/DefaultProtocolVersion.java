package org.jdesktop.wonderland.common.comms;

import java.io.Serializable;
import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class DefaultProtocolVersion implements ProtocolVersion, Serializable {
   private final int majorVersion;
   private final int minorVersion;
   private final int subVersion;

   public DefaultProtocolVersion(int majorVersion, int minorVersion, int subVersion) {
      this.majorVersion = majorVersion;
      this.minorVersion = minorVersion;
      this.subVersion = subVersion;
   }

   public int getMajorVersion() {
      return this.majorVersion;
   }

   public int getMinorVersion() {
      return this.minorVersion;
   }

   public int getSubVersion() {
      return this.subVersion;
   }

   public boolean isCompatible(ProtocolVersion version) {
      if (!(version instanceof DefaultProtocolVersion)) {
         return false;
      } else {
         DefaultProtocolVersion v = (DefaultProtocolVersion)version;
         return this.getMajorVersion() == v.getMajorVersion() && this.getMinorVersion() == v.getMinorVersion() && this.getSubVersion() >= v.getSubVersion();
      }
   }

   public boolean equals(Object obj) {
      if (!(obj instanceof DefaultProtocolVersion)) {
         return false;
      } else {
         DefaultProtocolVersion v = (DefaultProtocolVersion)obj;
         return this.getMajorVersion() == v.getMajorVersion() && this.getMinorVersion() == v.getMinorVersion() && this.getSubVersion() == v.getSubVersion();
      }
   }

   public int hashCode() {
      int hash = 7;
      hash = 53 * hash + this.majorVersion;
      hash = 53 * hash + this.minorVersion;
      hash = 53 * hash + this.subVersion;
      return hash;
   }

   public String toString() {
      return this.getMajorVersion() + "." + this.getMinorVersion() + "." + this.getSubVersion();
   }
}
