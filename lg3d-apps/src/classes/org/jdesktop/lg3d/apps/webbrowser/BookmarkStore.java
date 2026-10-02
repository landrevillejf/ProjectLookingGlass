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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The in-memory bookmark collection: add / edit / remove / list plus folder and
 * URL lookups. It performs no file I/O &mdash; {@link BrowserStore} loads the
 * list this holds and saves it back, which keeps the collection logic free of
 * AWT, JavaFX and the filesystem so it unit-tests headless.
 *
 * <p>Bookmarks are keyed by their stable {@link Bookmark#getId()}; adding a
 * bookmark whose URL already exists updates that entry rather than creating a
 * duplicate, so "bookmark this page" twice is idempotent.</p>
 */
public final class BookmarkStore {

    private final List<Bookmark> bookmarks = new ArrayList<>();

    /** Creates an empty store. */
    public BookmarkStore() {
    }

    /**
     * Creates a store seeded with a copy of {@code initial}.
     *
     * @param initial the starting bookmarks (may be null)
     */
    public BookmarkStore(List<Bookmark> initial) {
        if (initial != null) {
            for (Bookmark b : initial) {
                if (b != null) {
                    bookmarks.add(b.copy());
                }
            }
        }
    }

    /**
     * Adds a bookmark, or updates the existing one with the same URL.
     *
     * @param title the page title (may be null)
     * @param url   the absolute URL (may be null; a blank URL is ignored)
     * @return the stored bookmark, or null when nothing was added
     */
    public Bookmark add(String title, String url) {
        return add(new Bookmark(title, url), false);
    }

    /**
     * Adds a bookmark to a folder, or updates the existing one with the same URL.
     *
     * @param title  the page title (may be null)
     * @param url    the absolute URL (may be null; a blank URL is ignored)
     * @param folder the slash-separated folder path (may be null)
     * @return the stored bookmark, or null when nothing was added
     */
    public Bookmark add(String title, String url, String folder) {
        return add(new Bookmark(title, url, folder), false);
    }

    /**
     * Adds a bookmark. When one with the same non-blank URL already exists it is
     * updated in place (title/folder refreshed) instead of duplicated.
     *
     * @param bookmark   the bookmark to store (null is ignored)
     * @param keepFolder when true and the URL already exists, retain the existing
     *                   folder instead of overwriting it
     * @return the stored bookmark, or null when {@code bookmark} was null or had
     *         a blank URL
     */
    public Bookmark add(Bookmark bookmark, boolean keepFolder) {
        if (bookmark == null || bookmark.getUrl() == null || bookmark.getUrl().isBlank()) {
            return null;
        }
        Bookmark existing = findByUrl(bookmark.getUrl());
        if (existing != null) {
            if (!bookmark.getTitle().isBlank()) {
                existing.setTitle(bookmark.getTitle());
            }
            if (!keepFolder || existing.getFolder().isBlank()) {
                existing.setFolder(bookmark.getFolder());
            }
            return existing;
        }
        Bookmark stored = bookmark.copy();
        bookmarks.add(stored);
        return stored;
    }

    /**
     * Replaces the bookmark with the same id, or appends it when unknown.
     *
     * @param bookmark the edited bookmark (null is ignored)
     * @return true when the collection changed
     */
    public boolean update(Bookmark bookmark) {
        if (bookmark == null || bookmark.getId() == null) {
            return false;
        }
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).getId().equals(bookmark.getId())) {
                bookmarks.set(i, bookmark.copy());
                return true;
            }
        }
        bookmarks.add(bookmark.copy());
        return true;
    }

    /**
     * Removes the bookmark with the given id.
     *
     * @param id the bookmark id (may be null)
     * @return true when a bookmark was removed
     */
    public boolean remove(String id) {
        if (id == null) {
            return false;
        }
        return bookmarks.removeIf(b -> id.equals(b.getId()));
    }

    /** @return the bookmark with the given id, or null. */
    public Bookmark findById(String id) {
        if (id == null) {
            return null;
        }
        for (Bookmark b : bookmarks) {
            if (id.equals(b.getId())) {
                return b;
            }
        }
        return null;
    }

    /** @return the first bookmark with the given URL, or null. */
    public Bookmark findByUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        for (Bookmark b : bookmarks) {
            if (trimmed.equals(b.getUrl())) {
                return b;
            }
        }
        return null;
    }

    /** @return true when a bookmark for {@code url} already exists. */
    public boolean contains(String url) {
        return findByUrl(url) != null;
    }

    /** @return an unmodifiable view of all bookmarks, in insertion order. */
    public List<Bookmark> list() {
        return Collections.unmodifiableList(bookmarks);
    }

    /**
     * @param folder the folder path (null/empty selects the root)
     * @return an unmodifiable list of the bookmarks in that folder
     */
    public List<Bookmark> inFolder(String folder) {
        String target = (folder == null) ? "" : folder.trim();
        List<Bookmark> out = new ArrayList<>();
        for (Bookmark b : bookmarks) {
            if (target.equals(b.getFolder().trim())) {
                out.add(b);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** @return the distinct, sorted folder paths across all bookmarks. */
    public Set<String> folders() {
        Set<String> out = new LinkedHashSet<>();
        for (Bookmark b : bookmarks) {
            String folder = b.getFolder().trim();
            if (!folder.isEmpty()) {
                out.add(folder);
            }
        }
        return out;
    }

    /** @return the number of bookmarks. */
    public int size() {
        return bookmarks.size();
    }

    /** @return true when there are no bookmarks. */
    public boolean isEmpty() {
        return bookmarks.isEmpty();
    }

    /** Removes every bookmark. */
    public void clear() {
        bookmarks.clear();
    }
}
