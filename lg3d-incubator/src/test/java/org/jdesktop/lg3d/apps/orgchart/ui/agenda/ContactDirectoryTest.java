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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.jdesktop.lg3d.contacts.Contact;
import org.jdesktop.lg3d.contacts.ContactStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ContactDirectory}, the agenda/mail adapter over the
 * shared {@code org.jdesktop.lg3d.contacts.ContactStore}. The store is pointed
 * at a {@link TempDir}, so the developer's real address book is never touched.
 */
class ContactDirectoryTest {

    @Test
    @DisplayName("a fresh address book reads as an empty directory")
    void emptyStoreIsEmptyDirectory(@TempDir Path dir) {
        ContactDirectory directory = new ContactDirectory(new ContactStore(dir));
        assertTrue(directory.isEmpty());
        assertTrue(directory.getContacts().isEmpty());
    }

    @Test
    @DisplayName("reload reflects contacts added to the shared store")
    void reloadReflectsStore(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        ContactDirectory directory = new ContactDirectory(store);
        Contact saved = store.add(new Contact("Ada", "Lovelace", "ada@example.org"));

        directory.reload();
        assertEquals(1, directory.getContacts().size());
        ContactDirectory.ContactInfo info = directory.get(saved.getId());
        assertEquals("Ada Lovelace", info.displayName);
        assertEquals("ada@example.org", info.email);
        assertNull(directory.get("no-such-uid"));
    }

    @Test
    @DisplayName("the display name falls back for e-mail-only contacts")
    void displayNameFallback(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        Contact saved = store.add(new Contact("", "", "solo@example.org"));
        ContactDirectory directory = new ContactDirectory(store);
        assertEquals("solo@example.org", directory.get(saved.getId()).displayName);
    }
}
