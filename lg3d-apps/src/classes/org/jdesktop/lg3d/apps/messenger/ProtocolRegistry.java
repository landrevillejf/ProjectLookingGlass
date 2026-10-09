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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The catalogue of chat backends the Messenger can use, and the factory that
 * builds a fresh instance per connection (backends such as {@link IrcProtocol}
 * are stateful, so each account gets its own).
 *
 * <p>{@link #standard()} registers the shipped set: the <b>native IRC</b> client,
 * the <b>native P2P</b> direct/encrypted backend, plus <b>bridge</b> backends for
 * XMPP, Matrix, Telegram, WhatsApp, Signal, SMS and SIP (which hand off to the
 * network's official client/web app). Adding a new protocol &mdash; native or
 * bridge &mdash; is a single {@link #register} call here; nothing in the UI
 * changes. That is how "every known protocol" is approached honestly: real,
 * tested native clients and an extensible seam for the rest.</p>
 */
public final class ProtocolRegistry {

    /** Metadata + factory for one registered backend. */
    public record ProtocolInfo(String id, String displayName, String description,
                               boolean isNative, Supplier<MessengerProtocol> factory) {
    }

    private final Map<String, ProtocolInfo> byId = new LinkedHashMap<>();

    /**
     * Builds the standard registry with the shipped native and bridge backends.
     *
     * @return a populated registry
     */
    public static ProtocolRegistry standard() {
        ProtocolRegistry r = new ProtocolRegistry();
        // The one fully native, in-process, unit-tested backend.
        r.register(new ProtocolInfo("irc", "IRC",
                "Native IRC client (RFC 2812) over a plain or TLS socket.",
                true, IrcProtocol::new));
        // A second native backend: encrypted peer-to-peer, no server in between.
        r.register(new ProtocolInfo("p2p", "P2P (Direct)",
                "Encrypted peer-to-peer chat and file transfer (X25519 + AES-256-GCM), "
                        + "direct or LAN-discovered; no server.",
                true, P2pProtocol::new));
        // Bridges: real networks reached through their official client/web app.
        r.register(new ProtocolInfo("xmpp", "XMPP / Jabber",
                "Opens your XMPP client (Dino, Gajim, Conversations) for the JID.",
                false, () -> new BridgeProtocol("xmpp", "XMPP / Jabber",
                        "Hands off to your installed XMPP client.",
                        "xmpp:%TARGET", null)));
        r.register(new ProtocolInfo("matrix", "Matrix",
                "Opens Element (the Matrix web client) for the room or user.",
                false, () -> new BridgeProtocol("matrix", "Matrix",
                        "Hands off to Element (Matrix web client).",
                        "https://app.element.io/#/room/%TARGET", null)));
        r.register(new ProtocolInfo("telegram", "Telegram",
                "Opens Telegram (web or app) for the username.",
                false, () -> new BridgeProtocol("telegram", "Telegram",
                        "Hands off to Telegram.",
                        "https://t.me/%TARGET", null)));
        r.register(new ProtocolInfo("whatsapp", "WhatsApp",
                "Opens WhatsApp (web or app) for the phone number.",
                false, () -> new BridgeProtocol("whatsapp", "WhatsApp",
                        "Hands off to WhatsApp.",
                        "https://wa.me/%TARGET", null)));
        r.register(new ProtocolInfo("signal", "Signal",
                "Opens Signal Desktop for the number or username.",
                false, () -> new BridgeProtocol("signal", "Signal",
                        "Hands off to Signal Desktop.",
                        null, "signal")));
        r.register(new ProtocolInfo("sms", "SMS",
                "Opens your SMS/messaging app for the phone number.",
                false, () -> new BridgeProtocol("sms", "SMS",
                        "Hands off to your SMS app.",
                        "sms:%TARGET", null)));
        r.register(new ProtocolInfo("sip", "SIP",
                "Opens your SIP softphone for the address.",
                false, () -> new BridgeProtocol("sip", "SIP",
                        "Hands off to your SIP softphone.",
                        "sip:%TARGET", null)));
        return r;
    }

    /** Registers (or replaces) a backend. */
    public void register(ProtocolInfo info) {
        if (info != null && info.id() != null && !info.id().isBlank()) {
            byId.put(info.id(), info);
        }
    }

    /**
     * Builds a fresh backend instance for {@code id}.
     *
     * @param id the protocol id
     * @return a new instance, or null if the id is unknown
     */
    public MessengerProtocol create(String id) {
        ProtocolInfo info = (id == null) ? null : byId.get(id);
        return (info == null) ? null : info.factory().get();
    }

    /** The metadata for {@code id}, or null. */
    public ProtocolInfo info(String id) {
        return (id == null) ? null : byId.get(id);
    }

    /** True if {@code id} is a known native (in-process) backend. */
    public boolean isNative(String id) {
        ProtocolInfo info = info(id);
        return info != null && info.isNative();
    }

    /** The display name for {@code id}, or the id itself if unknown. */
    public String displayName(String id) {
        ProtocolInfo info = info(id);
        return (info == null) ? (id == null ? "" : id) : info.displayName();
    }

    /** True if {@code id} is registered. */
    public boolean contains(String id) {
        return id != null && byId.containsKey(id);
    }

    /** All registered backends, in registration order. */
    public List<ProtocolInfo> protocols() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    /** All registered ids, in registration order. */
    public List<String> ids() {
        return byId.keySet().stream().toList();
    }
}
