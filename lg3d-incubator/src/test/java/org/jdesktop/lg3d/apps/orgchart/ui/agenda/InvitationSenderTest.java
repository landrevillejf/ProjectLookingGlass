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
package org.jdesktop.lg3d.apps.orgchart.ui.agenda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;
import org.jdesktop.lg3d.apps.mail.MailAccount;
import org.jdesktop.lg3d.apps.mail.MailAccountStore;
import org.jdesktop.lg3d.apps.mail.MailAttachment;
import org.jdesktop.lg3d.apps.mail.MailBackendException;
import org.jdesktop.lg3d.apps.mail.MailFolder;
import org.jdesktop.lg3d.apps.mail.MailMessage;
import org.jdesktop.lg3d.apps.mail.MailRuleStore;
import org.jdesktop.lg3d.apps.mail.MailService;
import org.jdesktop.lg3d.apps.mail.MailSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link InvitationSender}: it resolves the desktop's
 * default mail account, skips invitees without an address, hands a real
 * {@code METHOD:REQUEST} draft plus its {@code invite.ics} attachment to the
 * mail backend, and turns every failure mode into a user-safe
 * {@link MailBackendException}. The Jakarta Mail round trip is replaced by a
 * recording {@link MailService} injected through the manager's
 * {@code ServiceFactory} seam (the mail package's own fake is package-private).
 * Accounts live in the user Preferences tree, so {@code /mail} is cleared
 * before and after each test.
 */
class InvitationSenderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 18);

    private MailAccountStore accountStore;
    private RecordingMailService fake;
    private MailSessionManager manager;
    private InvitationSender sender;

    @BeforeEach
    void setup() {
        remove("/mail");
        accountStore = new MailAccountStore();
        fake = new RecordingMailService();
        manager = new MailSessionManager(accountStore, new MailRuleStore(),
                a -> fake);
        sender = new InvitationSender(manager);
        sender.setPasswordPrompt(a -> "secret");
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

    /** Saves a complete default account in ASK mode (the prompt answers it). */
    private MailAccount defaultAccount() {
        MailAccount account = new MailAccount("org");
        account.setDisplayName("Big Boss");
        account.setEmailAddress("boss@example.com");
        account.setImapHost("imap.example.com");
        account.setSmtpHost("smtp.example.com");
        account.setCredentialMode(MailAccount.CredentialMode.ASK);
        accountStore.save(account); // first account becomes the default
        return account;
    }

    private static ContactDirectory.ContactInfo contact(String uid, String name,
            String email) {
        return new ContactDirectory.ContactInfo(uid, name, email);
    }

    @Test
    void sendGoesOutOverTheDefaultAccountWithARequestAttachment()
            throws MailBackendException {
        defaultAccount();
        Appointment a = new Appointment("apt1", "Review", 2, 10, 2);

        int sent = sender.send(a, DATE, Arrays.asList(
                contact("u1", "Alice Example", "alice@example.org"),
                contact("u2", "No Mail", "")));

        assertEquals(1, sent, "the e-mail-less invitee is skipped");
        assertTrue(fake.connected);
        assertEquals("secret", fake.password,
                "the injected prompt answered the ASK-mode account");

        MailMessage draft = fake.sent.get(0);
        assertEquals("boss@example.com", draft.getFrom().getEmail());
        assertEquals(1, draft.getTo().size());
        assertEquals("alice@example.org", draft.getTo().get(0).getEmail());
        assertTrue(draft.getSubject().startsWith("Invitation: Review"));

        MailAttachment att = fake.sentAttachments.get(0).get(0);
        assertEquals("invite.ics", att.getFileName());
        assertEquals("text/calendar; method=REQUEST", att.getMimeType());
        String ics = new String(att.getData(), StandardCharsets.UTF_8);
        assertTrue(ics.contains("METHOD:REQUEST"));
        assertTrue(ics.contains("UID:apt1@lg3d"));
        assertTrue(ics.contains("mailto:alice@example.org"));
        assertFalse(ics.contains("No Mail"));
    }

    @Test
    void sendCountsEveryMailableInvitee() throws MailBackendException {
        defaultAccount();
        Appointment a = new Appointment("apt2", "Sync", 0, 9, 1);

        int sent = sender.send(a, DATE, Arrays.asList(
                contact("u1", "Alice", "alice@example.org"),
                contact("u2", "Bob", "bob@example.org")));

        assertEquals(2, sent);
        assertEquals(2, fake.sent.get(0).getTo().size());
    }

    @Test
    void noConfiguredAccountIsAUserSafeError() {
        Appointment a = new Appointment("apt3", "Review", 2, 10, 2);
        MailBackendException e = assertThrows(MailBackendException.class,
                () -> sender.send(a, DATE, Arrays.asList(
                        contact("u1", "Alice", "alice@example.org"))));
        assertTrue(e.getMessage().contains("No mail account is configured"));
        assertEquals(0, fake.sent.size());
    }

    @Test
    void anIncompleteDefaultAccountIsAUserSafeError() {
        MailAccount partial = new MailAccount("half");
        partial.setEmailAddress("half@example.com"); // no IMAP/SMTP hosts
        accountStore.save(partial);

        Appointment a = new Appointment("apt4", "Review", 2, 10, 2);
        MailBackendException e = assertThrows(MailBackendException.class,
                () -> sender.send(a, DATE, Arrays.asList(
                        contact("u1", "Alice", "alice@example.org"))));
        assertTrue(e.getMessage().contains("No mail account is configured"));
    }

    @Test
    void inviteesWithoutAnAddressAreAUserSafeError() {
        defaultAccount();
        Appointment a = new Appointment("apt5", "Review", 2, 10, 2);
        MailBackendException e = assertThrows(MailBackendException.class,
                () -> sender.send(a, DATE, Arrays.asList(
                        contact("u2", "No Mail", "  "), null)));
        assertTrue(e.getMessage().contains("e-mail address"));
        assertEquals(0, fake.sent.size(), "nothing reaches the backend");
    }

    @Test
    void backendFailuresPropagateAsMailBackendException() {
        defaultAccount();
        fake.failOnSend = true;
        Appointment a = new Appointment("apt6", "Review", 2, 10, 2);
        assertThrows(MailBackendException.class,
                () -> sender.send(a, DATE, Arrays.asList(
                        contact("u1", "Alice", "alice@example.org"))));
    }

    @Test
    void withEmailKeepsOnlyAddressableContacts() {
        List<ContactDirectory.ContactInfo> filtered =
                InvitationSender.withEmail(Arrays.asList(
                        contact("u1", "Alice", "alice@example.org"),
                        contact("u2", "Blank", ""),
                        contact("u3", "Null", null),
                        null));
        assertEquals(1, filtered.size());
        assertEquals("u1", filtered.get(0).uid);
        assertTrue(InvitationSender.withEmail(null).isEmpty());
    }

    /** Captures whatever {@link InvitationSender} pushes over the wire. */
    private static final class RecordingMailService implements MailService {

        final List<MailMessage> sent = new ArrayList<MailMessage>();
        final List<List<MailAttachment>> sentAttachments =
                new ArrayList<List<MailAttachment>>();
        boolean connected;
        boolean failOnSend;
        MailAccount account;
        String password;

        @Override
        public void connect(MailAccount account, String password) {
            this.account = account;
            this.password = password;
            this.connected = true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public MailAccount getAccount() {
            return account;
        }

        @Override
        public List<MailFolder> listFolders() {
            return new ArrayList<MailFolder>();
        }

        @Override
        public int unreadCount(String folderName) {
            return 0;
        }

        @Override
        public List<MailMessage> list(String folderName) {
            return new ArrayList<MailMessage>();
        }

        @Override
        public List<MailMessage> list(String folderName, int limit) {
            return new ArrayList<MailMessage>();
        }

        @Override
        public MailMessage open(MailMessage envelope) {
            return envelope;
        }

        @Override
        public byte[] openAttachment(MailMessage message, MailAttachment attachment) {
            return attachment.getData();
        }

        @Override
        public List<MailMessage> search(String folderName, String terms) {
            return new ArrayList<MailMessage>();
        }

        @Override
        public void setRead(MailMessage message, boolean read) {
            message.setRead(read);
        }

        @Override
        public void setFlagged(MailMessage message, boolean flagged) {
            message.setFlagged(flagged);
        }

        @Override
        public void move(MailMessage message, String toFolder) {
            message.setFolder(toFolder);
        }

        @Override
        public void delete(MailMessage message) {
            // nothing to delete in a recorder
        }

        @Override
        public void send(MailMessage draft, List<MailAttachment> attachments)
                throws MailBackendException {
            if (failOnSend) {
                throw new MailBackendException("SMTP relay refused");
            }
            sent.add(draft);
            sentAttachments.add(attachments);
        }

        @Override
        public void disconnect() {
            connected = false;
        }

        @Override
        public void close() {
            disconnect();
        }
    }
}
