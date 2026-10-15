/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland chat (M9): the client's half of the chat wire.
 *
 * What this owns is the transport and nothing else: it puts a typed line on the
 * session's channel connection, and it hands lines the server relays to whoever
 * is listening. The transcript is not its business --
 * {@code org.jdesktop.wonderland.modules.chat.ChatConversation} is a model with
 * no dependency on any socket, and the desktop joins the two the way
 * {@code SessionFeed} joins a session to a status strip. Keeping the seam there
 * is what lets the module's model stay testable without a server.
 *
 * The address is the world's environment cell, and the reason is the client's
 * own routing: CellChannelConnection demultiplexes inbound traffic by CellID
 * into that cell's channel, so a message with no loaded cell to land on is a
 * message nobody sees. The environment cell is the one cell every session
 * loads -- the server's SET_VIEW fan-out sends it before any content -- so it is
 * the only address guaranteed to have a receiver on the far side.
 *
 * The sender's name is deliberately a placeholder. The server rewrites it from
 * the authenticated identity before it relays (ProtocolRouter.onChatMessage),
 * and a client that believes its own claim would show one name to its author
 * and a different one to everyone else. So the author's line comes back from
 * the relay like anyone else's: chat has no local echo, and therefore no
 * version of the transcript in which what you said is not what was heard.
 */
package org.jdesktop.wonderland.client.cell;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.client.comms.OKErrorResponseListener;
import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.messages.TextChatMessage;
import org.jdesktop.wonderland.common.messages.MessageID;

/**
 * One session's chat send/receive, attached to the world cell every client
 * shares. Construct one per {@link CellClientSession}, {@link #start()} it once
 * the session is connecting (before or after the handshake, either order
 * works), and {@link #stop()} it when the world window goes away.
 */
