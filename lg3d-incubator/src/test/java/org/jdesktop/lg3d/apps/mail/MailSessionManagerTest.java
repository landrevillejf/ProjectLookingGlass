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
package org.jdesktop.lg3d.apps.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link MailSessionManager} against a {@link FakeMailService}:
 * session caching / reconnect, credential routing (SAVED vs ASK), the connection
 * probe, and rule application on fetch (mark-read, move, delete).
 */
class MailSessionManagerTest {

    private MailAccountStore accountStore;
    private MailRuleStore ruleStore;
    private FakeMailService fake;
    private MailSessionManager manager;
    private MailAccount saved;

    @BeforeEach
    void setup() {
        remove("/mail");
        accountStore = new MailAccountStore();
        ruleStore = new MailRuleStore();
        fake = new FakeMailService();
        manager = new MailSessionManager(accountStore, ruleStore, a -> fake);

        saved = new MailAccount("a1");
        saved.setEmailAddress("a1@example.com");
        saved.setImapHost("imap.example.com");
        saved.setSmtpHost("smtp.example.com");
        saved.setCredentialMode(MailAccount.CredentialMode.SAVED);
        accountStore.save(saved);
        accountStore.setPassword("a1", "pw");
    }

    @AfterEach
    void cleanup() {
        manager.close();
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

    private static MailMessage msg(String id, String from, String subject) {
        MailMessage m = new MailMessage();
        m.setId(id);
        m.setAccountId("a1");
        m.setFolder(MailMessage.FOLDER_INBOX);
        m.setFrom(MailAddress.parse(from));
        m.setSubject(subject);
        m.setTextBody("body of " + subject);
        return m;
    }

    @Test
    void sessionConnectsOnceAndCaches() throws Exception {
        manager.session("a1");
        assertTrue(fake.connected);
        assertEquals(1, fake.connectCount);
        manager.session("a1");
        assertEquals(1, fake.connectCount, "a live session must be reused");
    }

    @Test
    void sessionReconnectsAfterADrop() throws Exception {
        manager.session("a1");
        fake.disconnect();
        manager.session("a1");
        assertEquals(2, fake.connectCount);
    }

    @Test
    void resolvePasswordUsesSavedCredential() {
        assertEquals("pw", manager.resolvePassword(saved));
    }

    @Test
    void resolvePasswordPromptsForAskAccounts() {
        MailAccount ask = new MailAccount("ask1");
        ask.setEmailAddress("ask@example.com");
        ask.setCredentialMode(MailAccount.CredentialMode.ASK);
        manager.setPasswordPrompt(a -> "typed-in");
        assertEquals("typed-in", manager.resolvePassword(ask));
    }

    @Test
    void unknownAccountThrows() {
        assertThrows(MailBackendException.class, () -> manager.session("nope"));
    }

    @Test
    void askAccountWithNoPromptCannotConnect() {
        MailAccount ask = new MailAccount("ask2");
        ask.setEmailAddress("ask2@example.com");
        ask.setCredentialMode(MailAccount.CredentialMode.ASK);
        accountStore.save(ask);
        // No prompt installed and nothing saved: resolution fails.
        assertThrows(MailBackendException.class, () -> manager.session("ask2"));
    }

    @Test
    void testConnectionReportsFolderCount() throws Exception {
        fake.seed(MailMessage.FOLDER_INBOX, msg("1", "a@b", "one"));
        fake.seed("Archive", msg("2", "a@b", "two"));
        int folders = manager.testConnection(saved, "pw");
        assertEquals(2, folders);
    }

    @Test
    void fetchReturnsEnvelopeList() throws Exception {
        fake.seed(MailMessage.FOLDER_INBOX,
                msg("1", "alice@example.com", "one"),
                msg("2", "bob@example.com", "two"));
        List<MailMessage> list = manager.fetch("a1", MailMessage.FOLDER_INBOX);
        assertEquals(2, list.size());
    }

    @Test
    void fetchAppliesMarkReadRule() throws Exception {
        MailRule r = new MailRule(null);
        r.setField(MailRule.Field.FROM);
        r.setMatch(MailRule.Match.CONTAINS);
        r.setValue("alice");
        r.setAction(MailRule.Action.MARK_READ);
        ruleStore.saveAll(Collections.singletonList(r));

        fake.seed(MailMessage.FOLDER_INBOX,
                msg("1", "alice@example.com", "one"),
                msg("2", "bob@example.com", "two"));

        List<MailMessage> list = manager.fetch("a1", MailMessage.FOLDER_INBOX);
        assertEquals(2, list.size(), "mark-read keeps the message in the folder");
        for (MailMessage m : list) {
            if (m.getId().equals("1")) {
                assertTrue(m.isRead());
            } else {
                assertFalse(m.isRead());
            }
        }
    }

    @Test
    void fetchAppliesMoveRule() throws Exception {
        MailRule r = new MailRule(null);
        r.setField(MailRule.Field.SUBJECT);
        r.setMatch(MailRule.Match.CONTAINS);
        r.setValue("spam");
        r.setAction(MailRule.Action.MOVE);
        r.setTargetFolder("Junk");
        ruleStore.saveAll(Collections.singletonList(r));

        fake.seed(MailMessage.FOLDER_INBOX,
                msg("1", "a@b", "hello"),
                msg("2", "a@b", "cheap spam offer"));

        List<MailMessage> list = manager.fetch("a1", MailMessage.FOLDER_INBOX);
        assertEquals(1, list.size(), "the moved message leaves the listing");
        assertEquals("hello", list.get(0).getSubject());
        assertEquals(1, fake.folders.get("Junk").size());
    }

    @Test
    void fetchAppliesDeleteRule() throws Exception {
        MailRule r = new MailRule(null);
        r.setField(MailRule.Field.SUBJECT);
        r.setMatch(MailRule.Match.EQUALS);
        r.setValue("drop");
        r.setAction(MailRule.Action.DELETE);
        ruleStore.saveAll(Arrays.asList(r));

        fake.seed(MailMessage.FOLDER_INBOX,
                msg("1", "a@b", "keep"),
                msg("2", "a@b", "drop"));

        List<MailMessage> list = manager.fetch("a1", MailMessage.FOLDER_INBOX);
        assertEquals(1, list.size());
        assertEquals("keep", list.get(0).getSubject());
        assertEquals(1, fake.folders.get(MailMessage.FOLDER_INBOX).size());
    }

    @Test
    void closeDisconnectsSessions() throws Exception {
        manager.session("a1");
        assertTrue(fake.connected);
        manager.close();
        assertFalse(fake.connected);
    }
}
