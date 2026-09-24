/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link MailPanel}, the 2D/Swing counterpart of Mail3D. They
 * drive the panel's actions and assert on the underlying {@link MailStore} /
 * {@link MailMessage} model. The mailbox lives in the user Preferences tree, so
 * each test clears {@code /mail} first (giving a deterministic four-message seed)
 * and again afterwards.
 */
class MailPanelTest {

    @BeforeEach
    void clearMailbox() {
        remove("/mail");
        remove("/contacts");
    }

    @AfterEach
    void cleanup() {
        remove("/mail");
    }

    private static void remove(String path) {
        try {
            if (Preferences.userRoot().nodeExists(path)) {
                Preferences.userRoot().node(path).removeNode();
            }
        } catch (Exception e) {
            // best effort
        }
    }

    @Test
    void seedsTheInboxOnFirstRun() {
        MailPanel panel = new MailPanel();
        assertEquals(4, panel.getMessageCount());
        assertEquals(MailMessage.FOLDER_INBOX, panel.getFolder());
        assertEquals(4, panel.currentFolderMessages().size());
    }

    @Test
    void openingAMessageMarksItRead() {
        MailPanel panel = new MailPanel();
        MailMessage first = panel.currentFolderMessages().get(0);
        assertFalse(first.isRead());
        panel.openMessage(first);
        assertTrue(first.isRead());
        assertEquals(first, panel.getSelected());
    }

    @Test
    void newMessageThenSendFilesItInSent() {
        MailPanel panel = new MailPanel();
        int before = panel.getMessageCount();
        panel.newMessage();
        assertNotNull(panel.getDraft());
        panel.send();
        assertNull(panel.getDraft());
        assertEquals(before + 1, panel.getMessageCount());
        assertEquals(MailMessage.FOLDER_SENT, panel.getFolder());
        // The sent message survived to the store.
        assertEquals(before + 1, new MailStore().load().size());
    }

    @Test
    void replyPrefillsSubjectAndQuote() {
        MailPanel panel = new MailPanel();
        MailMessage first = panel.currentFolderMessages().get(0);
        panel.openMessage(first);
        panel.reply();
        MailMessage draft = panel.getDraft();
        assertNotNull(draft);
        assertTrue(draft.getSubject().startsWith("Re: "));
        assertTrue(draft.getBody().contains(">"));
    }

    @Test
    void discardThrowsAwayTheDraft() {
        MailPanel panel = new MailPanel();
        panel.newMessage();
        assertNotNull(panel.getDraft());
        panel.discard();
        assertNull(panel.getDraft());
    }

    @Test
    void toggleReadFlipsTheFlag() {
        MailPanel panel = new MailPanel();
        MailMessage first = panel.currentFolderMessages().get(0);
        panel.openMessage(first);
        assertTrue(first.isRead());
        panel.toggleRead();
        assertFalse(first.isRead());
    }

    @Test
    void deleteRemovesFromModelAndStore() {
        MailPanel panel = new MailPanel();
        MailMessage first = panel.currentFolderMessages().get(0);
        panel.openMessage(first);
        int before = panel.getMessageCount();
        panel.deleteSelected();
        assertNull(panel.getSelected());
        assertEquals(before - 1, panel.getMessageCount());
        assertEquals(before - 1, new MailStore().load().size());
    }

    @Test
    void switchingFoldersIsIgnoredWhileComposing() {
        MailPanel panel = new MailPanel();
        panel.newMessage();
        panel.showFolder(MailMessage.FOLDER_SENT);
        // Still composing: the folder did not change under the draft.
        assertNotNull(panel.getDraft());
        assertEquals(MailMessage.FOLDER_INBOX, panel.getFolder());
    }
}
