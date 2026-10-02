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
package org.jdesktop.lg3d.contacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The desktop-wide address book: JSON CRUD over {@code ~/.lg3d/contacts}.
 *
 * <p>This is the single source of truth shared by the production Contacts app,
 * the Agenda attendee picker and the Messenger / Video Conference address
 * books. It deliberately replaces the legacy {@code java.util.prefs}
 * {@code /contacts} node and its bundled demo {@code contacts.xml}: the store
 * starts <b>empty</b> and only ever holds what the user creates.</p>
 *
 * <p>Design notes:</p>
 * <ul>
 *  <li><b>Fast</b> — contacts are held in memory and mutated there; a write
 *      touches disk once per mutation, serialised to a small JSON file.</li>
 *  <li><b>Safe</b> — every mutator is {@code synchronized}; saves write a temp
 *      file then {@code ATOMIC_MOVE} over the real one, so a crash mid-write
 *      cannot corrupt the address book. Reads are defensive: a missing or
 *      corrupt file yields an empty book, never a throw.</li>
 *  <li><b>Testable</b> — the directory is overridable with the
 *      {@value #DIR_PROPERTY} system property so headless tests never touch
 *      the developer's real address book.</li>
 * </ul>
 */
public final class ContactStore {

    /** System property overriding the store directory (tests). */
    public static final String DIR_PROPERTY = "lg3d.contacts.dir";

    private static final String FILE_NAME = "contacts.json";

    private static final Logger logger = Logger.getLogger(ContactStore.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final List<Contact> contacts = new ArrayList<>();
    private boolean loaded;

    /** A store over the default (or property-overridden) directory. */
    public ContactStore() {
        this(defaultDir());
    }

    /**
     * A store over an explicit directory.
     *
     * @param dir directory holding {@code contacts.json}
     */
    public ContactStore(Path dir) {
        this.file = dir.resolve(FILE_NAME);
    }

    private static Path defaultDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "contacts");
    }

    /** The backing JSON file (mainly for tests and diagnostics). */
    public Path file() {
        return file;
    }

    /**
     * All contacts, favourites first then by display name. Loads on first use.
     *
     * @return a live-order snapshot (a new list; safe to iterate)
     */
    public synchronized List<Contact> all() {
        ensureLoaded();
        List<Contact> out = new ArrayList<>(contacts);
        out.sort(Comparator.comparing(Contact::isFavorite).reversed()
                .thenComparing(c -> c.displayName().toLowerCase(Locale.ROOT)));
        return out;
    }

    /**
     * Contacts matching a free-text query over name, nickname, e-mail,
     * organisation, title and tags (case-insensitive). An empty query matches
     * everything.
     *
     * @param query the text to search for (may be null/blank)
     */
    public synchronized List<Contact> search(String query) {
        List<Contact> base = all();
        if (query == null || query.isBlank()) {
            return base;
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Contact> out = new ArrayList<>();
        for (Contact c : base) {
            if (matches(c, q)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean matches(Contact c, String q) {
        if (c.displayName().toLowerCase(Locale.ROOT).contains(q)
                || c.getNickname().toLowerCase(Locale.ROOT).contains(q)
                || c.getOrganization().toLowerCase(Locale.ROOT).contains(q)
                || c.getTitle().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (String e : c.getEmails()) {
            if (e.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        for (String t : c.getTags()) {
            if (t.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** The contact with {@code id}, or empty when absent. */
    public synchronized Optional<Contact> get(String id) {
        ensureLoaded();
        for (Contact c : contacts) {
            if (c.getId().equals(id)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * Adds a new contact (assigning a fresh id and timestamps) and saves.
     *
     * @return the persisted contact
     */
    public synchronized Contact add(Contact c) {
        ensureLoaded();
        Contact copy = clone(c);
        copy.setId(java.util.UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        copy.setCreatedMillis(now);
        copy.setUpdatedMillis(now);
        contacts.add(copy);
        save();
        return copy;
    }

    /**
     * Replaces the stored contact with the same id and saves.
     *
     * @return true when a contact was updated
     */
    public synchronized boolean update(Contact c) {
        ensureLoaded();
        for (int i = 0; i < contacts.size(); i++) {
            if (contacts.get(i).getId().equals(c.getId())) {
                Contact copy = clone(c);
                copy.setUpdatedMillis(System.currentTimeMillis());
                contacts.set(i, copy);
                save();
                return true;
            }
        }
        return false;
    }

    /**
     * Deletes the contact with {@code id} and saves.
     *
     * @return true when a contact was removed
     */
    public synchronized boolean delete(String id) {
        ensureLoaded();
        boolean removed = contacts.removeIf(c -> c.getId().equals(id));
        if (removed) {
            save();
        }
        return removed;
    }

    /** Number of stored contacts (loads on first use). */
    public synchronized int size() {
        ensureLoaded();
        return contacts.size();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        contacts.clear();
        if (!Files.isRegularFile(file)) {
            return;   // a fresh address book is empty, never seeded
        }
        try {
            Contact[] read = MAPPER.readValue(file.toFile(), Contact[].class);
            if (read != null) {
                for (Contact c : read) {
                    if (c != null) {
                        contacts.add(c);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt book must not take the desktop down: start empty and
            // keep the unreadable file untouched (the next save rewrites it).
            logger.log(Level.WARNING, "Could not read address book " + file, e);
            contacts.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            MAPPER.writeValue(tmp.toFile(), contacts);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException am) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not write address book " + file, e);
        }
    }

    /** Defensive copy so callers cannot mutate the stored instance. */
    private static Contact clone(Contact c) {
        Contact copy = MAPPER.convertValue(c, Contact.class);
        return copy;
    }
}
