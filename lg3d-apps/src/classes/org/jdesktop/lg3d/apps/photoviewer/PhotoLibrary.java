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
package org.jdesktop.lg3d.apps.photoviewer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The photo viewer's library model: an ordered collection of {@link PhotoItem}s
 * plus the pure query logic the gallery needs (de-duplicated insertion by path,
 * tag / keyword / rating filtering and the union of all tags). AWT-free, so it
 * is unit-testable headless; the panel simply reflects the returned lists.
 */
public class PhotoLibrary {

    private final List<PhotoItem> photos = new ArrayList<>();

    /**
     * Adds a photo unless one with the same path already exists.
     *
     * @return true when the photo was added
     */
    public boolean add(PhotoItem item) {
        if (item == null || item.getPath().isBlank() || findByPath(item.getPath()) != null) {
            return false;
        }
        photos.add(item);
        return true;
    }

    /** Adds every not-yet-present photo of {@code items}. */
    public int addAll(Collection<PhotoItem> items) {
        if (items == null) {
            return 0;
        }
        int added = 0;
        for (PhotoItem item : items) {
            if (add(item)) {
                added++;
            }
        }
        return added;
    }

    /** The photo whose path equals {@code path}, or null. */
    public PhotoItem findByPath(String path) {
        if (path == null) {
            return null;
        }
        for (PhotoItem item : photos) {
            if (path.equals(item.getPath())) {
                return item;
            }
        }
        return null;
    }

    /** Removes the photo at {@code i}; true when something was removed. */
    public boolean remove(int i) {
        if (i < 0 || i >= photos.size()) {
            return false;
        }
        photos.remove(i);
        return true;
    }

    /** Removes the photo with the given path; true when it was present. */
    public boolean removeByPath(String path) {
        PhotoItem item = findByPath(path);
        return item != null && photos.remove(item);
    }

    public void clear() {
        photos.clear();
    }

    public int size() {
        return photos.size();
    }

    public boolean isEmpty() {
        return photos.isEmpty();
    }

    /** The photo at {@code i}, or null when out of range. */
    public PhotoItem get(int i) {
        return (i < 0 || i >= photos.size()) ? null : photos.get(i);
    }

    /** An unmodifiable snapshot of all photos, in insertion order. */
    public List<PhotoItem> all() {
        return List.copyOf(photos);
    }

    /** The union of every photo's tags, in first-seen order. */
    public Set<String> allTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (PhotoItem item : photos) {
            tags.addAll(item.getTags());
        }
        return tags;
    }

    /**
     * The photos matching a filter: those carrying {@code tag} (when non-blank),
     * whose title contains {@code keyword} (case-insensitive, when non-blank),
     * and whose rating is at least {@code minRating}. A blank tag / keyword and
     * a minRating of 0 match everything.
     *
     * @param tag       a required tag, or null/blank for "any"
     * @param keyword   a title substring, or null/blank for "any"
     * @param minRating the minimum star rating (0..5)
     * @return the matching photos, in insertion order
     */
    public List<PhotoItem> filter(String tag, String keyword, int minRating) {
        String t = (tag == null) ? "" : tag.trim();
        String k = (keyword == null) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int min = Math.max(0, minRating);
        List<PhotoItem> out = new ArrayList<>();
        for (PhotoItem item : photos) {
            if (!t.isEmpty() && !item.hasTag(t)) {
                continue;
            }
            if (!k.isEmpty() && !item.getTitle().toLowerCase(Locale.ROOT).contains(k)) {
                continue;
            }
            if (item.getRating() < min) {
                continue;
            }
            out.add(item);
        }
        return out;
    }
}
