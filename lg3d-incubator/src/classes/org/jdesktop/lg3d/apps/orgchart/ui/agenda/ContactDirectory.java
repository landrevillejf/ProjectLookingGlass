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
package org.jdesktop.lg3d.apps.orgchart.ui.agenda;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.contacts.ContactStore;

/**
 * Read-only view of the desktop-wide address book for the agenda / mail UIs.
 *
 * <p>This is a thin adapter over the shared {@link ContactStore}
 * ({@code ~/.lg3d/contacts/contacts.json}, {@code org.jdesktop.lg3d.contacts}
 * in lg3d-core) that the production Contacts app edits. Because every app runs
 * in the same JVM and reads the same store, the contacts created in the
 * Contacts app are immediately available here as meeting attendees and mail
 * recipients — and there is no bundled demo data: a fresh address book is
 * simply empty until the user fills it.</p>
 *
 * <p>The legacy {@code java.util.prefs} {@code /contacts} node, the bundled
 * {@code contacts.xml} seeding and the fake free/busy presence flag are gone:
 * an address book holds identity, not presence.</p>
 */
public class ContactDirectory {

    private final ContactStore store;

    /** A lightweight, immutable snapshot of one contact for the agenda UI. */
    public static final class ContactInfo {
        public final String uid;
        public final String displayName;
        public final String email;

        ContactInfo(String uid, String displayName, String email) {
            this.uid = uid;
            this.displayName = displayName;
            this.email = email;
        }
    }

    private final List<ContactInfo> contacts = new ArrayList<ContactInfo>();

    public ContactDirectory() {
        this(new ContactStore());
    }

    /**
     * A directory over an explicit store (tests point this at a temp
     * directory).
     *
     * @param store the shared address book to read
     */
    public ContactDirectory(ContactStore store) {
        this.store = store;
        reload();
    }

    /** Re-reads the shared address book into memory. */
    public final void reload() {
        contacts.clear();
        for (org.jdesktop.lg3d.contacts.Contact c : store.all()) {
            contacts.add(new ContactInfo(c.getId(), c.displayName(), c.primaryEmail()));
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
