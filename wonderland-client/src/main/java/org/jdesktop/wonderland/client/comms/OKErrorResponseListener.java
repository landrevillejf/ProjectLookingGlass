package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.messages.ErrorMessage;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.OKMessage;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

/**
 * A {@link WaitResponseListener} that fans a reply out into a success / failure
 * callback by its wire type: an {@link OKMessage} calls {@link #onSuccess}, an
 * {@link ErrorMessage} calls {@link #onFailure} with the server's text and
 * cause, anything else is reported as an unexpected-type failure. Either way it
 * then releases the inherited {@link #waitForResponse} wait, so the two halves
 * (blocking and typed dispatch) compose. The whole class is exercised headless
 * by feeding it the three {@code ResponseMessage} shapes directly.
 */
@ExperimentalAPI
public abstract class OKErrorResponseListener extends WaitResponseListener {
   @Override
   public void responseReceived(ResponseMessage response) {
      if (response instanceof OKMessage) {
         this.onSuccess(response.getMessageID());
      } else if (response instanceof ErrorMessage) {
         ErrorMessage em = (ErrorMessage) response;
         this.onFailure(response.getMessageID(), em.getErrorMessage(), em.getErrorCause());
      } else {
         this.onFailure(response.getMessageID(), "Unexpected message type: " + response.getClass(), (Throwable) null);
      }
      this.notifyResponse(response);
   }

   public abstract void onSuccess(MessageID var1);

   public abstract void onFailure(MessageID var1, String var2, Throwable var3);
}
