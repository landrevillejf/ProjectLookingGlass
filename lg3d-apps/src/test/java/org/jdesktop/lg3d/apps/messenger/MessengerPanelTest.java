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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    @Test
    @DisplayName("Send File is enabled only for a connected FILE_TRANSFER peer")
    void sendFileGatedOnCapability(@TempDir Path dir) throws Exception {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            AccountConfig a = new AccountConfig("P2P", "p2p", "", "me");
            panel.addAccountForTest(a);
            MessengerPanel.Conversation peer =
                    new MessengerPanel.Conversation(a.getId(), "bob", "Bob");
            MessengerPanel.Conversation console =
                    new MessengerPanel.Conversation(a.getId(), "", "console");

            // No backend yet: nothing is enabled, and Find Peers needs a real P2P node.
            panel.selectConversationForTest(peer);
            assertFalse(panel.sendFileButton().isEnabled());
            assertFalse(panel.cancelFileButton().isEnabled());
            assertFalse(panel.findPeersButton().isEnabled());

            // A connected backend WITHOUT file transfer still cannot send.
            panel.putProtocolForTest(a.getId(), new FakeProtocol(false));
            panel.selectConversationForTest(peer);
            assertFalse(panel.sendFileButton().isEnabled());

            // A connected backend WITH file transfer enables Send File for a peer...
            FakeProtocol ft = new FakeProtocol(true);
            panel.putProtocolForTest(a.getId(), ft);
            panel.selectConversationForTest(peer);
            assertTrue(panel.sendFileButton().isEnabled());
            // ...but never for the account console.
            panel.selectConversationForTest(console);
            assertFalse(panel.sendFileButton().isEnabled());

            // A disconnected backend disables it again.
            ft.connected = false;
            panel.selectConversationForTest(peer);
            assertFalse(panel.sendFileButton().isEnabled());

            // Offering a file routes through the backend to the current peer.
            ft.connected = true;
            panel.selectConversationForTest(peer);
            Path payload = dir.resolve("hello.txt");
            Files.writeString(payload, "hi");
            assertTrue(panel.offerFileForTest(payload));
            assertEquals("bob", ft.lastSendTarget);
            assertEquals(payload, ft.lastSendFile);
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("a file event renders in the conversation and tracks the transfer")
    void handleFileEventTracksAndRenders(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        try {
            AccountConfig a = new AccountConfig("P2P", "p2p", "", "me");
            panel.addAccountForTest(a);
            FakeProtocol ft = new FakeProtocol(true);
            panel.putProtocolForTest(a.getId(), ft);
            panel.selectConversationForTest(
                    new MessengerPanel.Conversation(a.getId(), "bob", "Bob"));

            FileTransferEvent offer = new FileTransferEvent("t1", "bob", "report.bin",
                    2048, 0, FileTransferEvent.Direction.RECEIVE,
                    FileTransferEvent.State.OFFERED, null, null, false);
            panel.handleFileEventForTest(a, offer);

            // The offer renders, is tracked, and is auto-declined in the headless JVM.
            assertTrue(panel.transcriptText().contains("report.bin"), panel.transcriptText());
            assertTrue(panel.hasActiveTransfer("t1"));
            assertEquals("t1", ft.lastRejectId);

            // A terminal event clears the tracking and renders the verified outcome.
            FileTransferEvent done = new FileTransferEvent("t1", "bob", "report.bin",
                    2048, 2048, FileTransferEvent.Direction.RECEIVE,
                    FileTransferEvent.State.COMPLETED, "/tmp/report.bin", null, true);
            panel.handleFileEventForTest(a, done);
            assertFalse(panel.hasActiveTransfer("t1"));
            assertTrue(panel.transcriptText().contains("verified"), panel.transcriptText());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("an incoming chat line from a peer raises a desktop toast")
    void incomingChatToasts(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        List<String> titles = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        try {
            panel.setNotifierForTest((title, message, kind) -> {
                titles.add(title);
                bodies.add(message);
            });
            AccountConfig a = new AccountConfig("P2P", "p2p", "", "me");
            panel.addAccountForTest(a);

            panel.ingestForTest(a, new ChatMessage("bob", "bob",
                    "are you there?", ChatMessage.Kind.PRIVMSG));

            assertEquals(1, titles.size());
            assertTrue(titles.get(0).contains("bob"), titles.get(0));
            assertEquals("bob: are you there?", bodies.get(0));
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("self-echoes, presence traffic and the off setting never toast")
    void toastGatesAreRespected(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        List<String> titles = new ArrayList<>();
        try {
            panel.setNotifierForTest((title, message, kind) -> titles.add(title));
            AccountConfig a = new AccountConfig("P2P", "p2p", "", "me");
            panel.addAccountForTest(a);

            // Our own outgoing line echoed back by the backend.
            panel.ingestForTest(a, new ChatMessage("me", "bob",
                    "I wrote this", ChatMessage.Kind.PRIVMSG));
            assertTrue(titles.isEmpty(), "self-echo never toasts");

            // Presence traffic is not a chat line.
            panel.ingestForTest(a, new ChatMessage("bob", "bob",
                    "joined", ChatMessage.Kind.JOIN));
            assertTrue(titles.isEmpty(), "presence never toasts");

            // Long bodies are ellipsized in the preview.
            StringBuilder longText = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                longText.append("0123456789");
            }
            panel.ingestForTest(a, new ChatMessage("bob", "bob",
                    longText.toString(), ChatMessage.Kind.PRIVMSG));
            assertEquals(1, titles.size(), "the long message still toasts");

            // The user's switch wins over everything.
            titles.clear();
            panel.settings().setNotifyOnMessage(false);
            panel.ingestForTest(a, new ChatMessage("bob", "bob",
                    "quiet", ChatMessage.Kind.PRIVMSG));
            assertTrue(titles.isEmpty(), "notify-off is respected");
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("an inbound file offer raises a desktop toast beside the prompt")
    void fileOfferToasts(@TempDir Path dir) {
        MessengerPanel panel = new MessengerPanel(new MessengerStore(dir));
        List<String> titles = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        try {
            panel.setNotifierForTest((title, message, kind) -> {
                titles.add(title);
                bodies.add(message);
            });
            AccountConfig a = new AccountConfig("P2P", "p2p", "", "me");
            panel.addAccountForTest(a);
            panel.putProtocolForTest(a.getId(), new FakeProtocol(true));

            FileTransferEvent offer = new FileTransferEvent("t9", "bob", "slides.pdf",
                    4096, 0, FileTransferEvent.Direction.RECEIVE,
                    FileTransferEvent.State.OFFERED, null, null, false);
            panel.handleFileEventForTest(a, offer);

            assertEquals(1, titles.size());
            assertEquals("Messenger \u2014 Incoming file", titles.get(0));
            assertTrue(bodies.get(0).contains("slides.pdf"), bodies.get(0));
            assertTrue(bodies.get(0).contains("4.0 KiB"), bodies.get(0));
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("the file-event description and size helpers are pure and null-safe")
    void describeHelpers() {
        assertNull(MessengerPanel.describeFileEvent(null));
        assertEquals("", MessengerPanel.describeDiscovered(null));

        FileTransferEvent progress = new FileTransferEvent("t", "bob", "f.bin",
                100, 50, FileTransferEvent.Direction.SEND,
                FileTransferEvent.State.IN_PROGRESS, null, null, false);
        assertNull(MessengerPanel.describeFileEvent(progress), "progress renders no line");

        FileTransferEvent offer = new FileTransferEvent("t", "bob", "f.bin",
                100, 0, FileTransferEvent.Direction.RECEIVE,
                FileTransferEvent.State.OFFERED, null, null, false);
        assertTrue(MessengerPanel.describeFileEvent(offer).contains("offers"));

        FileTransferEvent failed = new FileTransferEvent("t", "bob", "f.bin",
                100, 0, FileTransferEvent.Direction.SEND,
                FileTransferEvent.State.FAILED, null, "boom", false);
        assertTrue(MessengerPanel.describeFileEvent(failed).contains("boom"));

        assertEquals("512 B", MessengerPanel.humanSize(512));
        assertEquals("1.0 KiB", MessengerPanel.humanSize(1024));
        assertEquals("2.0 KiB", MessengerPanel.humanSize(2048));
        assertEquals("5.0 MiB", MessengerPanel.humanSize(5L * 1024 * 1024));
        assertEquals("1.0 GiB", MessengerPanel.humanSize(1024L * 1024 * 1024));
    }

    /** A minimal, connected backend that records its file-transfer calls. */
    private static final class FakeProtocol implements MessengerProtocol {
        boolean connected = true;
        private final boolean fileTransfer;
        String lastSendTarget;
        Path lastSendFile;
        String lastAcceptId;
        String lastRejectId;
        String lastCancelId;

        FakeProtocol(boolean fileTransfer) {
            this.fileTransfer = fileTransfer;
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public String description() { return "test backend"; }
        @Override public boolean isNative() { return true; }
        @Override public Set<MessengerProtocol.Capability> capabilities() {
            return fileTransfer
                    ? Set.of(MessengerProtocol.Capability.CHAT,
                            MessengerProtocol.Capability.FILE_TRANSFER)
                    : Set.of(MessengerProtocol.Capability.CHAT);
        }
        @Override public void connect(AccountConfig account, ProtocolListener listener) { }
        @Override public void disconnect() { connected = false; }
        @Override public boolean isConnected() { return connected; }
        @Override public void sendMessage(String target, String text) { }
        @Override public boolean sendFile(String target, Path file) {
            lastSendTarget = target;
            lastSendFile = file;
            return true;
        }
        @Override public void acceptFile(String transferId) { lastAcceptId = transferId; }
        @Override public void rejectFile(String transferId, String reason) {
            lastRejectId = transferId;
        }
        @Override public void cancelFile(String transferId, String reason) {
            lastCancelId = transferId;
        }
    }
}
