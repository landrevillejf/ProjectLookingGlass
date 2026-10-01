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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link MessengerStore}: the Jackson JSON persistence behind
 * the Messenger. They assert accounts/messages/settings round-trip into a temp
 * directory, that <b>no password is ever written</b> (the {@code @JsonIgnore}
 * secrets are absent from the file and empty on reload), that the transcript is
 * trimmed to the newest entries, and that a missing or corrupt file yields the
 * empty/default value rather than throwing.
 */
class MessengerStoreTest {

    @Test
    @DisplayName("accounts round-trip through JSON")
    void roundTripsAccounts(@TempDir Path dir) {
        MessengerStore store = new MessengerStore(dir);
        AccountConfig a = new AccountConfig("Libera", "irc", "irc.libera.chat", "lg3duser");
        a.setPort(6697);
        a.setUseTls(true);
        a.setAutoJoinChannels(List.of("#lg3d", "#java"));
        a.setAutoConnect(true);
        store.saveAccounts(List.of(a));

        List<AccountConfig> loaded = new MessengerStore(dir).loadAccounts();
        assertEquals(1, loaded.size());
        AccountConfig b = loaded.get(0);
        assertEquals(a.getId(), b.getId());
        assertEquals("Libera", b.getName());
        assertEquals("irc.libera.chat", b.getHost());
        assertEquals("lg3duser", b.getNickname());
        assertEquals(6697, b.getPort());
        assertTrue(b.isUseTls());
        assertTrue(b.isAutoConnect());
        assertEquals(List.of("#lg3d", "#java"), b.getAutoJoinChannels());
    }

    @Test
    @DisplayName("no password is ever written to disk")
    void neverPersistsPasswords(@TempDir Path dir) throws Exception {
        MessengerStore store = new MessengerStore(dir);
        AccountConfig a = new AccountConfig("Secretive", "irc", "h", "nick");
        a.setServerPassword("hunter2-server");
        a.setNickServPassword("hunter2-nickserv");
        a.setPasswordPrompt(true);
        store.saveAccounts(List.of(a));

        String json = Files.readString(dir.resolve(MessengerStore.ACCOUNTS_FILE));
        assertFalse(json.contains("hunter2-server"), "the server password must not be persisted");
        assertFalse(json.contains("hunter2-nickserv"), "the NickServ password must not be persisted");
        // The prompt flag survives so the UI knows to ask again.
        assertTrue(json.contains("passwordPrompt"));

        AccountConfig reloaded = new MessengerStore(dir).loadAccounts().get(0);
        assertEquals("", reloaded.getServerPassword());
        assertEquals("", reloaded.getNickServPassword());
        assertTrue(reloaded.isPasswordPrompt());
    }

    @Test
    @DisplayName("messages round-trip and keep the newest up to the limit")
    void trimsMessagesToNewest(@TempDir Path dir) {
        MessengerStore store = new MessengerStore(dir);
        List<StoredMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            msgs.add(new StoredMessage("acct", "#lg3d", true, "u" + i, "m" + i,
                    ChatMessage.Kind.PRIVMSG, 1000L + i));
        }
        store.saveMessages(msgs, 3);

