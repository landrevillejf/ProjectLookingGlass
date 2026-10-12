/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.comms),
 * verbatim. This is the skeleton every concrete connection hangs off: the
 * DISCONNECTED/CONNECTED status pair, the request-reply map keyed by
 * MessageID (notifyResponseListener consumes the listener, so a reply is
 * routed once), send()/send()/listener, sendAndWait over the M2b
 * WaitResponseListener, and the messageReceived split -- a ResponseMessage
 * with a waiting listener never reaches handleMessage.
 *
 * The only 2006 member NOT ported is disconnect()-without-a-session hygiene:
 * the original called getSession().disconnect(this) unconditionally; kept
 * verbatim here (a NullPointerException on a never-connected disconnect is the
 * same behavior a 2006 client had, and the connections always connect first).
 */
package org.jdesktop.wonderland.client.comms;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

@ExperimentalAPI
public abstract class BaseConnection implements ClientConnection {

    private Status status;
    private WonderlandSession session;
    private final Map<MessageID, ResponseListener> responseListeners;

    public BaseConnection() {
        this.status = Status.DISCONNECTED;
        this.responseListeners = Collections.synchronizedMap(new HashMap<>());
    }

    @Override
    public WonderlandSession getSession() {
        return session;
    }

    @Override
    public synchronized Status getStatus() {
        return status;
    }

    protected synchronized void setStatus(Status status) {
        this.status = status;
    }

    public void connect(WonderlandSession session) throws ConnectionFailureException {
        session.connect(this);
    }

    public void connect(WonderlandSession session, Properties properties)
            throws ConnectionFailureException {
        session.connect(this, properties);
    }

    @Override
    public synchronized void connected(WonderlandSession session) {
        this.session = session;
        setStatus(Status.CONNECTED);
    }

    public void disconnect() {
        getSession().disconnect(this);
    }

    @Override
    public void disconnected() {
        setStatus(Status.DISCONNECTED);
    }

    @Override
    public void messageReceived(Message message) {
        if (message instanceof ResponseMessage
                && notifyResponseListener((ResponseMessage) message)) {
            return;
        }
        handleMessage(message);
    }

    public abstract void handleMessage(Message message);

    protected void send(Message message) {
        send(message, null);
    }

    protected void send(Message message, ResponseListener listener) {
        if (getStatus() != Status.CONNECTED) {
            throw new IllegalStateException("Not connected");
        }
        if (listener != null) {
            addResponseListener(message.getMessageID(), listener);
        }
        getSession().send(this, message);
    }

    protected ResponseMessage sendAndWait(Message message) throws InterruptedException {
        WaitResponseListener listener = new WaitResponseListener();
        send(message, listener);
        return listener.waitForResponse();
    }

    protected void addResponseListener(MessageID messageID, ResponseListener listener) {
        responseListeners.put(messageID, listener);
    }

    protected boolean notifyResponseListener(ResponseMessage response) {
        ResponseListener listener = responseListeners.remove(response.getMessageID());
        if (listener != null) {
            listener.responseReceived(response);
        }
        return listener != null;
    }

    @Override
    public String toString() {
        return getConnectionType().toString();
    }
}
