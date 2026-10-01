/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.messenger;

/**
 * One persisted transcript line, keyed to an account and a conversation target.
 * Stored as JSON by {@link MessengerStore} so history survives restarts. The
 * {@link ChatMessage.Kind} is kept as its {@code name()} string to stay
 * forward-compatible if a new kind is added.
 */
public class StoredMessage {

    private String accountId = "";
    private String target = "";
    private boolean channel;
    private String sender = "";
    private String text = "";
    private String kind = ChatMessage.Kind.PRIVMSG.name();
    private long epochMs;

    public StoredMessage() {
    }

    public StoredMessage(String accountId, String target, boolean channel,
                         String sender, String text, ChatMessage.Kind kind, long epochMs) {
        setAccountId(accountId);
        setTarget(target);
        this.channel = channel;
        setSender(sender);
        setText(text);
        setKind(kind);
        this.epochMs = epochMs;
    }

    /** Builds a stored line from a live {@link ChatMessage}. */
    public static StoredMessage from(String accountId, ChatMessage msg) {
        boolean isChan = msg.getTarget().startsWith("#") || msg.getTarget().startsWith("&");
        return new StoredMessage(accountId, msg.getTarget(), isChan, msg.getFrom(),
                msg.getText(), msg.getKind(), msg.getEpochMs());
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = (accountId == null) ? "" : accountId; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = (target == null) ? "" : target; }

    public boolean isChannel() { return channel; }
    public void setChannel(boolean channel) { this.channel = channel; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = (sender == null) ? "" : sender; }

    public String getText() { return text; }
    public void setText(String text) { this.text = (text == null) ? "" : text; }

    public String getKind() { return kind; }

    public void setKind(String kind) {
        this.kind = (kind == null || kind.isBlank()) ? ChatMessage.Kind.PRIVMSG.name() : kind;
    }

    public void setKind(ChatMessage.Kind kind) {
        this.kind = (kind == null) ? ChatMessage.Kind.PRIVMSG.name() : kind.name();
    }

    /** The parsed kind, defaulting to {@code PRIVMSG} for an unknown name. */
    public ChatMessage.Kind kindEnum() {
        try {
            return ChatMessage.Kind.valueOf(kind);
        } catch (IllegalArgumentException ex) {
            return ChatMessage.Kind.PRIVMSG;
        }
    }

    public long getEpochMs() { return epochMs; }
    public void setEpochMs(long epochMs) { this.epochMs = epochMs; }

    @Override
    public String toString() {
        return (sender == null || sender.isEmpty() ? "" : sender + ": ") + text;
    }
}
