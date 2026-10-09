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
package org.jdesktop.lg3d.apps.videoconference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import javax.swing.JTabbedPane;
import org.jdesktop.lg3d.contacts.Contact;
import org.jdesktop.lg3d.contacts.ContactStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link VideoConferencePanel}: the Swing face of the Video
 * Conference app. They build the panel against a temp-directory store, check its
 * default lobby state, drive the {@code join()} flow (which, headless, records
 * the built URL and a {@code URL_SHOWN} outcome without ever opening a browser
 * or a modal), verify that the joined room persists so a fresh panel over the
 * same directory reloads it, and exercise the command-splitting and timestamp
 * helpers. No X display, browser or clipboard is touched.
 */
class VideoConferencePanelTest {

    @Test
    @DisplayName("the contacts tab is a live view of the shared address book")
    void contactsComeFromSharedAddressBook(@TempDir Path dir, @TempDir Path contactsDir) {
        ContactStore addressBook = new ContactStore(contactsDir);
        Contact ada = addressBook.add(new Contact("Ada", "Lovelace", "ada@example.org"));
        VideoConferencePanel panel =
                new VideoConferencePanel(new VideoConferenceStore(dir), addressBook);
        try {
            assertEquals(1, panel.contacts().size());
            assertEquals("Ada Lovelace", panel.contacts().get(0).displayName());
            assertEquals("ada@example.org", panel.contacts().get(0).primaryEmail());
            assertSame(addressBook, panel.addressBook());

            // A contact created in the Contacts app shows up without any
            // vc-local persistence, and a delete propagates the same way.
            addressBook.add(new Contact("Grace", "Hopper", "grace@example.org"));
            assertEquals(2, panel.contacts().size());
            addressBook.delete(ada.getId());
            assertEquals(1, panel.contacts().size());
            assertEquals("Grace Hopper", panel.contacts().get(0).displayName());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("the panel sizes itself to the desktop window and starts empty")
    void defaultState(@TempDir Path dir) {
        VideoConferencePanel panel = new VideoConferencePanel(new VideoConferenceStore(dir));
        try {
            assertEquals(VideoConferencePanel.WIDTH_PX, panel.getPreferredSize().width);
            assertEquals(VideoConferencePanel.HEIGHT_PX, panel.getPreferredSize().height);
            // A friendly room name is pre-filled so Join works out of the box.
            assertFalse(panel.roomField().getText().isEmpty());
            assertEquals(VideoConferenceSettings.DEFAULT_DOMAIN, panel.domainField().getText());
            // No camera backend ships, so the preview degrades to a placeholder.
            assertFalse(panel.preview().hasLiveFrame());
            assertTrue(panel.rooms().isEmpty());
            assertTrue(panel.history().isEmpty());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("join() builds a sanitized deep link and records it headless")
    void joinRecordsHistoryAndRoom(@TempDir Path dir) {
        VideoConferenceStore store = new VideoConferenceStore(dir);
        VideoConferencePanel panel = new VideoConferencePanel(store);
        try {
            panel.roomField().setText("My Test Room!");
            panel.domainField().setText("meet.jit.si");
            panel.join();

            String url = panel.lastJoinUrl();
            assertNotNull(url);
            assertTrue(url.startsWith("https://meet.jit.si/MyTestRoom#"),
                    "the room name must be sanitized into the URL: " + url);
            // Headless: no browser, so the outcome is the manual-copy fallback.
            assertEquals(CallHistoryEntry.Outcome.URL_SHOWN, panel.lastOutcome());
            assertEquals(1, panel.history().size());
            assertEquals("My Test Room!", panel.history().get(0).getRoomName());
            assertEquals(1, panel.rooms().size());
            assertEquals(1, panel.rooms().get(0).getJoinCount());
            assertEquals(1, panel.roomModel().getSize());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("a joined room persists and is reloaded by a fresh panel")
    void joinedRoomPersists(@TempDir Path dir) {
        VideoConferencePanel first =
                new VideoConferencePanel(new VideoConferenceStore(dir));
        try {
            first.roomField().setText("Standup");
            first.join();
        } finally {
            first.shutdown();
        }

        VideoConferencePanel second =
                new VideoConferencePanel(new VideoConferenceStore(dir));
        try {
            assertEquals(1, second.rooms().size());
            assertEquals("Standup", second.rooms().get(0).getName());
            assertEquals(1, second.history().size());
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("join() with an empty room name is a no-op")
    void joinEmptyRoom(@TempDir Path dir) {
        VideoConferencePanel panel = new VideoConferencePanel(new VideoConferenceStore(dir));
        try {
            panel.roomField().setText("   ");
            panel.join();
            assertTrue(panel.history().isEmpty());
            assertTrue(panel.rooms().isEmpty());
            assertFalse(panel.statusLbl().getText().isEmpty());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("splitCommand honours quoted arguments with spaces")
    void splitCommand() {
        assertEquals(List.of("jitsi", "https://x/y"),
                VideoConferencePanel.splitCommand("jitsi https://x/y"));
        assertEquals(List.of("/opt/my apps/jitsi", "--room", "a b"),
                VideoConferencePanel.splitCommand("\"/opt/my apps/jitsi\" --room \"a b\""));
        assertEquals(List.of("a", "b"), VideoConferencePanel.splitCommand("  a    b  "));
        assertTrue(VideoConferencePanel.splitCommand(null).isEmpty());
        assertTrue(VideoConferencePanel.splitCommand("   ").isEmpty());
    }

    @Test
    @DisplayName("formatTimestamp renders nothing for a non-positive epoch")
    void formatTimestamp() {
        assertEquals("", VideoConferencePanel.formatTimestamp(0));
        assertEquals("", VideoConferencePanel.formatTimestamp(-1));
        assertFalse(VideoConferencePanel.formatTimestamp(System.currentTimeMillis()).isEmpty());
    }

    @Test
    @DisplayName("the sidebar gains a Direct (P2P) tab and starts no socket headless")
    void p2pTabIsPresentAndInert(@TempDir Path dir) {
        VideoConferencePanel panel = new VideoConferencePanel(new VideoConferenceStore(dir));
        try {
            JTabbedPane tabs = panel.sidebarTabs();
            assertEquals(4, tabs.getTabCount());
            assertEquals("Direct (P2P)", tabs.getTitleAt(3));

            // A headless-constructed panel spawns no side-channel, socket or thread.
            assertNull(panel.p2p());
            assertTrue(panel.p2pPeerModel().isEmpty());

            // Starting is refused headless rather than binding a socket.
            panel.startP2p();
            assertNull(panel.p2p());
            assertTrue(panel.statusLbl().getText().toLowerCase().contains("headless"),
                    panel.statusLbl().getText());

            // Inviting/chatting without a running channel only sets a guiding status.
            panel.sendInviteToSelectedPeer();
            assertTrue(panel.statusLbl().getText().contains("Start the P2P"),
                    panel.statusLbl().getText());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("an invite URL is built from the room and applied on receipt")
    void inviteUrlBuildAndApply(@TempDir Path dir) {
        VideoConferencePanel panel = new VideoConferencePanel(new VideoConferenceStore(dir));
        try {
            panel.roomField().setText("Board Meeting");
            panel.domainField().setText("meet.jit.si");
            String url = panel.buildInviteUrl();
            assertNotNull(url);
            assertTrue(url.contains("meet.jit.si"), url);
            assertTrue(url.contains("BoardMeeting"), "the room is sanitized: " + url);

            // Receiving an invite fills the room/domain and runs the existing join flow.
            panel.applyInvite("Invited Room", "https://chat.example.org/InvitedRoom");
            assertEquals("Invited Room", panel.roomField().getText());
            assertEquals("chat.example.org", panel.domainField().getText());
            assertNotNull(panel.lastJoinUrl());
            assertTrue(panel.lastJoinUrl().startsWith("https://chat.example.org/"),
                    panel.lastJoinUrl());
        } finally {
            panel.shutdown();
        }
    }

    @Test
    @DisplayName("the P2P chat log and the pure label helpers are headless-safe")
    void p2pChatAndHelpers(@TempDir Path dir) {
        VideoConferencePanel panel = new VideoConferencePanel(new VideoConferenceStore(dir));
        try {
            panel.appendP2pChatForTest("alice: hello");
            assertTrue(panel.p2pChatText().contains("alice: hello"));

            panel.p2pChatInput().setText("ignored without a channel");
            panel.sendP2pChat(); // no channel: only a status line, never a throw
            assertTrue(panel.statusLbl().getText().contains("Start the P2P"),
                    panel.statusLbl().getText());
        } finally {
            panel.shutdown();
        }

        assertEquals("", VideoConferencePanel.shortFp(null));
        assertEquals("aabbccdd", VideoConferencePanel.shortFp("aa:bb:cc:dd:ee:ff:00:11"));
        assertEquals("", VideoConferencePanel.describeDiscovered(null));
    }
}