@InternalAPI
public final class ChatChannel implements CellCache.CellCacheListener,
        MessageChannel.MessageReceiver {

    /**
     * What the wire carries as the author's claim, which the server replaces.
     * Non-blank because the message's own bounds refuse a blank name -- a claim
     * is still a field -- and otherwise meaningless, so it says nothing about
     * the person typing.
     */
    static final String UNCLAIMED_NAME = "participant";

    /** Why a line could not be sent when there is no world to send it to. */
    static final String NO_WORLD = "not in a world yet";

    private final CellClientSession session;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** The cell carrying chat, set once it is loaded; null before that. */
    private volatile Cell worldCell;
    /**
     * The channel the receiver went onto, kept beside the cell so a detach
     * removes the receiver from exactly the channel that has it rather than
     * asking the cell what it holds now.
     */
    private volatile MessageChannel worldChannel;
    private volatile boolean started;

    /** One received chat line, as the server's relay presented it. */
    public interface Listener {

        /**
         * @param senderName the author as the server authenticated them
         * @param text       the body
         * @param sentAt     epoch millis at which the author posted it
         * @param senderID   the author's session id, equal to
         *                   {@link CellClientSession#getID()} for the local
         *                   user's own line coming back around the relay
         */
        void chatReceived(String senderName, String text, long sentAt,
                BigInteger senderID);
    }

    /**
     * @param session the connected (or connecting) session whose channel
     *                connection carries the traffic
     */
    public ChatChannel(CellClientSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        this.session = session;
    }

    /**
     * Begin listening. Safe to call before the handshake: the cache listener is
     * registered whether or not the world cell is loaded yet (the cache holds it
     * in a set, so a repeat call is not a repeat registration), and if the cell
     * is already there the receiver goes on its channel at once.
     *
     * <p>The listener stays registered for as long as the channel is, rather
     * than being dropped once the first attach succeeds: the world cell can be
     * unloaded and reloaded -- a depart and a re-enter, an editor's reload --
     * and a channel that outlives its cell's unload would leave the transcript
     * deaf while the wire still looks connected.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        CellCache cache = session.getCellCache();
        cache.addCellCacheListener(this);
        Cell environment = cache.getEnvironmentCell();
        if (environment != null) {
            attach(environment);
        }
    }

    /** Stop listening and detach, so a closed window keeps no receiver alive. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        session.getCellCache().removeCellCacheListener(this);
        worldCell = null;
        detach();
    }

    /** Register a listener for lines arriving from the world. */
    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /** Unregister a listener; absent listeners are ignored. */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Whether a line can be sent right now, which is what a Send button shows. */
    public boolean isReady() {
        return worldCell != null;
    }

    /**
     * Post one line.
     *
     * @param text    the body; blank or longer than
     *                {@link TextChatMessage#MAX_TEXT_CHARS} is reported through
     *                {@code failure} rather than thrown, so a Send button needs
     *                no exception handling around a person's typing
     * @param failure called with the reason the line did not go, on the thread
     *                that was refused or on the transport thread when the server
     *                answers with an error; never called when the line went
     */
    public void send(String text, Consumer<String> failure) {
        Cell cell = worldCell;
        if (cell == null) {
            fail(failure, NO_WORLD);
            return;
        }
        TextChatMessage message;
        try {
            message = new TextChatMessage(cell.getCellID(), UNCLAIMED_NAME, text);
        } catch (IllegalArgumentException notSendable) {
            fail(failure, notSendable.getMessage());
            return;
        }
        session.getCellChannelConnection().send(message,
                new OKErrorResponseListener() {
                    @Override
                    public void onSuccess(MessageID messageID) {
                        // The line is in the world; the transcript learns it
                        // when the relay comes back, like everyone else's.
                    }

                    @Override
                    public void onFailure(MessageID messageID, String reason,
                            Throwable cause) {
                        fail(failure, reason);
                    }
                });
    }

    /** The same send, for a caller that has nowhere to put the reason. */
    public void send(String text) {
        send(text, null);
    }

    @Override
    public void cellLoaded(CellID cellId, Cell cell) {
        if (!CellID.getEnvironmentCellID().equals(cellId)) {
            return;
        }
        // No `started` test here: stop() removes this object from the cache's
        // listener set, so a callback can only arrive while the channel is up.
        synchronized (this) {
            if (worldCell == null) {
                attach(cell);
            }
        }
    }

    @Override
    public void cellLoadFailed(CellID cellId, String className, CellID parentCellID,
            Throwable cause) {
        // No channel, so no chat: the send path already says so, and a world
        // that failed to load has a louder problem than a quiet transcript.
    }

    @Override
    public void cellUnloaded(CellID cellId, Cell cell) {
        if (cell == null || cell != worldCell) {
            return;
        }
        synchronized (this) {
            worldCell = null;
        }
        detach();
    }

    @Override
    public void messageReceived(CellMessage message) {
        if (!(message instanceof TextChatMessage)) {
            return;
        }
        TextChatMessage chat = (TextChatMessage) message;
        for (Listener listener : listeners) {
            listener.chatReceived(chat.getSenderName(), chat.getText(),
                    chat.getSentAt(), chat.getSenderID());
        }
    }

    /** The receiver goes on the cell's channel; the id is already the world's. */
    private void attach(Cell cell) {
        MessageChannel channel = cell.getChannel();
        if (channel == null) {
            // Nothing to attach to yet; the cache listener stays registered so
            // a later load of the same cell gets a second chance at this.
            return;
        }
        worldCell = cell;
        worldChannel = channel;
        channel.addMessageReceiver(TextChatMessage.class, this);
    }

    /** Take the receiver back off the channel that was attached. */
    private void detach() {
        MessageChannel channel = worldChannel;
        worldChannel = null;
        if (channel != null) {
            channel.removeMessageReceiver(TextChatMessage.class);
        }
    }

    private static void fail(Consumer<String> failure, String reason) {
        if (failure != null) {
            failure.accept(reason);
        }
    }
}
