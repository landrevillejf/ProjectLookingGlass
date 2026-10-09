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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the protocol seam: {@link ProtocolRegistry} (the backend
 * catalogue/factory) and {@link BridgeProtocol} (the deep-link/external-command
 * hand-off). They assert the shipped set (two native clients, IRC and P2P, plus
 * seven bridges), that a fresh stateful instance is built per connection, the
 * extensibility seam (registering a new backend needs no UI change), the
 * placeholder expansion, and that a bridge is inert and honest when headless
 * (it fires listener events and never launches a browser or a command).
 */
class ProtocolRegistryTest {

    @Test
    @DisplayName("standard() ships IRC and P2P plus the seven bridges")
    void standardRegistry() {
        ProtocolRegistry r = ProtocolRegistry.standard();
        List<String> ids = r.ids();
        assertEquals(List.of("irc", "p2p", "xmpp", "matrix", "telegram", "whatsapp",
                "signal", "sms", "sip"), ids);
        assertEquals(9, r.protocols().size());
        assertTrue(r.contains("irc"));
        assertTrue(r.contains("p2p"));
        assertTrue(r.contains("matrix"));
        assertFalse(r.contains("carrier-pigeon"));
        assertFalse(r.contains(null));
    }

    @Test
    @DisplayName("IRC and P2P are the native, in-process backends")
    void nativeFlag() {
        ProtocolRegistry r = ProtocolRegistry.standard();
        assertTrue(r.isNative("irc"));
        assertTrue(r.isNative("p2p"));
        assertFalse(r.isNative("xmpp"));
        assertFalse(r.isNative("unknown"));
        assertTrue(r.info("irc").isNative());
        assertTrue(r.info("p2p").isNative());
        assertFalse(r.info("telegram").isNative());
        assertNull(r.info(null));
    }

    @Test
    @DisplayName("create() builds the right backend type, fresh each time")
    void createsBackends() {
        ProtocolRegistry r = ProtocolRegistry.standard();
        assertInstanceOf(IrcProtocol.class, r.create("irc"));
        assertInstanceOf(P2pProtocol.class, r.create("p2p"));
        assertInstanceOf(BridgeProtocol.class, r.create("xmpp"));
        assertNotSame(r.create("irc"), r.create("irc"),
                "backends are stateful, so each connection needs its own");
        assertNotSame(r.create("p2p"), r.create("p2p"));
        assertNull(r.create("unknown"));
        assertNull(r.create(null));
    }

    @Test
    @DisplayName("displayName resolves a label or echoes an unknown id")
    void displayNames() {
        ProtocolRegistry r = ProtocolRegistry.standard();
        assertEquals("IRC", r.displayName("irc"));
        assertEquals("P2P (Direct)", r.displayName("p2p"));
        assertEquals("Matrix", r.displayName("matrix"));
        assertEquals("mystery", r.displayName("mystery"));
        assertEquals("", r.displayName(null));
    }

    @Test
    @DisplayName("a newly registered backend is immediately usable")
    void registersCustomBackend() {
        ProtocolRegistry r = new ProtocolRegistry();
        assertFalse(r.contains("custom"));
        r.register(new ProtocolRegistry.ProtocolInfo("custom", "Custom",
                "A test backend", false,
                () -> new BridgeProtocol("custom", "Custom", "d", "custom:%TARGET", null)));
        assertTrue(r.contains("custom"));
        assertInstanceOf(BridgeProtocol.class, r.create("custom"));
        assertEquals("Custom", r.displayName("custom"));
        // A blank/null id is ignored rather than corrupting the catalogue.
        int before = r.ids().size();
        r.register(new ProtocolRegistry.ProtocolInfo(null, "X", "d", false, IrcProtocol::new));
        r.register(new ProtocolRegistry.ProtocolInfo("  ", "X", "d", false, IrcProtocol::new));
        r.register(null);
        assertEquals(before, r.ids().size());
    }

    // ------------------------------------------------------------------
    // BridgeProtocol
    // ------------------------------------------------------------------

