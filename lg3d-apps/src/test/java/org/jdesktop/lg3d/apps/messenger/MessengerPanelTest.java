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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jdesktop.lg3d.contacts.Contact;
import org.jdesktop.lg3d.contacts.ContactStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link MessengerPanel}: the Swing face of the Messenger.
 * They build the panel against a temp-directory store, check its default state,
 * exercise the pure {@code /slash}-command parser and the token/timestamp/colour
 * helpers, drive {@code submitInput} down its no-connection guard paths (which
 * only set a status line, never open a socket or a browser), verify an added
 * account persists, and confirm {@code shutdown()} runs the close hook. No X
 * display, socket, browser or clipboard is touched.
 */
class MessengerPanelTest {

    @Test
    @DisplayName("the panel sizes itself to the desktop window and starts empty")
    void defaultState(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            assertEquals(MessengerPanel.WIDTH_PX, panel.getPreferredSize().width);
            assertEquals(MessengerPanel.HEIGHT_PX, panel.getPreferredSize().height);
            assertTrue(panel.accounts().isEmpty());
            assertTrue(panel.conversations().isEmpty());
            assertTrue(panel.transcript().isEmpty());
            assertEquals(13, panel.settings().getFontSize());
            assertTrue(panel.registry().isNative("irc"));
            assertNotNull(panel.statusLbl());
            // The empty transcript invites the user to open a conversation.
            assertTrue(panel.transcriptText().toLowerCase().contains("conversation"));
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("parseCommand returns null for plain text and parses verbs")
    void parsesCommands() {
        assertNull(MessengerPanel.parseCommand("hello"));
        assertNull(MessengerPanel.parseCommand(""));
        assertNull(MessengerPanel.parseCommand(null));
        assertNull(MessengerPanel.parseCommand("   "));

        MessengerPanel.ParsedCommand join = MessengerPanel.parseCommand("/join #lg3d");
        assertEquals("join", join.verb());
        assertEquals(List.of("#lg3d"), join.args());
        assertEquals("#lg3d", join.rest());

        // The verb is lower-cased; the raw remainder keeps the original spacing.
        MessengerPanel.ParsedCommand msg = MessengerPanel.parseCommand("  /MSG nick hi there  ");
        assertEquals("msg", msg.verb());
        assertEquals(List.of("nick", "hi", "there"), msg.args());
        assertEquals("nick hi there", msg.rest());

        MessengerPanel.ParsedCommand bare = MessengerPanel.parseCommand("/quit");
        assertEquals("quit", bare.verb());
        assertTrue(bare.args().isEmpty());
        assertEquals("", bare.rest());
    }

    @Test
    @DisplayName("the token helpers split a target from its remainder")
    void tokenHelpers() {
        assertEquals("nick", MessengerPanel.firstToken("nick hello there"));
        assertEquals("hello there", MessengerPanel.afterFirstToken("nick hello there"));
        assertEquals("solo", MessengerPanel.firstToken("solo"));
        assertEquals("", MessengerPanel.afterFirstToken("solo"));
        assertEquals("", MessengerPanel.firstToken(null));
        assertEquals("", MessengerPanel.afterFirstToken(null));
    }

    @Test
    @DisplayName("formatTimestamp renders nothing for a non-positive epoch")
    void formatsTimestamp() {
        assertEquals("", MessengerPanel.formatTimestamp(0));
        assertEquals("", MessengerPanel.formatTimestamp(-1));
        assertFalse(MessengerPanel.formatTimestamp(System.currentTimeMillis()).isEmpty());
    }

    @Test
    @DisplayName("nickColor is deterministic and null-safe")
    void coloursNicknames() {
        assertNotNull(MessengerPanel.nickColor("alice"));
        assertEquals(MessengerPanel.nickColor("alice"), MessengerPanel.nickColor("alice"));
        assertEquals(MessengerPanel.nickColor(null), MessengerPanel.nickColor(""));
    }

    @Test
    @DisplayName("an added account persists and is reloaded by a fresh panel")
    void accountPersists(@TempDir Path dir) {
        MessengerStore store = new MessengerStore(dir);
        MessengerPanel first = new MessengerPanel(store);
        try {
            first.addAccountForTest(new AccountConfig("Libera", "irc", "irc.libera.chat", "me"));
            assertEquals(1, first.accounts().size());
            assertEquals(1, first.accountModel().getSize());
        } finally {
            first.shutdown();
        }

        MessengerPanel second = new MessengerPanel(new MessengerStore(dir));
        try {
            assertEquals(1, second.accounts().size());
            assertEquals("Libera", second.accounts().get(0).getName());
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("plain text with no conversation only sets a status line")
    void sendWithoutConversation(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            panel.submitInput("hello world");
            assertFalse(panel.statusLbl().getText().isEmpty());
            assertTrue(panel.transcript().isEmpty(), "nothing is sent without a target");
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("/join without an account asks for one and opens no socket")
    void joinWithoutAccount(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            panel.submitInput("/join #lg3d");
            assertTrue(panel.statusLbl().getText().toLowerCase().contains("account"),
                    panel.statusLbl().getText());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("/help prints the command list to the transcript")
    void helpIsPrinted(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            panel.submitInput("/help");
            String text = panel.transcriptText();
            assertTrue(text.contains("/join"), text);
            assertTrue(text.contains("/msg"), text);
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("an unknown command is reported, not thrown")
    void unknownCommand(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            panel.submitInput("/frobnicate");
            assertTrue(panel.statusLbl().getText().contains("frobnicate"));
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("connecting an unknown protocol id reports it and opens no socket")
    void connectUnknownProtocol(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            AccountConfig a = new AccountConfig("Bad", "carrier-pigeon", "h", "me");
            panel.addAccountForTest(a);
            panel.connectAccount(a);
            assertTrue(panel.statusLbl().getText().contains("Unknown protocol"),
                    panel.statusLbl().getText());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("connect/disconnect with no selection set a guiding status")
    void connectDisconnectNullSafe(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            panel.connectAccount(null);
            assertTrue(panel.statusLbl().getText().toLowerCase().contains("select"));
            panel.disconnectAccount(null);
            assertTrue(panel.statusLbl().getText().toLowerCase().contains("select"));
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("Conversation identity is (accountId, target)")
    void conversationIdentity() {
        MessengerPanel.Conversation a = new MessengerPanel.Conversation("acct", "#lg3d", "LG3D");
        MessengerPanel.Conversation b = new MessengerPanel.Conversation("acct", "#lg3d", "other label");
        MessengerPanel.Conversation console = new MessengerPanel.Conversation("acct", "", "console");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.isChannel());
        assertFalse(a.isConsole());
        assertTrue(console.isConsole());
        assertEquals("LG3D", a.displayName());
        assertNotNull(a.key());
    }

    @Test
    @DisplayName("shutdown() runs the close hook and is safe with no connections")
    void shutdownRunsHook(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        AtomicBoolean closed = new AtomicBoolean(false);
        panel.setOnClose(() -> closed.set(true));
        panel.shutdown();
        assertTrue(closed.get());
    }

    @Test
    @DisplayName("a private-chat peer is saved into the shared address book")
    void savePeerToSharedAddressBook(@TempDir Path dir, @TempDir Path contactsDir) {
        ContactStore addressBook = new ContactStore(contactsDir);
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir),
                ProtocolRegistry.standard(), addressBook);
        try {
            assertSame(addressBook, panel.addressBook());
            MessengerPanel.Conversation peer =
                    new MessengerPanel.Conversation("acct", "alice", "Alice");
            MessengerPanel.Conversation channel =
                    new MessengerPanel.Conversation("acct", "#lg3d", "LG3D");
            MessengerPanel.Conversation console =
                    new MessengerPanel.Conversation("acct", "", "console");

            // Only private chats are saved; channels and the console are refused.
            assertFalse(panel.savePeerToAddressBook(channel));
            assertFalse(panel.savePeerToAddressBook(console));
            assertFalse(panel.savePeerToAddressBook(null));
            assertEquals(0, addressBook.size());

            assertTrue(panel.savePeerToAddressBook(peer));
            assertEquals(1, addressBook.size());
            Contact saved = addressBook.all().get(0);
            assertEquals("alice", saved.displayName());
            assertEquals("alice", saved.getNickname());
            assertTrue(saved.getTags().contains("messenger"));

            // Saving the same peer again (any case) is a dedup no-op, and the
            // contact persists for the Contacts app to see.
            assertFalse(panel.savePeerToAddressBook(
                    new MessengerPanel.Conversation("acct", "ALICE", "Alice")));
            assertEquals(1, addressBook.size());
            assertEquals(1, new ContactStore(contactsDir).size());
        } finally {
            panel.shutdown();
        }
    }
}
