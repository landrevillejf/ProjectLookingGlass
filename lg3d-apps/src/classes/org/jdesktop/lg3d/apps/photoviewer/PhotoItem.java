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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One photo in the viewer's library: an absolute file path, an optional title,
 * a set of free-form tags and a 0..5 star rating. Items are plain Jackson beans
 * persisted by {@link PhotoViewerStore}, so the class stays serialisable (no-arg
 * constructor plus getters/setters, no image handle - the pixels are loaded on
 * demand by the panel).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PhotoItem {

    /** The maximum star rating. */
    public static final int MAX_RATING = 5;

    private String path = "";
    private String title = "";
    private Set<String> tags = new LinkedHashSet<>();
    private int rating = 0;
    private long addedMillis = System.currentTimeMillis();

    /** No-arg constructor for Jackson. */
    public PhotoItem() {
    }

    /**
     * Convenience constructor.
     *
     * @param path  the absolute file path
     * @param title the display title (blank falls back to the file name)
     */
    public PhotoItem(String path, String title) {
        this.path = (path == null) ? "" : path;
        this.title = (title == null || title.isBlank()) ? deriveTitle(this.path) : title;
    }

    /** Builds an item from a path, titling it from the file name. */
    public static PhotoItem of(String path) {
        return new PhotoItem(path, null);
    }

    /** The file name (minus extension) of {@code path}. */
    static String deriveTitle(String path) {
        if (path == null || path.isBlank()) {
            return "Untitled";
        }
        String s = path.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0 && slash < s.length() - 1) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        return s.isBlank() ? "Untitled" : s;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = (path == null) ? "" : path;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = (title == null || title.isBlank()) ? deriveTitle(path) : title;
    }

    /** The live tag set (never null). */
    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = (tags == null) ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
    }

    /** Adds a tag (blank and duplicate tags are ignored). */
    public boolean addTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        return tags.add(tag.trim());
    }

    /** Removes a tag; true when it was present. */
    public boolean removeTag(String tag) {
        return tag != null && tags.remove(tag.trim());
    }

    /** True when the photo carries {@code tag} (case-sensitive). */
    public boolean hasTag(String tag) {
        return tag != null && tags.contains(tag.trim());
    }

    /** @return the star rating, 0..{@link #MAX_RATING}. */
    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = Math.max(0, Math.min(MAX_RATING, rating));
    }

    public long getAddedMillis() {
        return addedMillis;
    }

    public void setAddedMillis(long addedMillis) {
        this.addedMillis = addedMillis;
    }

    @Override
    public String toString() {
        return (title == null || title.isBlank()) ? deriveTitle(path) : title;
    }
}
