/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.orgchart.ui.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.prefs.Preferences;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.ContactDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ContactCardsPanel}, the 2D/Swing counterpart of
 * Contact3D. Clearing {@code /contacts} first makes {@link ContactDirectory}
 * import the bundled {@code contacts.xml}, so the panel always sees the same
 * four sample contacts.
 */
class ContactCardsPanelTest {

    @BeforeEach
    void clear() {
        try {
            if (Preferences.userRoot().nodeExists("/contacts")) {
                Preferences.userRoot().node("/contacts").removeNode();
            }
        } catch (Exception e) {
            // best effort
        }
    }

    @Test
    void listsTheSharedContacts() {
        ContactCardsPanel panel = new ContactCardsPanel();
        assertEquals(4, panel.getContactCount());
        assertEquals(4, panel.getContacts().size());
    }

    @Test
    void firstContactIsShownOnOpen() {
        ContactCardsPanel panel = new ContactCardsPanel();
        ContactDirectory.ContactInfo first = panel.getContacts().get(0);
        assertEquals(first.displayName, panel.getDisplayedName());
    }

    @Test
    void showContactHandlesNullAndValue() {
        ContactCardsPanel panel = new ContactCardsPanel();
        panel.showContact(null);
        assertEquals("(no contacts)", panel.getDisplayedName());
        panel.showContact(panel.getContacts().get(1));
        assertEquals(panel.getContacts().get(1).displayName, panel.getDisplayedName());
        assertTrue(panel.getDisplayedName().length() > 0);
    }
}
