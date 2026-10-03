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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-protocol evidence for {@link ImapSmtpMailService}: it boots an in-JVM
 * GreenMail IMAP + SMTP server, delivers fixtures, then drives the full round trip
 * the plan calls for - connect, list folders, list envelopes, open a body,
 * search, set flags, move, delete, and send a message with an attachment.
 *
 * <p>The account uses {@link MailAccount.Security#NONE} so the plaintext GreenMail
 * listeners accept it; every other code path is the same one production uses over
 * SSL/STARTTLS. Ports are chosen dynamically so parallel test classes do not
 * clash.</p>
 */
class ImapSmtpMailServiceTest {

    private static final String EMAIL = "user@localhost";
    private static final String LOGIN = "user";
    private static final String PASSWORD = "pass";

    private GreenMail greenMail;
    private ServerSetup imapSetup;
    private ServerSetup smtpSetup;
    private MailAccount account;
    private ImapSmtpMailService service;

    @BeforeEach
    void startServer() {
        imapSetup = new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_IMAP);
        smtpSetup = new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        greenMail = new GreenMail(new ServerSetup[] { imapSetup, smtpSetup });
        greenMail.start();
        greenMail.setUser(EMAIL, LOGIN, PASSWORD);

        account = new MailAccount("greenmail-test");
        account.setEmailAddress(EMAIL);
        account.setDisplayName("Test User");
        account.setUsername(LOGIN);
        account.setImapHost("127.0.0.1");
        account.setImapPort(imapSetup.getPort());
        account.setImapSecurity(MailAccount.Security.NONE);
        account.setSmtpHost("127.0.0.1");
        account.setSmtpPort(smtpSetup.getPort());
        account.setSmtpSecurity(MailAccount.Security.NONE);

        service = new ImapSmtpMailService();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.disconnect();
        }
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port", e);
        }
    }

    private void deliverTwo() {
        GreenMailUtil.sendTextEmail(EMAIL, "alice@example.com", "First message",
                "Hello from Alice.", smtpSetup);
        GreenMailUtil.sendTextEmail(EMAIL, "bob@example.com", "Second message",
                "Hello from Bob.", smtpSetup);
        assertTrue(greenMail.waitForIncomingEmail(5000, 2),
                "fixtures did not arrive");
    }

    @Test
    void connectsAndListsFolders() throws Exception {
        deliverTwo();
        service.connect(account, PASSWORD);
        assertTrue(service.isConnected());
        assertEquals(account, service.getAccount());

        List<MailFolder> folders = service.listFolders();
        assertFalse(folders.isEmpty());
        boolean hasInbox = false;
        for (MailFolder f : folders) {
            if (f.getName().equalsIgnoreCase("INBOX")) {
                hasInbox = true;
            }
        }
        assertTrue(hasInbox, "no INBOX among " + folders);
    }

    @Test
    void listsEnvelopesAndOpensBody() throws Exception {
        deliverTwo();
        service.connect(account, PASSWORD);

        List<MailMessage> list = service.list("INBOX");
        assertEquals(2, list.size());
        // Envelopes are headers-only until opened.
        assertFalse(list.get(0).isBodyLoaded());

        MailMessage target = null;
        for (MailMessage m : list) {
            if ("First message".equals(m.getSubject())) {
                target = m;
            }
        }
        assertNotNull(target, "First message not listed");
        MailMessage full = service.open(target);
        assertTrue(full.isBodyLoaded());
        assertTrue(full.getTextBody().contains("Hello from Alice"),
                "body was: " + full.getTextBody());
        assertEquals("alice@example.com", full.getFrom().getEmail());
    }

    @Test
    void searchesBySubject() throws Exception {
        deliverTwo();
        service.connect(account, PASSWORD);

        List<MailMessage> found = service.search("INBOX", "Second");
        assertEquals(1, found.size());
        assertEquals("Second message", found.get(0).getSubject());
    }

    @Test
    void setsReadFlagOnServer() throws Exception {
        deliverTwo();
        service.connect(account, PASSWORD);

        MailMessage first = service.list("INBOX").get(0);
        assertFalse(first.isRead());
        service.setRead(first, true);

        // Re-list to confirm the flag persisted server-side.
        boolean sawRead = false;
        for (MailMessage m : service.list("INBOX")) {
            if (m.getId().equals(first.getId()) && m.isRead()) {
                sawRead = true;
            }
        }
        assertTrue(sawRead, "SEEN flag did not persist");
    }

    @Test
    void movesAndDeletesMessages() throws Exception {
        deliverTwo();
        service.connect(account, PASSWORD);

        List<MailMessage> list = service.list("INBOX");
        MailMessage victim = list.get(0);
        MailMessage mover = list.get(1);

        service.move(mover, "Archive");
        assertEquals(1, service.list("INBOX").size());
        assertEquals(1, service.list("Archive").size());

        service.delete(victim);
        assertEquals(0, service.list("INBOX").size());
    }

    @Test
    void sendsAMessageWithAnAttachment() throws Exception {
        service.connect(account, PASSWORD);

        MailMessage draft = new MailMessage();
        draft.setAccountId(account.getId());
        draft.setFrom(account.fromAddress());
        draft.setTo(Collections.singletonList(MailAddress.of(EMAIL)));
        draft.setSubject("Attach test");
        draft.setTextBody("See attached.");
        byte[] payload = "file-bytes".getBytes("UTF-8");
        MailAttachment att = MailAttachment.fromBytes("note.txt", "text/plain",
                payload);

        service.send(draft, Collections.singletonList(att));
        assertTrue(greenMail.waitForIncomingEmail(5000, 1), "sent mail not delivered");

        MailMessage sent = null;
        for (MailMessage m : service.list("INBOX")) {
            if ("Attach test".equals(m.getSubject())) {
                sent = m;
            }
        }
        assertNotNull(sent, "sent message not in INBOX");
        MailMessage full = service.open(sent);
        assertTrue(full.hasAttachments(), "attachment metadata missing");
        assertEquals("note.txt", full.getAttachments().get(0).getFileName());
        byte[] back = service.openAttachment(full, full.getAttachments().get(0));
        assertEquals("file-bytes", new String(back, "UTF-8"));
    }
}
