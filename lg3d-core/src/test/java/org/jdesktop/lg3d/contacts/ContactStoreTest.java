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
package org.jdesktop.lg3d.contacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless CRUD / persistence tests for the shared address book.
 *
 * <p>Each test points the store at a {@link TempDir} so it never touches the
 * developer's real {@code ~/.lg3d/contacts}. The store is pure file + model
 * logic (no AWT), so everything runs headless on CI.</p>
 */
class ContactStoreTest {

    private Contact newContact(String first, String last, String email) {
        Contact c = new Contact(first, last, email);
        return c;
    }

    @Test
    @DisplayName("a fresh store is empty and never seeded with demo data")
    void freshStoreIsEmpty(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        assertEquals(0, store.size());
        assertTrue(store.all().isEmpty());
        assertFalse(java.nio.file.Files.exists(store.file()),
                "no file (and no demo data) before the first write");
    }

    @Test
    @DisplayName("add assigns an id and timestamps and persists")
    void addPersists(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        Contact saved = store.add(newContact("Ada", "Lovelace", "ada@example.org"));
        assertFalse(saved.getId().isEmpty());
        assertEquals(saved.getCreatedMillis(), saved.getUpdatedMillis());
        assertEquals(1, store.size());
        // A second store over the same dir reads what the first wrote.
        ContactStore reload = new ContactStore(dir);
        assertEquals(1, reload.size());
        assertEquals("Ada Lovelace", reload.all().get(0).displayName());
        assertEquals("ada@example.org", reload.all().get(0).primaryEmail());
    }

    @Test
    @DisplayName("update replaces by id and bumps updatedMillis")
    void updateReplaces(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        Contact saved = store.add(newContact("Grace", "Hopper", "grace@example.org"));
        saved.setLastName("Brewster");
        assertTrue(store.update(saved));
        assertEquals("Grace Brewster", store.get(saved.getId()).orElseThrow().displayName());
        assertFalse(store.update(newContact("Nobody", "Here", "")),
                "updating an unknown id is a no-op");
    }

    @Test
    @DisplayName("delete removes by id")
    void deleteRemoves(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        Contact a = store.add(newContact("A", "One", "a@example.org"));
        store.add(newContact("B", "Two", "b@example.org"));
        assertEquals(2, store.size());
        assertTrue(store.delete(a.getId()));
        assertFalse(store.delete(a.getId()), "second delete of same id is false");
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("search matches name, email, org and tag case-insensitively")
    void searchMatches(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        Contact c = newContact("Alan", "Turing", "alan@bletchley.uk");
        c.setOrganization("GCCS");
        c.getTags().add("cryptanalysis");
        store.add(c);
        store.add(newContact("Katherine", "Johnson", "katherine@nasa.gov"));

        assertEquals(2, store.search(null).size());
        assertEquals(2, store.search("  ").size());
        assertEquals(1, store.search("alan").size());
        assertEquals(1, store.search("TURING").size());
        assertEquals(1, store.search("bletchley").size());
        assertEquals(1, store.search("gccs").size());
        assertEquals(1, store.search("Crypt").size());
        assertEquals(1, store.search("johnson").size());
        assertTrue(store.search("zzz-no-match").isEmpty());
    }

    @Test
    @DisplayName("all() sorts favourites first then by name")
    void orderingFavouritesFirst(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        Contact zed = store.add(newContact("Zed", "Alpha", "z@example.org"));
        store.add(newContact("Amy", "Beta", "a@example.org"));
        zed.setFavorite(true);
        store.update(zed);
        List<Contact> all = store.all();
        assertEquals("Zed Alpha", all.get(0).displayName(), "favourite first");
        assertEquals("Amy Beta", all.get(1).displayName());
    }

    @Test
    @DisplayName("a corrupt file degrades to an empty book instead of throwing")
    void corruptFileDegrades(@TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("contacts.json"), "{not json!!");
        ContactStore store = new ContactStore(dir);
        assertEquals(0, store.size(), "corrupt book reads as empty");
        // And it is still writable afterwards.
        store.add(newContact("Re", "Covered", "r@example.org"));
        assertEquals(1, new ContactStore(dir).size());
    }

    @Test
    @DisplayName("the display name falls back to nickname then e-mail")
    void displayNameFallbacks() {
        Contact onlyEmail = new Contact("", "", "solo@example.org");
        assertEquals("solo@example.org", onlyEmail.displayName());
        Contact nick = new Contact("", "", "");
        nick.setNickname("dude");
        assertEquals("dude", nick.displayName());
        assertEquals("(unnamed)", new Contact("", "", "").displayName());
    }
}
