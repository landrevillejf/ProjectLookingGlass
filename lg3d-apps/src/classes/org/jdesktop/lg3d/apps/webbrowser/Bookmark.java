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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * A saved web page: a title, an absolute URL and an optional folder path so the
 * bookmarks menu can group entries (e.g. {@code "/Work/Docs"}).
 *
 * <p>A plain Jackson bean persisted by {@link BrowserStore} and held in memory by
 * {@link BookmarkStore}. The {@code id} is stable across edits so a bookmark can
 * be renamed or moved without losing its identity.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Bookmark {

    private String id = UUID.randomUUID().toString();
    private String title = "";
    private String url = "";
    /** Slash-separated folder path; empty means the bookmarks root. */
    private String folder = "";
    private long addedAt = System.currentTimeMillis();

    public Bookmark() {
    }

    /**
     * Creates a bookmark with a title and URL in the root folder.
     *
     * @param title the display title (may be null)
     * @param url   the absolute URL (may be null)
     */
    public Bookmark(String title, String url) {
        this(title, url, "");
    }

    /**
     * Creates a bookmark with a title, URL and folder.
     *
     * @param title  the display title (may be null)
     * @param url    the absolute URL (may be null)
     * @param folder the slash-separated folder path (may be null)
     */
    public Bookmark(String title, String url, String folder) {
        this.title = (title == null) ? "" : title;
        this.url = (url == null) ? "" : url;
        this.folder = (folder == null) ? "" : folder;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = (title == null) ? "" : title; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = (url == null) ? "" : url; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = (folder == null) ? "" : folder; }

    public long getAddedAt() { return addedAt; }
    public void setAddedAt(long addedAt) { this.addedAt = addedAt; }

    /** @return an independent copy of this bookmark. */
    public Bookmark copy() {
        Bookmark b = new Bookmark();
        b.id = this.id;
        b.title = this.title;
        b.url = this.url;
        b.folder = this.folder;
        b.addedAt = this.addedAt;
        return b;
    }

    @Override
    public String toString() {
        return (title == null || title.isEmpty()) ? url : title;
    }
}
