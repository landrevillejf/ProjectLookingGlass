package org.jdesktop.wonderland.common.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public interface ProtocolVersion {
   boolean isCompatible(ProtocolVersion var1);
}
