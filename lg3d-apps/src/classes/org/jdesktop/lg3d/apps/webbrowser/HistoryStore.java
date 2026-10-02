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
package org.jdesktop.lg3d.apps.webbrowser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The in-memory browsing history: a de-duplicated, capacity-capped list kept
 * newest-first. Like {@link BookmarkStore} it performs no file I/O &mdash;
 * {@link BrowserStore} loads and saves the list &mdash; so the retention logic is
 * unit-testable headless.
 *
 * <p>{@link #record} moves an already-seen URL to the front and bumps its visit
 * count rather than appending a duplicate, then trims the tail to the capacity.
 * Transient pages ({@code about:}, {@code data:}, {@code javascript:},
 * {@code view-source:}) are never recorded so the history holds real
 * destinations only.</p>
 */
public final class HistoryStore {

    /** The capacity used when none is supplied. */
    public static final int DEFAULT_CAPACITY = BrowserSettings.DEFAULT_HISTORY_LIMIT;

    private final List<HistoryEntry> entries = new ArrayList<>();
    private int capacity;

    /** Creates an empty store with the {@link #DEFAULT_CAPACITY}. */
    public HistoryStore() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates an empty store with an explicit capacity.
     *
     * @param capacity the maximum entries retained; {@code <= 0} falls back to
     *                 {@link #DEFAULT_CAPACITY}
     */
    public HistoryStore(int capacity) {
        this.capacity = (capacity <= 0) ? DEFAULT_CAPACITY : capacity;
    }

    /**
     * Creates a store seeded with a copy of {@code initial}, trimmed to
     * {@code capacity}.
     *
     * @param initial  the starting entries, newest-first (may be null)
     * @param capacity the maximum entries retained
     */
    public HistoryStore(List<HistoryEntry> initial, int capacity) {
        this(capacity);
        if (initial != null) {
            for (HistoryEntry e : initial) {
                if (e != null && !e.getUrl().isBlank()) {
                    entries.add(e.copy());
                }
            }
            trim();
        }
    }

    /**
     * Records a visit, de-duplicating by URL and keeping the list newest-first
     * and within capacity.
     *
     * @param url   the visited URL (transient schemes and blanks are ignored)
     * @param title the page title at visit time (may be null)
     * @return the recorded entry, or null when the URL was skipped
     */
    public HistoryEntry record(String url, String title) {
        if (url == null || url.isBlank() || isTransient(url)) {
            return null;
        }
        String trimmed = url.trim();
        HistoryEntry existing = find(trimmed);
        if (existing != null) {
            entries.remove(existing);
            existing.setVisitedAt(System.currentTimeMillis());
            existing.setVisitCount(existing.getVisitCount() + 1);
            if (title != null && !title.isBlank()) {
                existing.setTitle(title);
            }
            entries.add(0, existing);
            return existing;
        }
        HistoryEntry entry = new HistoryEntry(trimmed, title);
        entries.add(0, entry);
        trim();
        return entry;
    }

    /** @return the entry for {@code url}, or null. */
    public HistoryEntry find(String url) {
        if (url == null) {
            return null;
        }
        for (HistoryEntry e : entries) {
            if (e.getUrl().equals(url)) {
                return e;
            }
        }
        return null;
    }

    /** @return an unmodifiable view of the history, newest-first. */
    public List<HistoryEntry> list() {
        return Collections.unmodifiableList(entries);
    }

    /** @return the number of retained entries. */
    public int size() {
        return entries.size();
    }

    /** @return true when there is no history. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** @return the maximum entries retained. */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Sets the retention capacity, trimming immediately if it shrank.
     *
     * @param capacity the new maximum; {@code <= 0} falls back to the default
     */
    public void setCapacity(int capacity) {
        this.capacity = (capacity <= 0) ? DEFAULT_CAPACITY : capacity;
        trim();
    }

    /** Removes every entry (e.g. "clear history"). */
    public void clear() {
        entries.clear();
    }

    private void trim() {
        while (entries.size() > capacity) {
            entries.remove(entries.size() - 1);
        }
    }

    /** True for schemes that should not clutter the history. */
    private static boolean isTransient(String url) {
        String lower = url.trim().toLowerCase();
        return lower.startsWith("about:")
                || lower.startsWith("data:")
                || lower.startsWith("javascript:")
                || lower.startsWith("view-source:")
                || lower.startsWith("blob:");
    }
}
