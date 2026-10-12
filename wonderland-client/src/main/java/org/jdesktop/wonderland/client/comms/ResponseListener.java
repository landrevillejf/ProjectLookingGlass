package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@ExperimentalAPI
public interface ResponseListener {
   void responseReceived(ResponseMessage var1);
}
