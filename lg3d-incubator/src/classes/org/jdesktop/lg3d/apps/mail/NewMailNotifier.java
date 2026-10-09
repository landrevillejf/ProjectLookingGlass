/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.displayserver.desktop2d.Notification;
import org.jdesktop.lg3d.scenemanager.utils.hud.NotificationService;

/**
 * The new-mail detector shared by the 2D {@link MailPanel} and the native-3D
 * {@link Mail3D} surfaces, so "you got mail" behaves identically on both
 * desktops.
 *
 * <p>State machine: each folder listing records its message keys in a seen-set.
 * The very first listing only takes a baseline (what was already on the server
 * did not just arrive). After that, an inbox listing that brings still-unread
 * messages with unseen keys raises <em>one</em> notification naming the newest
 * arrival. Non-inbox folders (Sent, Drafts...) are recorded but never notify,
 * and read messages never notify, so opening mail through another client does
 * not toast after the fact.</p>
 *
 * <p>Pure state plus an injectable {@link Notifier} seam: no Swing, no Java 3D,
 * no desktop singleton in the detection path, so the headless tests drive it
 * directly. The panels' production notifier is
 * {@link NotificationService#notify(String, String, Notification.Kind)}, which
 * routes to whichever desktop shell is running.</p>
 */
final class NewMailNotifier {

    /** Raises the desktop notification; replaced by tests to capture posts. */
    interface Notifier {
        void notify(String title, String message, Notification.Kind kind);
    }

    /** The heading every new-mail toast shares. */
    static final String TITLE = "New Mail";

    private final Notifier notifier;

    private final Set<String> seenMessageKeys = new HashSet<>();
    /** False until a folder has been listed once: the baseline never notifies. */
    private boolean baselineTaken;

    NewMailNotifier(Notifier notifier) {
        this.notifier = (notifier == null)
                ? NotificationService::notify : notifier;
    }

    /**
     * Records a folder listing and raises the toast when new inbox mail arrived.
     * Call on the UI thread with the messages just fetched.
     *
     * @param enabled the user's {@link MailSettings#isNotifyOnNewMail()} switch
     * @return the messages detected as newly arrived (empty if none/not inbox)
     */
    List<MailMessage> onFolderLoaded(String accountId, String folder,
            List<MailMessage> loaded, boolean enabled) {
        boolean inbox = MailMessage.FOLDER_INBOX.equalsIgnoreCase(folder);
        Set<String> folderSeen = new HashSet<>();
        for (MailMessage m : loaded) {
            folderSeen.add(messageKey(accountId, folder, m));
        }
        if (!inbox) {
            seenMessageKeys.addAll(folderSeen);
            baselineTaken = true;
            return new ArrayList<>();
        }
        List<MailMessage> arrived = new ArrayList<>();
        if (baselineTaken) {
            for (MailMessage m : loaded) {
                if (!m.isRead() && !seenMessageKeys.contains(
                        messageKey(accountId, folder, m))) {
                    arrived.add(m);
                }
            }
        }
        seenMessageKeys.addAll(folderSeen);
        baselineTaken = true;
        if (arrived.isEmpty() || !enabled) {
            return arrived;
        }
        MailMessage newest = arrived.get(arrived.size() - 1);
        String message = (arrived.size() == 1)
                ? newest.getFrom().display() + ": " + newest.getSubject()
                : arrived.size() + " new message(s)\n"
                        + newest.getFrom().display() + ": " + newest.getSubject();
        notifier.notify(TITLE, message, Notification.Kind.INFO);
        return arrived;
    }

    /** Clears the seen-set and the baseline flag (tests / account switch). */
    void reset() {
        seenMessageKeys.clear();
        baselineTaken = false;
    }

    private static String messageKey(String accountId, String folder,
            MailMessage m) {
        String id = m.getId();
        // Envelopes without a server id fall back to sender+subject+date,
        // which is stable across reloads of the same backend listing.
        String discriminator = (id == null || id.isEmpty())
                ? m.getFrom().getEmail() + "|" + m.getSubject()
                        + "|" + m.getSentDate()
                : id;
        return accountId + "/" + folder + "/" + discriminator;
    }
}
