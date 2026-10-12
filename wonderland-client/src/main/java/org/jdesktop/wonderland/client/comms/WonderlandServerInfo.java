package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;

/**
 * A {@code (hostname, port)} pair naming a Wonderland session server. A value
 * object with value-based {@link #equals}/{@link #hashCode} so it keys maps and
 * dedupes. The {@code sgsPort} name is historical (Sun Game Server, retired in
 * the rebuild); the field simply carries the server port the M3 session server
 * will listen on.
 */
@ExperimentalAPI
public class WonderlandServerInfo {
   private String hostname;
   private int sgsPort;

   public WonderlandServerInfo(String hostname, int sgsPort) {
      this.hostname = hostname;
      this.sgsPort = sgsPort;
   }

   public String getHostname() {
      return this.hostname;
   }

   public int getSgsPort() {
      return this.sgsPort;
   }

   @Override
   public boolean equals(Object obj) {
      if (!(obj instanceof WonderlandServerInfo)) {
         return false;
      } else {
         WonderlandServerInfo o = (WonderlandServerInfo) obj;
         return this.getHostname().equals(o.getHostname()) && this.getSgsPort() == o.getSgsPort();
      }
   }

   @Override
   public int hashCode() {
      int hash = 7;
      hash = 97 * hash + (this.hostname != null ? this.hostname.hashCode() : 0);
      hash = 97 * hash + this.sgsPort;
      return hash;
   }
}
