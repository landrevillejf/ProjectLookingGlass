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

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;
import org.jdesktop.lg3d.contacts.ContactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the rewritten {@link MailPanel}. The panel is driven against a
 * {@link FakeMailService} through a {@link MailSessionManager} in
 * {@link MailPanel#setSynchronous(boolean) synchronous} mode, so every backend call
 * runs inline on the test thread and the assertions are deterministic. Together
 * they cover the folder/message load, selection -> reading, reply/compose -> send,
 * server-side search, triage (read/delete), the no-account empty state, and the
 * live application of {@link MailSettings}.
 *
 * <p>Accounts live in the user {@code /mail} Preferences tree, cleared before and
 * after each test; the recipient address book (the shared
 * {@code org.jdesktop.lg3d.contacts.ContactStore}) is pointed at a temp dir so the
 * compose contact picker stays empty and deterministic.</p>
 */
class MailPanelTest {

    @TempDir
    Path contactsDir;

    private MailAccountStore accountStore;
    private FakeMailService fake;
    private MailSessionManager manager;
    private MailAccount account;

    @BeforeEach
    void setup() {
        remove("/mail");
        System.setProperty(ContactStore.DIR_PROPERTY, contactsDir.toString());

        accountStore = new MailAccountStore();
        fake = new FakeMailService();
        manager = new MailSessionManager(accountStore, new MailRuleStore(),
                a -> fake);

        account = new MailAccount("a1");
        account.setDisplayName("Test User");
        account.setEmailAddress("me@example.com");
        account.setImapHost("imap.example.com");
        account.setSmtpHost("smtp.example.com");
        account.setCredentialMode(MailAccount.CredentialMode.SAVED);
        accountStore.save(account);
        accountStore.setPassword("a1", "pw");

        fake.seed(MailMessage.FOLDER_INBOX,
                message("1", "Alice <alice@example.com>", "First message"),
                message("2", "Bob <bob@example.com>", "Second message"));
    }

    @AfterEach
    void cleanup() {
        manager.close();
        remove("/mail");
        System.clearProperty(ContactStore.DIR_PROPERTY);
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

    private static MailMessage message(String id, String from, String subject) {
        MailMessage m = new MailMessage();
        m.setId(id);
        m.setAccountId("a1");
        m.setFolder(MailMessage.FOLDER_INBOX);
        m.setFrom(MailAddress.parse(from));
        m.setTo(Collections.singletonList(MailAddress.of("me@example.com")));
        m.setSubject(subject);
        m.setTextBody("Body of " + subject);
        return m;
    }

    private MailPanel newPanel() {
        MailPanel panel = new MailPanel(manager, MailSettings.load());
        panel.setSynchronous(true);
        manager.setPasswordPrompt(a -> "pw");
        panel.refresh();
        return panel;
    }

    @Test
    void loadsAccountsFoldersAndMessages() {
        MailPanel panel = newPanel();
        assertEquals(1, panel.getAccounts().size());
        assertEquals(MailMessage.FOLDER_INBOX, panel.getFolder());
        assertEquals(2, panel.getMessages().size());
        boolean hasInbox = false;
        for (MailFolder f : panel.getFolders()) {
            if (f.getName().equalsIgnoreCase("INBOX")) {
                hasInbox = true;
            }
        }
        assertTrue(hasInbox);
    }

    @Test
    void openMessageFetchesBodyAndMarksRead() {
        MailPanel panel = newPanel();
        MailMessage first = panel.getMessages().get(0);
        assertFalse(first.isRead());
        panel.openMessage(first);
        MailMessage selected = panel.getSelected();
        assertNotNull(selected);
        assertTrue(selected.isRead());
        assertTrue(selected.isBodyLoaded());
        assertEquals("Body of First message", selected.getTextBody());
    }

    @Test
    void replyPrefillsSubjectAndRecipient() {
        MailPanel panel = newPanel();
        panel.openMessage(panel.getMessages().get(0));
        ComposePanel cp = panel.composeFor(account, panel.getSelected(),
                MailPanel.ComposeMode.REPLY);
        assertTrue(cp.getSubjectText().startsWith("Re:"), cp.getSubjectText());
        assertTrue(cp.getToText().contains("alice@example.com"), cp.getToText());
    }

    @Test
    void composeAndSendGoesThroughTheBackend() {
        MailPanel panel = newPanel();
        ComposePanel cp = panel.composeFor(account, null,
                MailPanel.ComposeMode.NEW);
        cp.setToText("dest@example.com");
        cp.setSubjectText("Hello there");
        cp.setBodyText("Some body text.");
        MailMessage draft = cp.buildDraft();
        draft.setAccountId("a1");
        panel.sendDraft(draft, cp.attachments());

        assertEquals(1, fake.sent.size());
        assertEquals("Hello there", fake.sent.get(0).getSubject());
        assertEquals(MailMessage.FOLDER_SENT, panel.getFolder());
    }

    @Test
    void searchFiltersTheList() {
        MailPanel panel = newPanel();
        panel.performSearch("Second");
        assertEquals(1, panel.getMessages().size());
        assertEquals("Second message", panel.getMessages().get(0).getSubject());
    }

    @Test
    void toggleReadFlipsTheFlag() {
        MailPanel panel = newPanel();
        panel.openMessage(panel.getMessages().get(0));
        assertTrue(panel.getSelected().isRead());
        panel.toggleRead();
        assertFalse(panel.getSelected().isRead());
    }

    @Test
    void deleteRemovesFromTheList() {
        MailPanel panel = newPanel();
        panel.openMessage(panel.getMessages().get(0));
        int before = panel.getMessages().size();
        panel.deleteSelected();
        assertEquals(before - 1, panel.getMessages().size());
        assertNull(panel.getSelected());
    }

    @Test
    void noAccountShowsTheEmptyState() {
        remove("/mail");
        MailSessionManager empty = new MailSessionManager(
                new MailAccountStore(), new MailRuleStore(),
                a -> new FakeMailService());
        MailPanel panel = new MailPanel(empty, MailSettings.load());
        panel.setSynchronous(true);
        panel.refresh();
        assertTrue(panel.getAccounts().isEmpty());
        assertTrue(panel.getMessages().isEmpty());
        assertTrue(panel.getStatusText().contains("No account"),
                panel.getStatusText());
        empty.close();
    }

    @Test
    void settingsChangesApplyToTheList() {
        MailPanel panel = newPanel();
        MailSettings s = panel.getSettings();
        s.setDensity(MailSettings.Density.COMPACT);
        panel.applySettings();
        assertEquals(22, panel.getTableRowHeight());
        s.setDensity(MailSettings.Density.COMFORTABLE);
        panel.applySettings();
        assertEquals(34, panel.getTableRowHeight());
    }

    @Test
    void selectingAFolderReloadsItsMessages() {
        MailPanel panel = newPanel();
        fake.seed("Archive", message("9", "a@b", "Archived"));
        panel.selectFolder("a1", "Archive");
        assertEquals("Archive", panel.getFolder());
        List<MailMessage> msgs = panel.getMessages();
        assertEquals(1, msgs.size());
        assertEquals("Archived", msgs.get(0).getSubject());
    }
}
