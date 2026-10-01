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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the Messenger model beans: {@link AccountConfig},
 * {@link ChatMessage}, {@link StoredMessage} and {@link MessengerSettings}. They
 * pin the defaults the UI relies on, the null-guarding and clamping that keep a
 * hand-edited or corrupt config from putting the client into an invalid state,
 * the effective-port resolution, deep-copy independence, and the protocol-neutral
 * event classification.
 */
class MessengerModelTest {

    // ------------------------------------------------------------------
    // AccountConfig
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a new account defaults to TLS IRC with a generated id")
    void accountDefaults() {
        AccountConfig a = new AccountConfig();
        assertEquals("irc", a.getProtocolId());
        assertEquals(AccountConfig.PORT_TLS, a.getPort());
        assertTrue(a.isUseTls());
        assertFalse(a.getId() == null || a.getId().isBlank());
        assertEquals("", a.getName());
        assertTrue(a.getAutoJoinChannels().isEmpty());
    }

    @Test
    @DisplayName("the convenience constructor sets name/protocol/host/nick")
    void accountConstructor() {
        AccountConfig a = new AccountConfig("Libera", "irc", "irc.libera.chat", "lg3duser");
        assertEquals("Libera", a.getName());
        assertEquals("irc", a.getProtocolId());
        assertEquals("irc.libera.chat", a.getHost());
        assertEquals("lg3duser", a.getNickname());
        assertEquals("Libera", a.toString());
    }

    @Test
    @DisplayName("getEffectivePort falls back to the transport default")
    void effectivePort() {
        AccountConfig a = new AccountConfig();
        a.setPort(7000);
        assertEquals(7000, a.getEffectivePort());
        a.setPort(0);
        a.setUseTls(true);
        assertEquals(AccountConfig.PORT_TLS, a.getEffectivePort());
        a.setUseTls(false);
        assertEquals(AccountConfig.PORT_PLAIN, a.getEffectivePort());
    }

    @Test
    @DisplayName("account setters null-guard and trim")
    void accountNullSafety() {
        AccountConfig a = new AccountConfig();
        a.setName(null);
        a.setHost("  spaced  ");
        a.setNickname(null);
        a.setProtocolId("   ");
        a.setUsername(null);
        a.setRealName(null);
        a.setAutoJoinChannels(null);
        a.setServerPassword(null);
        a.setNickServPassword(null);
        a.setId(null);
        assertEquals("", a.getName());
        assertEquals("spaced", a.getHost());
        assertEquals("", a.getNickname());
        assertEquals("irc", a.getProtocolId());
        assertEquals("", a.getUsername());
        assertTrue(a.getAutoJoinChannels().isEmpty());
        assertEquals("", a.getServerPassword());
        assertEquals("", a.getNickServPassword());
        assertFalse(a.getId() == null || a.getId().isBlank());
    }

    @Test
    @DisplayName("copy() keeps the id but deep-copies the channel list")
    void accountCopy() {
        AccountConfig a = new AccountConfig("Acc", "irc", "h", "nick");
        a.getAutoJoinChannels().add("#one");
        AccountConfig c = a.copy();
        assertNotSame(a, c);
        assertEquals(a.getId(), c.getId());
        assertEquals("Acc", c.getName());
        assertEquals(1, c.getAutoJoinChannels().size());
        c.getAutoJoinChannels().add("#two");
        assertEquals(1, a.getAutoJoinChannels().size(), "the copy must be independent");
    }

    @Test
    @DisplayName("toString falls back to nick then to protocol://host")
    void accountToString() {
        AccountConfig a = new AccountConfig();
        a.setHost("example.com");
        a.setNickname("bob");
        assertEquals("bob", a.toString());
        a.setNickname("");
        assertEquals("irc://example.com", a.toString());
    }

    // ------------------------------------------------------------------
    // ChatMessage
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ChatMessage null-coerces every field and defaults to SYSTEM")
    void chatMessageNullSafety() {
        ChatMessage m = new ChatMessage(null, null, null, null);
        assertEquals("", m.getFrom());
        assertEquals("", m.getTarget());
        assertEquals("", m.getText());
        assertEquals(ChatMessage.Kind.SYSTEM, m.getKind());
        assertTrue(m.getEpochMs() > 0);
    }

    @Test
    @DisplayName("the system/error factories build the right kinds")
    void chatMessageFactories() {
        assertEquals(ChatMessage.Kind.SYSTEM, ChatMessage.system("hi").getKind());
        assertEquals("hi", ChatMessage.system("hi").getText());
        assertEquals(ChatMessage.Kind.ERROR, ChatMessage.error("boom").getKind());
    }

    @Test
    @DisplayName("isChat and isPresence classify the kinds")
    void chatMessageClassification() {
        assertTrue(new ChatMessage("a", "#c", "t", ChatMessage.Kind.PRIVMSG).isChat());
        assertTrue(new ChatMessage("a", "#c", "t", ChatMessage.Kind.ACTION).isChat());
        assertTrue(new ChatMessage("a", "#c", "t", ChatMessage.Kind.NOTICE).isChat());
        assertFalse(new ChatMessage("a", "#c", "t", ChatMessage.Kind.JOIN).isChat());
        assertTrue(new ChatMessage("a", "#c", "", ChatMessage.Kind.JOIN).isPresence());
        assertTrue(new ChatMessage("a", "#c", "", ChatMessage.Kind.TOPIC).isPresence());
        assertFalse(new ChatMessage("a", "#c", "t", ChatMessage.Kind.PRIVMSG).isPresence());
    }

