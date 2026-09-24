/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists the mailbox to the {@link Preferences} tree, mirroring
 * {@code AppointmentStore}: one child node of {@link #ROOT} per message.
 *
 * <p>The store is local-only (no SMTP/IMAP): "sending" a message files it in
 * the Sent folder, which keeps the client fully functional offline and lets
 * the compose / reply / send loop be exercised end to end. On first run the
 * inbox is seeded with a handful of sample messages so the reading pane and
 * list have something to show before the user writes anything.</p>
 */
public class MailStore {

    /** Absolute user-preferences path holding one child node per message. */
    public static final String ROOT = "/mail/messages";

    private static final Logger logger
            = Logger.getLogger(MailStore.class.getName());

    private final Preferences root;

    public MailStore() {
        this.root = Preferences.userRoot().node(ROOT);
    }

    /** Loads every persisted message (unsorted; the view orders them). */
    public List<MailMessage> load() {
        List<MailMessage> list = new ArrayList<MailMessage>();
        try {
            String[] ids = root.childrenNames();
            java.util.Arrays.sort(ids);
            for (String id : ids) {
                list.add(MailMessage.readFrom(id, root.node(id)));
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error loading mailbox from " + ROOT, e);
        }
        return list;
    }

    /** Writes (or rewrites) a single message and flushes it to disk. */
    public void save(MailMessage m) {
        try {
            m.writeTo(root.node(m.getId()));
            root.flush();
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error saving message " + m.getId(), e);
        }
    }

    /** Removes a single message node. */
    public void delete(String id) {
        try {
            if (root.nodeExists(id)) {
                root.node(id).removeNode();
                root.flush();
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error deleting message " + id, e);
        }
    }

    /** Generates a unique, sort-stable id for a new message. */
    public String newId() {
        String base = String.format("%013d", System.currentTimeMillis());
        String id = base;
        int n = 0;
        try {
            while (root.nodeExists(id)) {
                id = base + "-" + (n++);
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error checking id availability", e);
        }
        return id;
    }

    /**
     * Fills the inbox with a few sample messages the first time the mail
     * client runs, so the list and reading pane are not empty. No-op once any
     * message already exists.
     */
    public void seedIfEmpty() {
        try {
            if (root.childrenNames().length > 0) {
                return;
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error checking mailbox emptiness", e);
            return;
        }
        long now = System.currentTimeMillis();
        String[][] seed = {
            {"Dana Whitfield", "dana@example.com", "You", "you@example.com",
             "Welcome to Mail 3D",
             "Hi,\n\nThis is your native 3D mailbox. Pick a message on the left "
             + "to read it here, use New or Reply to write, and Sent to file "
             + "outgoing mail.\n\nEverything is stored locally in your "
             + "preferences, so it survives a restart.\n\n- Dana"},
            {"Ravi Patel", "ravi@example.com", "You", "you@example.com",
             "Project Looking Glass build",
             "The Gradle build is green on JDK 21. I bumped the Jogamp natives "
             + "and the desktop boots clean in dev mode.\n\nCan you take a look "
             + "at the new start-menu entries before I open the PR?\n\nRavi"},
            {"Mia Chen", "mia@example.com", "You", "you@example.com",
             "Re: 3D desktop screenshots",
             "The internal screencapture (lgscreen-0-0.png) is the only capture "
             + "that works under Wayland; X-client tools just return black.\n\n"
             + "Attached mentally, since attachments are out of scope :)\n\nMia"},
            {"Build Bot", "bot@example.com", "You", "you@example.com",
             "Nightly result: SUCCESS",
             "Branch main, commit 289a315.\n\n  compile  PASS\n  runtimeRes  "
             + "PASS\n  jar upload PASS\n\nNo action required."},
        };
        for (int i = 0; i < seed.length; i++) {
            String[] s = seed[i];
            MailMessage m = new MailMessage(newId(), s[0], s[1], s[2], s[3],
                    s[4], s[5], MailMessage.FOLDER_INBOX);
            // Stagger the timestamps so the list sorts into a sensible order.
            m.setWhen(now - (seed.length - i) * 3600_000L);
            save(m);
        }
    }
}
