package org.jdesktop.wonderland.common.cell;

import java.io.Serializable;
import java.util.HashSet;
import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class ClientCapabilities implements Serializable {
   private HashSet capabilities;

   public boolean supportsCapability(Capability capability) {
      return this.capabilities == null ? false : this.capabilities.contains(capability);
   }
}
