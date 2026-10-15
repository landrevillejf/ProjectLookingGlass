/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland chat (M9): the wire form of one typed chat line.
 *
 * Why it is a CellMessage addressed at the environment cell rather than a bare
 * session-level Message: the client's CellChannelConnection demultiplexes
 * inbound traffic by CellID and hands each message to that cell's channel
 * (CellChannelConnection.handleMessage casts to CellMessage without checking,
 * by 2006 design). A message that is not cell-scoped therefore has nowhere to
 * land without cutting a new seam into a verbatim-ported class. The world's
 * root is the one cell every session is guaranteed to have loaded -- the
 * server's SET_VIEW fan-out sends the environment before any content -- and
 * CellClientSession installs a real ChannelComponent on every cell as it
 * loads, so the root is a working address with no new machinery. It is also
 * what the 2006 module did in spirit: chat belonged to the room, not to a
 * socket.
 *
 * The sender's *name* is a claim a client makes; the sender's *id* is a fact
 * the server stamps (CellMessage.getSenderID(), the same echo-suppression key
 * a move carries). ProtocolRouter therefore rewrites the name from the
 * session's authenticated identity before it broadcasts, so no client can say
 * something on someone else's behalf.
 */
package org.jdesktop.wonderland.common.cell.messages;

import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellID;

/**
 * One chat line posted to a world: who said it, what they said, and when they
 * said it. Immutable in intent -- the fields exist for Java serialisation, and
 * the only writer after construction is the server's authoritative rewrite,
 * which builds a new message rather than editing the one it received.
 */
@InternalAPI
public final class TextChatMessage extends CellMessage {

    /**
     * The longest body the wire carries. A chat line is a sentence, not a
     * document; the bound is what keeps one client from filling the server's
     * outbound queue with a megabyte of text stamped as a message from everyone.
     */
    public static final int MAX_TEXT_CHARS = 1000;

    /** The same bound for a display name, which is a username, not an essay. */
    public static final int MAX_NAME_CHARS = 64;

    private static final long serialVersionUID = 1L;

    private String senderName;
    private String text;
    private long sentAt;

    /**
     * @param cellID    the cell the line is addressed to -- the world's
     *                  environment cell, the one cell every session loads
     * @param senderName the display name of the author, non-blank
     * @param text      the body, non-blank and no longer than
     *                  {@link #MAX_TEXT_CHARS}
     * @throws IllegalArgumentException on a blank or over-long field, so a
     *         message that cannot be shown is never put on the wire
     */
    public TextChatMessage(CellID cellID, String senderName, String text) {
        this(cellID, senderName, text, System.currentTimeMillis());
    }

    /**
     * The full form, used by the server when it re-stamps a received line with
     * the sender's authenticated name: the moment the author typed it is the
     * moment everyone else should show, not the moment the relay ran.
     */
    public TextChatMessage(CellID cellID, String senderName, String text, long sentAt) {
        super(cellID);
        this.senderName = require(senderName, MAX_NAME_CHARS, "sender name");
        this.text = require(text, MAX_TEXT_CHARS, "chat text");
        this.sentAt = sentAt;
    }

    /** The display name the line is shown under. */
    public String getSenderName() {
        return senderName;
    }

    /** The body. */
    public String getText() {
        return text;
    }

    /** Epoch millis at which the author posted it. */
    public long getSentAt() {
        return sentAt;
    }

    /**
     * A blank name is an anonymous line, which the transcript cannot render and
     * the roster cannot attribute; a blank body is a keystroke. Both are refused
     * at construction rather than filtered by every reader downstream.
     */
    private static String require(String value, int maxChars, String what) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(what + " longer than " + maxChars
                    + " characters: " + value.length());
        }
        return value;
    }

    @Override
    public String toString() {
        return "TextChatMessage[" + getSenderName() + ": " + text + "]";
    }
}
