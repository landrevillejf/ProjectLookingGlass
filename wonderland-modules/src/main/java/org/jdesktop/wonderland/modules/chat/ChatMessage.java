/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (text-chat): one posted chat line.
 *
 * The 2006 chat module painted these into a Swing conversation window; that
 * presentation is deferred to the desktop integration (M6/M7). This is the
 * immutable value the headless model and, later, the wire share.
 */
package org.jdesktop.wonderland.modules.chat;

import java.util.Objects;

/** An immutable chat line: who said what, and when. */
public final class ChatMessage {

    private final String sender;
    private final String text;
    private final long timestamp;

    /**
     * @param sender display name of the author, non-blank
     * @param text message body, non-blank
     * @param timestamp epoch millis the message was posted
     */
    public ChatMessage(String sender, String text, long timestamp) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.text = Objects.requireNonNull(text, "text");
        this.timestamp = timestamp;
    }

    public String getSender() {
        return sender;
    }

    public String getText() {
        return text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return sender + ": " + text;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ChatMessage)) {
            return false;
        }
        ChatMessage other = (ChatMessage) obj;
        return timestamp == other.timestamp
                && sender.equals(other.sender)
                && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender, text, timestamp);
    }
}
