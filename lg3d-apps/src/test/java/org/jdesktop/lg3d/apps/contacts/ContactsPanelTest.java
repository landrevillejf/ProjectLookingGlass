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
package org.jdesktop.lg3d.apps.contacts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.contacts.Contact;
import org.jdesktop.lg3d.contacts.ContactStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ContactsPanel}, the production address-book UI of
 * both desktops. Every panel is built over a {@link ContactStore} pointed at a
 * {@link TempDir}, so the developer's real {@code ~/.lg3d/contacts} is never
 * touched. The CRUD paths exercised here avoid the modal dialogs (delete
 * confirmation, file choosers), which cannot run headless; those are covered
 * by the vCard codec tests instead.
 */
class ContactsPanelTest {

    @Test
    @DisplayName("the panel constructs headless and starts on the empty state")
    void panelConstructsHeadless(@TempDir Path dir) {
        ThrowingSupplier<ContactsPanel> ctor = () -> new ContactsPanel(new ContactStore(dir));
        ContactsPanel panel = assertDoesNotThrow(ctor);
        assertNotNull(panel.getLayout());
        assertTrue(panel.getComponentCount() > 0);
        assertEquals(0, panel.getContactCount());
        assertEquals("No contacts yet", panel.getDisplayedName());
        assertTrue(Contacts.WIDTH_PX > 0 && Contacts.HEIGHT_PX > 0);
    }

