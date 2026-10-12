/*
 * M2b pin: the synchronous request/reply substrate the M3 session will use.
 *
 * WaitResponseListener + OKErrorResponseListener are the two halves of a
 * blocking reply: the reader thread calls responseReceived, a caller parked in
 * waitForResponse wakes, and OKErrorResponseListener additionally fans the
 * reply out into onSuccess / onFailure by its wire type -- then releases the
 * inherited wait. Neither needs a socket: the reply is fed straight through
 * responseReceived, from a helper thread for the blocking case. Runs headless.
 */
package org.jdesktop.wonderland.client.comms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jdesktop.wonderland.common.messages.ErrorMessage;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.OKMessage;
import org.jdesktop.wonderland.common.messages.ResponseMessage;
import org.junit.jupiter.api.Test;

class ResponseListenerTest {

    /** A ResponseMessage that is neither OK nor Error, to reach the
     * unexpected-type branch. ResponseMessage is abstract; an anonymous leaf
     * with no added behaviour is the smallest such wire shape. */
    private static ResponseMessage otherResponse(MessageID id) {
        return new ResponseMessage(id) { };
    }

    @Test
    void waitForResponseBlocksUntilReplyArrivesOnAnotherThread() throws Exception {
        WaitResponseListener listener = new WaitResponseListener();
        MessageID id = new MessageID(1234L);
        OKMessage reply = new OKMessage(id);

        AtomicBoolean returned = new AtomicBoolean(false);
        AtomicReference<ResponseMessage> got = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                ResponseMessage r = listener.waitForResponse();
                got.set(r);
                returned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reader.start();

        // Give the reader a moment to park in wait(), then feed the reply.
        Thread.sleep(50);
        assertThat(returned.get()).isFalse(); // still blocked, no reply yet
        listener.responseReceived(reply);
        reader.join(2000);

        assertThat(returned.get()).isTrue();
        assertThat(got.get()).isSameAs(reply);
        assertThat(got.get().getMessageID()).isEqualTo(id);
    }

    @Test
    void okMessageDispatchesToOnSuccessAndReleasesTheWait() throws Exception {
        MessageID id = new MessageID(7L);
        AtomicReference<MessageID> successId = new AtomicReference<>();
        OKErrorResponseListener listener = new OKErrorResponseListener() {
            @Override public void onSuccess(MessageID msgId) {
                successId.set(msgId);
            }
            @Override public void onFailure(MessageID msgId, String message, Throwable cause) {
                throw new AssertionError("onFailure must not fire for an OK reply");
            }
        };

        listener.responseReceived(new OKMessage(id));

        assertThat(successId.get()).isEqualTo(id);
        // The inherited blocking half still resolves to the same reply.
        assertThat(listener.waitForResponse()).isInstanceOfSatisfying(OKMessage.class,
                m -> assertThat(m.getMessageID()).isEqualTo(id));
    }

    @Test
    void errorMessageDispatchesTextAndCauseToOnFailure() {
        MessageID id = new MessageID(8L);
        Throwable cause = new IllegalStateException("root");
        AtomicReference<String> failedMessage = new AtomicReference<>();
        AtomicReference<Throwable> failedCause = new AtomicReference<>();
        OKErrorResponseListener listener = new OKErrorResponseListener() {
            @Override public void onSuccess(MessageID msgId) {
                throw new AssertionError("onSuccess must not fire for an error reply");
            }
            @Override public void onFailure(MessageID msgId, String message, Throwable c) {
                failedMessage.set(message);
                failedCause.set(c);
            }
        };

        listener.responseReceived(new ErrorMessage(id, "boom", cause));

        assertThat(failedMessage.get()).isEqualTo("boom");
        assertThat(failedCause.get()).isSameAs(cause);
    }

    @Test
    void unexpectedReplyTypeReportsFailureWithNullCause() {
        MessageID id = new MessageID(9L);
        AtomicReference<String> failedMessage = new AtomicReference<>();
        AtomicReference<Throwable> failedCause = new AtomicReference<>();
        AtomicBoolean sawSuccess = new AtomicBoolean(false);
        OKErrorResponseListener listener = new OKErrorResponseListener() {
            @Override public void onSuccess(MessageID msgId) {
                sawSuccess.set(true);
            }
            @Override public void onFailure(MessageID msgId, String message, Throwable c) {
                failedMessage.set(message);
                failedCause.set(c);
            }
        };

        listener.responseReceived(otherResponse(id));

        assertThat(sawSuccess).isFalse();
        assertThat(failedMessage.get()).contains("Unexpected message type");
        assertThat(failedCause.get()).isNull();
    }
}
