/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: the session status the desktop's HUD and login panel
 * read.
 *
 * The 2006 client had no such object: the SGS login machine pushed its status
 * straight into Swing labels, and the scene-graph layer never heard about it. The
 * port cannot copy that -- the panel must not import the transport, and the
 * transport fires on its own thread while Swing owns the screen. So the feed
 * holds the status, and every viewer registers a consumer.
 *
 * It is headless by construction: WonderlandSession is a pure interface and
 * SessionStatusListener a pure callback, so a test drives the whole class with an
 * in-JVM fake session (the same harness M3a built) and never opens a socket.
 */
package org.jdesktop.wonderland.desktop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jdesktop.wonderland.client.comms.SessionStatusListener;
import org.jdesktop.wonderland.client.comms.WonderlandSession;

/**
 * A thread-safe view of one {@link WonderlandSession}'s status, republished to
 * listeners on every transition.
 *
 * <p>The feed starts {@link WonderlandSession.Status#DISCONNECTED} and stays
 * there until a session is attached; attaching reads the session's current status
 * so a late HUD still shows the truth rather than a stale "Disconnected".</p>
 */
public final class SessionFeed implements SessionStatusListener {

    private final List<Consumer<WonderlandSession.Status>> listeners =
            new CopyOnWriteArrayList<>();

    private volatile WonderlandSession.Status status = WonderlandSession.Status.DISCONNECTED;
    private volatile WonderlandSession attached;

    /**
     * Follows {@code session}. Passing null detaches (and resets to
     * DISCONNECTED), which is what a logout leaves behind.
     *
     * @param session the session to observe, or null to detach
     */
    public void attach(WonderlandSession session) {
        WonderlandSession previous = this.attached;
        if (previous != null) {
            previous.removeSessionStatusListener(this);
        }
        this.attached = session;
        if (session == null) {
            publish(WonderlandSession.Status.DISCONNECTED);
            return;
        }
        session.addSessionStatusListener(this);
        // The session may already be connected: read rather than assume, or a
        // HUD opened after login would show the wrong state until the next
        // transition -- which for a stable connection is never.
        WonderlandSession.Status current = session.getStatus();
        publish(current == null ? WonderlandSession.Status.DISCONNECTED : current);
    }

    /** The session being followed, or null while detached. */
    public WonderlandSession getSession() {
        return attached;
    }

    /** The last status published. Never null. */
    public WonderlandSession.Status getStatus() {
        return status;
    }

    /**
     * Registers {@code listener}, which is called with the new status on every
     * transition this feed publishes. Listeners are called in registration order
     * and on whichever thread drove the transition.
     *
     * @param listener the consumer to notify; null is ignored
     */
    public void addStatusListener(Consumer<WonderlandSession.Status> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Stops notifying {@code listener}. */
    public void removeStatusListener(Consumer<WonderlandSession.Status> listener) {
        listeners.remove(listener);
    }

    /** {@link #attach(WonderlandSession)} with the null session: detach and reset. */
    public void detach() {
        attach(null);
    }

    /**
     * The {@link SessionStatusListener} callback. A duplicate status is not
     * republished: a session that re-sends CONNECTED must not make a HUD flicker,
     * and a listener that counts transitions must count real ones.
     */
    @Override
    public void sessionStatusChanged(WonderlandSession session,
            WonderlandSession.Status newStatus) {
        publish(newStatus == null ? WonderlandSession.Status.DISCONNECTED : newStatus);
    }

    private void publish(WonderlandSession.Status next) {
        WonderlandSession.Status previous = this.status;
        this.status = next;
        if (previous == next) {
            return;
        }
        for (Consumer<WonderlandSession.Status> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException | Error listenerFailure) {
                // One broken viewer must not stop the others, and must never
                // propagate back into the transport thread that reported the
                // status -- the status has already changed whether or not the
                // HUD survives it.
            }
        }
    }
}
