/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jdesktop.lg3d.displayserver.desktop2d.Notification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link NewMailNotifier}, the new-mail state machine shared
 * by the 2D panel and the 3D Frame3D: baseline on the first listing, one toast
 * per arrival batch, unread-only and inbox-only gating, the settings switch,
 * the id-less fallback key, and reset. The notifier seam captures the posts,
 * so no desktop shell is needed.
 */
class NewMailNotifierTest {

    private final List<String> titles = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private NewMailNotifier notifier;

    @BeforeEach
    void setup() {
        notifier = new NewMailNotifier((title, message, kind) -> {
            titles.add(title);
            bodies.add(message);
        });
    }

    private static MailMessage message(String id, String from, String subject,
            boolean read) {
        MailMessage m = new MailMessage();
        m.setId(id);
        m.setAccountId("a1");
        m.setFolder(MailMessage.FOLDER_INBOX);
        m.setFrom(MailAddress.parse(from));
        m.setTo(Collections.singletonList(MailAddress.of("me@example.com")));
        m.setSubject(subject);
        m.setRead(read);
        return m;
    }

    private List<MailMessage> inbox(MailMessage... msgs) {
        return Arrays.asList(msgs);
    }

    @Test
    void firstListingOnlyTakesTheBaseline() {
        List<MailMessage> arrived = notifier.onFolderLoaded("a1",
                MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);

        assertTrue(arrived.isEmpty(), "the baseline is not an arrival");
        assertTrue(titles.isEmpty(), "no toast on the first listing");
    }

    @Test
    void unseenUnreadMailRaisesOneToastNamingTheNewest() {
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);

        List<MailMessage> arrived = notifier.onFolderLoaded("a1",
                MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", true),
                        message("2", "Bob <b@x.com>", "Hello", false),
                        message("3", "Carol <c@x.com>", "Party", false)),
                true);

        assertEquals(2, arrived.size());
        assertEquals(1, titles.size(), "one toast per batch");
        assertEquals(NewMailNotifier.TITLE, titles.get(0));
        assertTrue(bodies.get(0).startsWith("2 new message(s)"), bodies.get(0));
        assertTrue(bodies.get(0).contains("Party"), "names the newest arrival");
    }

    @Test
    void sameListingTwiceDoesNotNotify() {
        List<MailMessage> first = inbox(
                message("1", "Alice <a@x.com>", "Old", false));
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX, first, true);
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX, first, true);

        assertTrue(titles.isEmpty(), "a repeat of the baseline is not an arrival");
    }

    @Test
    void alreadyReadMailNeverNotifies() {
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);

        // Arrived unseen but already read (another client opened it).
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("9", "Zed <z@x.com>", "Seen elsewhere", true)),
                true);

        assertTrue(titles.isEmpty());
    }

    @Test
    void nonInboxFoldersRecordButNeverNotify() {
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);

        notifier.onFolderLoaded("a1", MailMessage.FOLDER_SENT,
                inbox(message("7", "me <me@x.com>", "Sent now", false)), true);
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_SENT,
                inbox(message("8", "me <me@x.com>", "Sent later", false)), true);

        assertTrue(titles.isEmpty(), "Sent never toasts");
    }

    @Test
    void singleArrivalShowsSenderAndSubject() {
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", true),
                        message("2", "Bob <bob@x.com>", "Lunch?", false)),
                true);

        assertEquals(1, titles.size());
        assertEquals("Bob <bob@x.com>: Lunch?", bodies.get(0));
    }

    @Test
    void disabledSettingSuppressesTheToastButStillDetects() {
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);

        List<MailMessage> arrived = notifier.onFolderLoaded("a1",
                MailMessage.FOLDER_INBOX,
                inbox(message("2", "Bob <b@x.com>", "Hi", false)), false);

        assertEquals(1, arrived.size(), "detection keeps running");
        assertTrue(titles.isEmpty(), "the user's switch wins");
    }

    @Test
    void idLessMessagesFallBackToSenderSubjectDateKey() {
        MailMessage noId = message("", "Alice <a@x.com>", "Old", false);
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(noId), true);
        // The same envelope relisted (same sender/subject/date) is not new...
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message(noId)), true);
        assertTrue(titles.isEmpty(), "fallback key is stable across reloads");

        // ...but a different one is.
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message(noId),
                        message("", "Bob <b@x.com>", "New", false)), true);
        assertEquals(1, titles.size());
    }

    private static MailMessage message(MailMessage copy) {
        MailMessage m = new MailMessage();
        m.setId(copy.getId());
        m.setAccountId(copy.getAccountId());
        m.setFolder(copy.getFolder());
        m.setFrom(copy.getFrom());
        m.setSubject(copy.getSubject());
        m.setSentDate(copy.getSentDate());
        m.setRead(copy.isRead());
        return m;
    }

    @Test
    void resetClearsTheBaseline() {
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);
        notifier.reset();
        notifier.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);

        assertTrue(titles.isEmpty(), "after reset the next listing is a baseline");
    }

    @Test
    void nullNotifierFallsBackToTheDesktopFacadeWithoutThrowing() {
        // No 2D shell in the test JVM: the default route lands on the 3D HUD
        // service singleton, which must accept the post headlessly.
        NewMailNotifier withDefault = new NewMailNotifier(null);
        withDefault.onFolderLoaded("a1", MailMessage.FOLDER_INBOX,
                inbox(message("1", "Alice <a@x.com>", "Old", false)), true);
        List<MailMessage> arrived = withDefault.onFolderLoaded("a1",
                MailMessage.FOLDER_INBOX,
                inbox(message("2", "Bob <b@x.com>", "Ping", false)), true);

        assertEquals(1, arrived.size(), "the machine still runs with the default");
    }
}
