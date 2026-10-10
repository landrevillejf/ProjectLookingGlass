/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The user's ordered toolbar button selection: which commands (extension
 * toolbar contributions, and any future built-in id) appear in the editor's
 * user-configured toolbar section, and how each is drawn. It is a plain, AWT-free
 * value object so the whole model &mdash; ordering, add / remove / move, id
 * validation and the {@code '|'}-joined persistence format &mdash; is testable
 * headless, without a panel or a preference store.
 *
 * <p>Each {@link Entry} pairs a stable id (fully-qualified
 * {@code providerId/actionId} for an extension contribution) with a
 * {@link DisplayMode} that decides icon-only, text-only, or icon-and-text
 * rendering. Entries are unique by id: adding an id already present just updates
 * its mode rather than duplicating the button.</p>
 *
 * <p>Validation is deliberately split: this class never drops an unknown id on
 * load (a disabled extension must not corrupt the stored layout); the caller
 * filters against the currently-available contributions at <em>render</em> time
 * via {@link #resolve(Collection)}.</p>
 */
public final class ToolbarButtonConfig {

    /** Pref-key separator between entries, and the id/mode delimiter. */
    static final char ENTRY_SEP = '|';
    static final char MODE_SEP = ':';

    /** How a configured toolbar button draws its contribution. */
    public enum DisplayMode {
        ICON, TEXT, ICON_TEXT;

        /**
         * @param value    the token to interpret (case-insensitive; may be null)
         * @param fallback used when {@code value} is null or unrecognised
         * @return the matching mode, never null
         */
        public static DisplayMode parse(String value, DisplayMode fallback) {
            if (value != null) {
                for (DisplayMode m : values()) {
                    if (m.name().equalsIgnoreCase(value.trim())) {
                        return m;
                    }
                }
            }
            return fallback;
        }
    }

    /** The display mode a bare id gets when none is stored. */
    public static final DisplayMode DEFAULT_MODE = DisplayMode.ICON_TEXT;

    /** One ordered toolbar button: a stable contribution id and its display mode. */
    public record Entry(String id, DisplayMode mode) {
        public Entry {
            id = (id == null) ? "" : id.trim();
            mode = (mode == null) ? DEFAULT_MODE : mode;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** @return an empty configuration (the built-in toolbar buttons stand alone). */
    public static ToolbarButtonConfig defaults() {
        return new ToolbarButtonConfig();
    }

    /**
     * Parses the {@code '|'}-joined {@code id[:mode]} persistence format written
     * by {@link #toConfigString()}. A blank / null string yields the empty
     * (default) configuration; malformed entries (empty id) are skipped rather
     * than throwing, so a corrupt store degrades gracefully.
     *
     * @param text the persisted string, may be null
     * @return the parsed configuration, never null
     */
    public static ToolbarButtonConfig parse(String text) {
        ToolbarButtonConfig cfg = new ToolbarButtonConfig();
        if (text == null || text.isBlank()) {
            return cfg;
        }
        for (String raw : text.split("\\" + ENTRY_SEP)) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            int sep = part.lastIndexOf(MODE_SEP);
            String id;
            String mode;
            if (sep < 0) {
                id = part;
                mode = null;
            } else {
                id = part.substring(0, sep).trim();
                mode = part.substring(sep + 1).trim();
            }
            if (!id.isEmpty()) {
                cfg.add(id, DisplayMode.parse(mode, DEFAULT_MODE));
            }
        }
        return cfg;
    }

    /**
     * @return this configuration in the {@code '|'}-joined {@code id:mode} form,
     *         or "" when empty (so the caller can omit the preference entirely)
     */
    public String toConfigString() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) {
                sb.append(ENTRY_SEP);
            }
            sb.append(e.id()).append(MODE_SEP).append(e.mode().name());
        }
        return sb.toString();
    }

    /**
     * Appends {@code id} with the default mode, or updates its mode when already
     * present (ids stay unique, no duplicate buttons). A blank id is ignored.
     *
     * @param id the stable contribution id
     */
    public void add(String id) {
        add(id, DEFAULT_MODE);
    }

    /** Appends / updates {@code id} with an explicit {@code mode}. */
    public void add(String id, DisplayMode mode) {
        if (id == null || id.isBlank()) {
            return;
        }
        String clean = id.trim();
        DisplayMode m = (mode == null) ? DEFAULT_MODE : mode;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(clean)) {
                entries.set(i, new Entry(clean, m));
                return;
            }
        }
        entries.add(new Entry(clean, m));
    }

    /** Removes the entry with id {@code id}; no-op when absent. */
    public void remove(String id) {
        if (id == null) {
            return;
        }
        String clean = id.trim();
        entries.removeIf(e -> e.id().equals(clean));
    }

    /**
     * Replaces the whole ordered selection with {@code newEntries} (de-duplicated
     * by id, preserving first-seen order). Used to load a working draft wholesale.
     *
     * @param newEntries the entries to adopt, may be null (clears)
     */
    public void replaceFrom(Collection<Entry> newEntries) {
        entries.clear();
        if (newEntries == null) {
            return;
        }
        for (Entry e : newEntries) {
            // First-seen wins: a later duplicate id is dropped rather than
            // updating the mode (unlike add()), so a wholesale load is stable.
            if (e != null && !e.id().isBlank() && !contains(e.id())) {
                entries.add(new Entry(e.id(), e.mode()));
            }
        }
    }

    /**
     * Moves the entry at {@code index} by {@code delta} positions, clamped to
     * the list ends (so "up" on the first and "down" on the last are no-ops).
     *
     * @param index the current position
     * @param delta the shift (-1 up / +1 down)
     * @return true when a move actually happened
     */
    public boolean move(int index, int delta) {
        if (index < 0 || index >= entries.size() || delta == 0) {
            return false;
        }
        int target = Math.max(0, Math.min(index + delta, entries.size() - 1));
        if (target == index) {
            return false;
        }
        Entry e = entries.remove(index);
        entries.add(target, e);
        return true;
    }

    /** Sets the display mode of the entry at {@code index}; no-op when out of range. */
    public void setMode(int index, DisplayMode mode) {
        if (index < 0 || index >= entries.size()) {
            return;
        }
        Entry e = entries.get(index);
        entries.set(index, new Entry(e.id(), mode));
    }

    /** @return the entry at {@code index}, or null when out of range. */
    public Entry get(int index) {
        return (index >= 0 && index < entries.size()) ? entries.get(index) : null;
    }

    /** @return the number of configured buttons. */
    public int size() {
        return entries.size();
    }

    /** @return true when no user button is configured. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** @return the ordered entries, unmodifiable. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** @return the configured ids in order, unmodifiable. */
    public List<String> ids() {
        List<String> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(e.id());
        }
        return Collections.unmodifiableList(out);
    }

    /** @return true when an entry with id {@code id} is configured. */
    public boolean contains(String id) {
        if (id == null) {
            return false;
        }
        String clean = id.trim();
        for (Entry e : entries) {
            if (e.id().equals(clean)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves this configuration against the ids currently available from the
     * enabled extensions: entries whose id is not in {@code availableIds} are
     * dropped (stale ids from a disabled/removed extension never render, and are
     * never purged from the stored layout either).
     *
     * @param availableIds the ids the editor can render right now
     * @return the renderable entries, in order, never null
     */
    public List<Entry> resolve(Collection<String> availableIds) {
        Set<String> available = new LinkedHashSet<>(
                (availableIds == null) ? Set.of() : availableIds);
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (available.contains(e.id())) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolbarButtonConfig)) {
            return false;
        }
        return entries.equals(((ToolbarButtonConfig) o).entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return toConfigString();
    }
}