    @Test
    @DisplayName("the no-arg constructor exists for the 2D registry lookup")
    void noArgConstructorExists() {
        // Desktop2DAppRegistry.PANEL_APPS builds the panel reflectively through
        // this constructor, so it must exist. (It points at the real user store
        // and only reads it, which is harmless headless.)
        ThrowingSupplier<ContactsPanel> ctor = ContactsPanel::new;
        ContactsPanel panel = assertDoesNotThrow(ctor);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("contacts added to the shared store show up after refresh")
    void storeChangesAppearOnRefresh(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        ContactsPanel panel = new ContactsPanel(store);
        store.add(new Contact("Ada", "Lovelace", "ada@example.org"));
        panel.refreshList();
        assertEquals(1, panel.getContactCount());
        assertEquals("Ada Lovelace", panel.getDisplayedName());
    }

    @Test
    @DisplayName("the create flow persists through saveEdit")
    void createFlowPersists(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        ContactsPanel panel = new ContactsPanel(store);
        panel.startCreate();
        panel.editFirstField().setText("Grace");
        panel.editLastField().setText("Hopper");
        panel.editEmailsArea().setText("grace@example.org\ngrace@navy.mil");
        panel.editFavoriteBox().setSelected(true);
        panel.saveEdit();

        assertEquals(1, store.size());
        Contact saved = store.all().get(0);
        assertEquals("Grace Hopper", saved.displayName());
        assertEquals(List.of("grace@example.org", "grace@navy.mil"), saved.getEmails());
        assertTrue(saved.isFavorite());
        assertEquals(1, panel.getContactCount());
        // The favourite star prefixes the displayed name.
        assertTrue(panel.getDisplayedName().contains("Grace Hopper"));
    }

    @Test
    @DisplayName("the edit flow updates the stored contact in place")
    void editFlowUpdates(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        Contact c = store.add(new Contact("Alan", "Turing", "alan@example.org"));
        ContactsPanel panel = new ContactsPanel(store);
        panel.startEdit();
        panel.editLastField().setText("Mathison");
        panel.saveEdit();

        assertEquals(1, store.size(), "edit must not duplicate");
        assertEquals("Alan Mathison", store.get(c.getId()).orElseThrow().displayName());
    }

    @Test
    @DisplayName("cancelEdit discards the editor without writing")
    void cancelDiscards(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        ContactsPanel panel = new ContactsPanel(store);
        panel.startCreate();
        panel.editFirstField().setText("Ghost");
        panel.cancelEdit();
        assertEquals(0, store.size());
        assertEquals(0, panel.getContactCount());
    }

    @Test
    @DisplayName("the search field filters the list incrementally")
    void searchFilters(@TempDir Path dir) {
        ContactStore store = new ContactStore(dir);
        store.add(new Contact("Alan", "Turing", "alan@bletchley.uk"));
        store.add(new Contact("Katherine", "Johnson", "katherine@nasa.gov"));
        ContactsPanel panel = new ContactsPanel(store);
        assertEquals(2, panel.getContactCount());

        // setText fires the DocumentListener, which refreshes the list.
        panel.searchField().setText("turing");
        assertEquals(1, panel.getContactCount());
        assertEquals("Alan Turing", panel.getShownContacts().get(0).displayName());

        panel.searchField().setText("nasa.gov");
        assertEquals(1, panel.getContactCount());
        assertEquals("Katherine Johnson", panel.getShownContacts().get(0).displayName());

        panel.searchField().setText("");
        assertEquals(2, panel.getContactCount());
    }

    @Test
    @DisplayName("vCard export/import round-trips the whole book")
    void vcardRoundTrip() {
        Contact c = new Contact("Ada", "Lovelace", "ada@example.org");
        c.setNickname("countess");
        c.setOrganization("Analytical Engines Ltd");
        c.setTitle("Chief Mathematician");
        c.getPhones().add("+44 20 7946 0000");
        c.getTags().add("vip");
        c.getTags().add("history");
        c.setNotes("line one\nline two");

        String vcf = ContactsPanel.VCard.format(List.of(c));
        assertTrue(vcf.startsWith("BEGIN:VCARD"));
        assertTrue(vcf.contains("VERSION:3.0"));

        List<Contact> back = ContactsPanel.VCard.parse(vcf);
        assertEquals(1, back.size());
        Contact r = back.get(0);
        assertEquals("Ada Lovelace", r.displayName());
        assertEquals("countess", r.getNickname());
        assertEquals("ada@example.org", r.primaryEmail());
        assertEquals("+44 20 7946 0000", r.primaryPhone());
        assertEquals("Analytical Engines Ltd", r.getOrganization());
        assertEquals("Chief Mathematician", r.getTitle());
        assertEquals(List.of("vip", "history"), r.getTags());
        assertEquals("line one\nline two", r.getNotes());
    }

    @Test
    @DisplayName("vCard parsing tolerates junk and parameterised properties")
    void vcardParseIsDefensive() {
        String vcf = String.join("\n",
                "garbage before any card",
                "BEGIN:VCARD",
                "VERSION:3.0",
                "N:Doe;Jane;;;",
                "EMAIL;TYPE=INTERNET,WORK:jane@example.org",
                "TEL;TYPE=CELL:+1 555 0100",
                "line-without-colon",
                "UNKNOWN-PROP:whatever",
                "END:VCARD",
                "BEGIN:VCARD",
                "END:VCARD");
        List<Contact> parsed = ContactsPanel.VCard.parse(vcf);
        assertEquals(2, parsed.size());
        assertEquals("Jane Doe", parsed.get(0).displayName());
        assertEquals("jane@example.org", parsed.get(0).primaryEmail());
        assertEquals("+1 555 0100", parsed.get(0).primaryPhone());
        assertEquals("(unnamed)", parsed.get(1).displayName());
        assertTrue(ContactsPanel.VCard.parse("").isEmpty());
    }

    @Test
    @DisplayName("an imported vCard lands in the shared store")
    void importedCardsPersist(@TempDir Path dir) throws Exception {
        ContactStore store = new ContactStore(dir);
        ContactsPanel panel = new ContactsPanel(store);
        for (Contact c : ContactsPanel.VCard.parse(
                ContactsPanel.VCard.format(List.of(
                        new Contact("Imported", "Person", "i@example.org"))))) {
            store.add(c);
        }
        panel.refreshList();
        assertEquals(1, panel.getContactCount());
        assertEquals("Imported Person", panel.getDisplayedName());
    }
}