        List<StoredMessage> loaded = new MessengerStore(dir).loadMessages();
        assertEquals(3, loaded.size());
        // The list is chronological, so the tail (newest) is retained.
        assertEquals("m7", loaded.get(0).getText());
        assertEquals("m8", loaded.get(1).getText());
        assertEquals("m9", loaded.get(2).getText());
    }

    @Test
    @DisplayName("a non-positive limit persists no history")
    void zeroLimitKeepsNothing(@TempDir Path dir) {
        MessengerStore store = new MessengerStore(dir);
        store.saveMessages(List.of(new StoredMessage("a", "#c", true, "u", "hi",
                ChatMessage.Kind.PRIVMSG, 1L)), 0);
        assertTrue(new MessengerStore(dir).loadMessages().isEmpty());
    }

    @Test
    @DisplayName("a small transcript under the limit is kept whole")
    void keepsWholeTranscriptUnderLimit(@TempDir Path dir) {
        MessengerStore store = new MessengerStore(dir);
        List<StoredMessage> msgs = List.of(
                new StoredMessage("a", "#c", true, "u", "one", ChatMessage.Kind.PRIVMSG, 1L),
                new StoredMessage("a", "#c", true, "u", "two", ChatMessage.Kind.PRIVMSG, 2L));
        store.saveMessages(msgs, 500);
        assertEquals(2, new MessengerStore(dir).loadMessages().size());
    }

    @Test
    @DisplayName("settings round-trip through JSON")
    void roundTripsSettings(@TempDir Path dir) {
        MessengerStore store = new MessengerStore(dir);
        MessengerSettings s = new MessengerSettings();
        s.setShowJoinsParts(true);
        s.setFontSize(17);
        s.setHistoryLimit(250);
        s.setDefaultProtocolId("matrix");
        store.saveSettings(s);

        MessengerSettings loaded = new MessengerStore(dir).loadSettings();
        assertTrue(loaded.isShowJoinsParts());
        assertEquals(17, loaded.getFontSize());
        assertEquals(250, loaded.getHistoryLimit());
        assertEquals("matrix", loaded.getDefaultProtocolId());
    }

    @Test
    @DisplayName("an empty directory yields empty lists and default settings")
    void readsEmptyDirectory(@TempDir Path dir) {
        MessengerStore store = new MessengerStore(dir.resolve("does-not-exist-yet"));
        assertTrue(store.loadAccounts().isEmpty());
        assertTrue(store.loadMessages().isEmpty());
        MessengerSettings s = store.loadSettings();
        assertEquals(13, s.getFontSize());
        assertEquals(MessengerSettings.DEFAULT_PROTOCOL, s.getDefaultProtocolId());
    }

    @Test
    @DisplayName("a corrupt file yields the empty/default value, never a throw")
    void readsCorruptFiles(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MessengerStore.ACCOUNTS_FILE), "{ not valid json ]");
        Files.writeString(dir.resolve(MessengerStore.MESSAGES_FILE), "[1,2,3");
        Files.writeString(dir.resolve(MessengerStore.SETTINGS_FILE), "###");

        MessengerStore store = new MessengerStore(dir);
        assertTrue(store.loadAccounts().isEmpty());
        assertTrue(store.loadMessages().isEmpty());
        assertEquals(13, store.loadSettings().getFontSize());
    }

    @Test
    @DisplayName("saveSettings(null) writes defaults rather than failing")
    void savesNullSettings(@TempDir Path dir) {
        MessengerStore store = new MessengerStore(dir);
        store.saveSettings(null);
        assertEquals(13, new MessengerStore(dir).loadSettings().getFontSize());
    }

    @Test
    @DisplayName("defaultConfigDir honours the override system property")
    void respectsDirProperty() {
        String override = "/tmp/lg3d-messenger-test-dir";
        String previous = System.getProperty(MessengerStore.DIR_PROPERTY);
        try {
            System.setProperty(MessengerStore.DIR_PROPERTY, override);
            assertEquals(Paths.get(override), MessengerStore.defaultConfigDir());
            assertEquals(Paths.get(override), new MessengerStore().getConfigDir());
        } finally {
            if (previous == null) {
                System.clearProperty(MessengerStore.DIR_PROPERTY);
            } else {
                System.setProperty(MessengerStore.DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("defaultConfigDir falls back to ~/.lg3d/messenger")
    void defaultsToHomeDirectory() {
        String previous = System.getProperty(MessengerStore.DIR_PROPERTY);
        try {
            System.clearProperty(MessengerStore.DIR_PROPERTY);
            Path expected = Paths.get(System.getProperty("user.home"), ".lg3d", "messenger");
            assertEquals(expected, MessengerStore.defaultConfigDir());
        } finally {
            if (previous != null) {
                System.setProperty(MessengerStore.DIR_PROPERTY, previous);
            }
        }
    }
}
