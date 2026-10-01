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

import java.util.Set;

/**
 * A chat-network backend. This is the Messenger's extension point: the shipped
 * {@link IrcProtocol} is a fully in-process native client, while the
 * {@link BridgeProtocol} family reaches networks that need an external or native
 * stack (XMPP, Matrix, Telegram, WhatsApp, Signal, SMS, SIP) by handing off to
 * the official client / web app. Any future native protocol (for example an
 * XMPP client built on Smack) plugs in by implementing this interface and
 * registering with {@link ProtocolRegistry} &mdash; no UI change required.
 *
 * <p>All network I/O is asynchronous: {@link #connect} returns promptly and
 * reports progress through the {@link ProtocolListener}. Optional operations
 * (channels, actions) have {@code default} no-ops so a minimal backend, such as
 * a one-shot bridge, need not implement them.</p>
 */
public interface MessengerProtocol {

    /** Optional features a backend may support; the UI adapts to these. */
    enum Capability {
        /** Persistent two-way chat. */
        CHAT,
        /** Multi-user rooms/channels. */
        CHANNELS,
        /** Presence / roster information. */
        PRESENCE,
        /** Encrypted transport. */
        TLS,
        /** {@code /me}-style actions. */
        ACTIONS,
        /** Runs entirely in-process (no external client hand-off). */
        NATIVE
    }

    /** The stable, persisted identifier (e.g. {@code "irc"}). */
    String id();

    /** The human-readable name shown in the UI (e.g. {@code "IRC"}). */
    String displayName();

    /** A one-line description of how this backend reaches its network. */
    String description();

    /** True if this backend is a native, in-process client; false for a bridge. */
    boolean isNative();

    /** The feature set the UI should assume for this backend. */
    Set<Capability> capabilities();

    /**
     * Begins an asynchronous connection/registration for {@code account}.
     *
     * @param account  the account to connect
     * @param listener the callback sink for events
     */
    void connect(AccountConfig account, ProtocolListener listener);

    /** Tears down the connection, if any. Idempotent and never throws. */
    void disconnect();

    /** True while a live session is established. */
    boolean isConnected();

    /**
     * Sends a chat message to {@code target} (a channel or peer). A backend that
     * cannot send (a pure bridge) reports this through the listener instead.
     *
     * @param target the conversation target
     * @param text   the message body
     */
    void sendMessage(String target, String text);

    /** Sends a {@code /me} action; defaults to a normal message. */
    default void sendAction(String target, String text) {
        sendMessage(target, text);
    }

    /** Joins a channel/room; a no-op for backends without {@link Capability#CHANNELS}. */
    default void joinChannel(String channel) {
        // not supported by default
    }

    /** Leaves a channel/room; a no-op by default. */
    default void partChannel(String channel, String reason) {
        // not supported by default
    }
}
