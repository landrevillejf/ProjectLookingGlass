package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

/**
 * Blocks a caller until the matching reply arrives: {@link #responseReceived}
 * (called from the connection's reader thread) stores the response and wakes
 * any {@link #waitForResponse} parked on this instance. The loop guarding the
 * {@code wait()} re-checks the condition, so a spurious wake does not return
 * {@code null}. This is the synchronous half of the request/reply contract the
 * M3 session exercises; it needs no network to test -- the reply is simply fed
 * through {@code responseReceived} from another thread.
 */
@ExperimentalAPI
public class WaitResponseListener implements ResponseListener {
   private ResponseMessage response = null;

   @Override
   public void responseReceived(ResponseMessage response) {
      this.notifyResponse(response);
   }

   public synchronized ResponseMessage waitForResponse() throws InterruptedException {
      while (this.response == null) {
         this.wait();
      }
      return this.response;
   }

   protected synchronized void notifyResponse(ResponseMessage response) {
      this.response = response;
      this.notify();
   }
}