    // ------------------------------------------------------------------
    // StoredMessage
    // ------------------------------------------------------------------

    @Test
    @DisplayName("StoredMessage.from detects a channel target")
    void storedMessageFrom() {
        ChatMessage cm = new ChatMessage("alice", "#lg3d", "hello", ChatMessage.Kind.PRIVMSG, 1234L);
        StoredMessage sm = StoredMessage.from("acct-1", cm);
        assertEquals("acct-1", sm.getAccountId());
        assertEquals("#lg3d", sm.getTarget());
        assertTrue(sm.isChannel());
        assertEquals("alice", sm.getSender());
        assertEquals("hello", sm.getText());
        assertEquals("PRIVMSG", sm.getKind());
        assertEquals(1234L, sm.getEpochMs());
        assertEquals(ChatMessage.Kind.PRIVMSG, sm.kindEnum());
    }

    @Test
    @DisplayName("a peer or &-prefixed target is classified correctly")
    void storedMessageChannelDetection() {
        assertFalse(StoredMessage.from("a",
                new ChatMessage("x", "bob", "hi", ChatMessage.Kind.PRIVMSG)).isChannel());
        assertTrue(StoredMessage.from("a",
                new ChatMessage("x", "&chan", "hi", ChatMessage.Kind.PRIVMSG)).isChannel());
    }

    @Test
    @DisplayName("an unknown kind name falls back to PRIVMSG")
    void storedMessageUnknownKind() {
        StoredMessage sm = new StoredMessage();
        sm.setKind("NOT_A_REAL_KIND");
        assertEquals(ChatMessage.Kind.PRIVMSG, sm.kindEnum());
        sm.setKind((String) null);
        assertEquals("PRIVMSG", sm.getKind());
        sm.setKind((ChatMessage.Kind) null);
        assertEquals("PRIVMSG", sm.getKind());
    }

    @Test
    @DisplayName("StoredMessage.toString shows sender then text")
    void storedMessageToString() {
        StoredMessage sm = new StoredMessage("a", "#c", true, "alice", "hi",
                ChatMessage.Kind.PRIVMSG, 0L);
        assertEquals("alice: hi", sm.toString());
        sm.setSender("");
        assertEquals("hi", sm.toString());
    }

    // ------------------------------------------------------------------
    // MessengerSettings
    // ------------------------------------------------------------------

    @Test
    @DisplayName("settings start at their documented defaults")
    void settingsDefaults() {
        MessengerSettings s = new MessengerSettings();
        assertTrue(s.isShowTimestamps());
        assertFalse(s.isShowJoinsParts());
        assertTrue(s.isShowSystemMessages());
        assertTrue(s.isNotifyOnMessage());
        assertTrue(s.isColorNicknames());
        assertEquals(13, s.getFontSize());
        assertEquals(500, s.getHistoryLimit());
        assertEquals(MessengerSettings.DEFAULT_PROTOCOL, s.getDefaultProtocolId());
        assertTrue(s.isAutoReconnect());
        assertEquals(10, s.getReconnectDelaySeconds());
    }

    @Test
    @DisplayName("numeric setters clamp to their valid range")
    void settingsClamp() {
        MessengerSettings s = new MessengerSettings();
        s.setFontSize(1000);
        assertEquals(32, s.getFontSize());
        s.setFontSize(1);
        assertEquals(8, s.getFontSize());
        s.setHistoryLimit(-5);
        assertEquals(0, s.getHistoryLimit());
        s.setReconnectDelaySeconds(0);
        assertEquals(1, s.getReconnectDelaySeconds());
        s.setReconnectDelaySeconds(10_000);
        assertEquals(300, s.getReconnectDelaySeconds());
    }

    @Test
    @DisplayName("a blank default protocol falls back to irc")
    void settingsDefaultProtocol() {
        MessengerSettings s = new MessengerSettings();
        s.setDefaultProtocolId(null);
        assertEquals("irc", s.getDefaultProtocolId());
        s.setDefaultProtocolId("  ");
        assertEquals("irc", s.getDefaultProtocolId());
        s.setDefaultProtocolId("xmpp");
        assertEquals("xmpp", s.getDefaultProtocolId());
    }

    @Test
    @DisplayName("copy() preserves every preference")
    void settingsCopy() {
        MessengerSettings s = new MessengerSettings();
        s.setShowJoinsParts(true);
        s.setFontSize(18);
        s.setHistoryLimit(42);
        s.setDefaultProtocolId("matrix");
        MessengerSettings c = s.copy();
        assertNotSame(s, c);
        assertTrue(c.isShowJoinsParts());
        assertEquals(18, c.getFontSize());
        assertEquals(42, c.getHistoryLimit());
        assertEquals("matrix", c.getDefaultProtocolId());
    }
}