    @Test
    @DisplayName("expand substitutes %TARGET, %HOST and %NICK")
    void expandsPlaceholders() {
        AccountConfig a = new AccountConfig("Acc", "xmpp", "example.com", "mynick");
        assertEquals("xmpp:bob@example.com",
                BridgeProtocol.expand("xmpp:%TARGET@%HOST", a, "bob"));
        assertEquals("hi mynick", BridgeProtocol.expand("hi %NICK", a, null));
        // A null account/target expands to empty strings, never null.
        assertEquals("t=", BridgeProtocol.expand("t=%TARGET", null, null));
        assertEquals("", BridgeProtocol.expand(null, a, "bob"));
        assertEquals("", BridgeProtocol.expand("   ", a, "bob"));
    }

    @Test
    @DisplayName("buildUrl/buildCommand expand against the connected account")
    void buildsUrlAndCommand() {
        BridgeProtocol b = new BridgeProtocol("t", "T", "d",
                "https://chat/%TARGET", "run %TARGET");
        // Before connect there is no account, so host/nick placeholders are blank.
        assertEquals("https://chat/bob", b.buildUrl("bob"));
        assertEquals("run bob", b.buildCommand("bob"));
        assertEquals("T", b.displayName());
        assertEquals("t", b.id());
        assertFalse(b.isNative());
        assertEquals(Set.of(MessengerProtocol.Capability.CHAT), b.capabilities());
    }

    @Test
    @DisplayName("connect() is inert when headless but still fires events")
    void connectIsHeadlessSafe() {
        BridgeProtocol b = new BridgeProtocol("matrix", "Matrix", "d",
                "https://app.element.io/#/room/%TARGET", null);
        RecordingListener l = new RecordingListener();
        AccountConfig a = new AccountConfig("M", "matrix", "matrix.org", "me");

        assertFalse(b.isConnected());
        b.connect(a, l);

        assertTrue(b.isConnected());
        assertEquals(1, l.connected.size());
        assertFalse(l.status.isEmpty(), "a status line is emitted while opening");
        assertEquals(1, l.messages.size());
        ChatMessage sys = l.messages.get(0);
        assertEquals(ChatMessage.Kind.SYSTEM, sys.getKind());
        // Headless: nothing could be launched, so the message says to do it manually.
        assertTrue(sys.getText().contains("manually"), sys.getText());

        b.disconnect();
        assertFalse(b.isConnected());
        assertEquals(1, l.disconnected.size());
    }

    @Test
    @DisplayName("sendMessage() explains the hand-off instead of failing")
    void sendMessageIsHonest() {
        BridgeProtocol b = new BridgeProtocol("telegram", "Telegram", "d",
                "https://t.me/%TARGET", null);
        RecordingListener l = new RecordingListener();
        b.connect(new AccountConfig("T", "telegram", "", "me"), l);
        l.messages.clear();

        b.sendMessage("alice", "hello");

        assertEquals(1, l.messages.size());
        ChatMessage m = l.messages.get(0);
        assertEquals(ChatMessage.Kind.SYSTEM, m.getKind());
        assertEquals("alice", m.getTarget());
        assertTrue(m.getText().contains("Telegram"));
    }

    @Test
    @DisplayName("splitCommand honours quoted arguments")
    void splitsCommands() {
        assertEquals(List.of("signal"), BridgeProtocol.splitCommand("signal"));
        assertEquals(List.of("/opt/my apps/signal", "--use-tray"),
                BridgeProtocol.splitCommand("\"/opt/my apps/signal\" --use-tray"));
        assertEquals(List.of("a", "b"), BridgeProtocol.splitCommand("  a   b "));
        assertTrue(BridgeProtocol.splitCommand(null).isEmpty());
    }

    /** Captures listener callbacks so the headless bridge can be asserted on. */
    private static final class RecordingListener implements ProtocolListener {
        final List<AccountConfig> connected = new ArrayList<>();
        final List<String> disconnected = new ArrayList<>();
        final List<ChatMessage> messages = new ArrayList<>();
        final List<String> status = new ArrayList<>();

        @Override public void onConnected(AccountConfig account) { connected.add(account); }
        @Override public void onDisconnected(AccountConfig account, String reason) {
            disconnected.add(reason);
        }
        @Override public void onMessage(AccountConfig account, ChatMessage message) {
            messages.add(message);
        }
        @Override public void onStatus(AccountConfig account, String s) { status.add(s); }
    }
}
