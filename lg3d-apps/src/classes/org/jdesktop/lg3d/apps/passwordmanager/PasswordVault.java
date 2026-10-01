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
package org.jdesktop.lg3d.apps.passwordmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The unlocked, in-memory vault: an ordered list of {@link PasswordEntry} items
 * with the small amount of behaviour the panel needs (add / remove / replace /
 * filter / distinct categories) and the JSON (de)serialisation that
 * {@link VaultEnvelope} seals. A vault only exists in memory while the app is
 * unlocked; {@link PasswordManagerPanel} clears it (and the derived key) on lock
 * and on auto-lock, so nothing secret outlives the session in a field.
 *
 * <p>The model is deliberately AWT-free and side-effect free - it never touches
 * the filesystem or a cipher directly - so it is trivially unit-testable. The
 * bytes produced by {@link #toJsonBytes()} are what gets encrypted; reading them
 * back is {@link #fromJsonBytes(byte[])}.</p>
 */
public class PasswordVault {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<PasswordEntry> entries = new ArrayList<>();

    /** An empty vault. */
    public PasswordVault() {
    }

    /**
     * A vault pre-populated with the given entries (defensively copied).
     *
     * @param initial the entries to start with (may be null)
     */
    public PasswordVault(List<PasswordEntry> initial) {
        if (initial != null) {
            for (PasswordEntry entry : initial) {
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
    }

    /** @return the number of entries. */
    public int size() {
        return entries.size();
    }

    /** @return true when the vault holds no entries. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Adds an entry (ignored when null).
     *
     * @param entry the entry to add
     */
    public void add(PasswordEntry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    /**
     * Removes the entry at {@code index} if it is in range.
     *
     * @param index the zero-based position
     * @return the removed entry, or null when the index was out of range
     */
    public PasswordEntry remove(int index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.remove(index);
    }

    /**
     * Replaces the entry at {@code index} with {@code entry}, both of which must
     * be valid; used by the detail editor's Save.
     *
     * @param index the zero-based position
     * @param entry the new entry (ignored when null)
     * @return true when the replacement happened
     */
    public boolean replace(int index, PasswordEntry entry) {
        if (entry == null || index < 0 || index >= entries.size()) {
            return false;
        }
        entries.set(index, entry);
        return true;
    }

    /**
     * The entry at {@code index}, or null when out of range.
     *
     * @param index the zero-based position
     * @return the entry, or null
     */
    public PasswordEntry get(int index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get(index);
    }

    /** @return an unmodifiable view of the entries, in insertion order. */
    public List<PasswordEntry> entries() {
        return List.copyOf(entries);
    }

    /**
     * The entries whose title / username / URL / category match {@code query}
     * (see {@link PasswordEntry#matches(String)}).
     *
     * @param query the filter text (null/blank returns everything)
     * @return a new list of matching entries, never null
     */
    public List<PasswordEntry> find(String query) {
        List<PasswordEntry> out = new ArrayList<>();
        for (PasswordEntry entry : entries) {
            if (entry.matches(query)) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * The distinct, non-blank categories across all entries, in first-seen order.
     *
     * @return the category names, never null
     */
    public List<String> categories() {
        Set<String> seen = new LinkedHashSet<>();
        for (PasswordEntry entry : entries) {
            String category = entry.getCategory();
            if (category != null && !category.isBlank()) {
                seen.add(category);
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * Serialises the vault to JSON bytes (the plaintext {@link VaultEnvelope}
     * seals).
     *
     * @return the UTF-8 JSON bytes, never null
     * @throws IOException if serialisation fails (should not, for plain beans)
     */
    public byte[] toJsonBytes() throws IOException {
        return MAPPER.writeValueAsBytes(entries);
    }

    /**
     * Rebuilds a vault from the JSON bytes recovered from an envelope. A null or
     * empty payload yields an empty vault rather than throwing.
     *
     * @param json the plaintext vault JSON
     * @return the vault, never null
     * @throws IOException if the bytes are not a valid entry list
     */
    public static PasswordVault fromJsonBytes(byte[] json) throws IOException {
        if (json == null || json.length == 0) {
            return new PasswordVault();
        }
        List<PasswordEntry> list = MAPPER.readValue(json,
                new TypeReference<List<PasswordEntry>>() { });
        return new PasswordVault(list);
    }

    /**
     * Rebuilds a vault from a JSON string (UTF-8), for tests and tooling.
     *
     * @param json the plaintext vault JSON
     * @return the vault, never null
     * @throws IOException if the text is not a valid entry list
     */
    public static PasswordVault fromJson(String json) throws IOException {
        if (json == null) {
            return new PasswordVault();
        }
        return fromJsonBytes(json.getBytes(StandardCharsets.UTF_8));
    }
}
