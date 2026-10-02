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

/**
 * One visited page in the browsing history: the URL, the page title at the time
 * of the visit, a visit count and the timestamp of the most recent visit.
 *
 * <p>Held newest-first by {@link HistoryStore} and persisted by
 * {@link BrowserStore}. Re-visiting a URL updates the existing entry (bumping
 * {@code visitedAt} and {@code visitCount}) rather than appending a duplicate, so
 * the history stays a de-duplicated, capped list.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoryEntry {

    private String url = "";
    private String title = "";
    private long visitedAt = System.currentTimeMillis();
    private int visitCount = 1;

    public HistoryEntry() {
    }

    /**
     * Creates a history entry with one visit.
     *
     * @param url   the absolute URL (may be null)
     * @param title the page title (may be null)
     */
    public HistoryEntry(String url, String title) {
        this.url = (url == null) ? "" : url;
        this.title = (title == null) ? "" : title;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = (url == null) ? "" : url; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = (title == null) ? "" : title; }

    public long getVisitedAt() { return visitedAt; }
    public void setVisitedAt(long visitedAt) { this.visitedAt = visitedAt; }

    public int getVisitCount() { return visitCount; }
    public void setVisitCount(int visitCount) { this.visitCount = Math.max(0, visitCount); }

    /** @return an independent copy of this entry. */
    public HistoryEntry copy() {
        HistoryEntry h = new HistoryEntry();
        h.url = this.url;
        h.title = this.title;
        h.visitedAt = this.visitedAt;
        h.visitCount = this.visitCount;
        return h;
    }

    @Override
    public String toString() {
        return (title == null || title.isEmpty()) ? url : title + " - " + url;
    }
}
