/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.apps.orgchart.framework.contact.Contact;
import org.jdesktop.lg3d.apps.orgchart.framework.contact.PreferenceContactService;

/**
 * Read-only view of the shared contact directory that {@code Contact3D}
 * populates. This is the one-way interaction between the two apps: they run in
 * the same JVM and share the same user {@link Preferences} root, so
 * {@code Agenda3D} reads exactly the contacts {@code Contact3D} imported under
 * {@code /contacts} and offers them as meeting attendees, showing each
 * contact's live presence beside their name.
 *
 * <p>If {@code Contact3D} has not run yet the node is empty, so — mirroring
 * {@code Contact3D.initFramework()} — the bundled {@code contacts.xml} is
 * imported on first use. That keeps {@code Agenda3D} functional standalone
 * while still sharing the same store once {@code Contact3D} refreshes it.</p>
 */
public class ContactDirectory {

    /** Same absolute node {@code Contact3D} imports its contacts into. */
    static final String PATH_CONTACTS = PreferenceContactService.DEFAULT_ROOT;

    private static final String CONTACTS_RESOURCE
            = "org/jdesktop/lg3d/apps/orgchart/ui/contact/contacts.xml";

    private static final Logger logger
            = Logger.getLogger(ContactDirectory.class.getName());

    /** A lightweight, immutable snapshot of one contact for the agenda UI. */
    public static final class ContactInfo {
        public final String uid;
        public final String displayName;
        public final String email;
        public final boolean busy;

        ContactInfo(String uid, String displayName, String email, boolean busy) {
            this.uid = uid;
            this.displayName = displayName;
            this.email = email;
            this.busy = busy;
        }
    }

    private final List<ContactInfo> contacts = new ArrayList<ContactInfo>();

    public ContactDirectory() {
        ensurePopulated();
        reload();
    }

    /** Imports the bundled contacts if the shared node is not there yet. */
    private void ensurePopulated() {
        try {
            if (!Preferences.userRoot().nodeExists(PATH_CONTACTS)) {
                InputStream in = getClass().getClassLoader()
                        .getResourceAsStream(CONTACTS_RESOURCE);
                if (in != null) {
                    Preferences.userRoot().importPreferences(in);
                } else {
                    logger.warning("Bundled contacts.xml not found on classpath: "
                            + CONTACTS_RESOURCE);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error importing shared contacts", e);
        }
    }

    /** Re-reads the shared {@code /contacts} node into memory. */
    public final void reload() {
        contacts.clear();
        try {
            Preferences root = Preferences.userRoot().node(PATH_CONTACTS);
            String[] names = root.childrenNames();
            java.util.Arrays.sort(names);
            for (String name : names) {
                Preferences node = root.node(name);
                String uid = node.get(Contact.ATTR_UID, name);
                String first = node.get(Contact.ATTR_FIRSTNAME, "");
                String last = node.get(Contact.ATTR_LASTNAME, "");
                String display = (first + " " + last).trim();
                if (display.length() == 0) {
                    display = name;
                }
                String email = node.get(Contact.ATTR_EMAIL, null);
                // Contact3D's Randomizer drives presence in memory only, so a
                // freshly imported contact may have no persisted presence; treat
                // an explicit "busy" presence or a non-null calendarBusy flag as
                // busy and everything else as free.
                String presence = node.get(Contact.ATTR_PRESENCE, Contact.PRESENCE_OPEN);
                boolean busy = Contact.PRESENCE_BUSY.equals(presence)
                        || node.get(Contact.ATTR_CALENDAR_BUSY, null) != null;
                contacts.add(new ContactInfo(uid, display, email, busy));
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error reading shared contacts", e);
        }
    }

    public List<ContactInfo> getContacts() {
        return contacts;
    }

    public ContactInfo get(String uid) {
        for (ContactInfo c : contacts) {
            if (c.uid.equals(uid)) {
                return c;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return contacts.isEmpty();
    }
}
