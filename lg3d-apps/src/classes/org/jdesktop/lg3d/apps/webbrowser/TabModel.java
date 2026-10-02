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
 * The tab-strip model: an ordered list of tabs with an active index. It is pure
 * state (no AWT/JavaFX), so the panel can drive the tab strip and the
 * {@code WebView} selection through it and the logic can be unit-tested headless.
 *
 * <p>Each {@link Tab} carries the identity of one {@code WebView} (its title and
 * current URL for the strip label). Closing a tab keeps the active index on a
 * neighbour, and the model never allows zero tabs to be selected while tabs
 * remain.</p>
 */
public final class TabModel {

    /** One browser tab: a stable id plus the display title and current URL. */
    public static final class Tab {
        private final int id;
        private String title = "";
        private String url = "";

        Tab(int id, String title, String url) {
            this.id = id;
            this.title = (title == null) ? "" : title;
            this.url = (url == null) ? "" : url;
        }

        public int getId() { return id; }
        public String getTitle() { return title; }
        public String getUrl() { return url; }

        void setTitle(String title) { this.title = (title == null) ? "" : title; }
        void setUrl(String url) { this.url = (url == null) ? "" : url; }

        /** @return the strip label: the title, else the URL, else a placeholder. */
        public String label() {
            if (!title.isBlank()) {
                return title;
            }
            if (!url.isBlank()) {
                return url;
            }
            return "New Tab";
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private int nextId;

    /** Creates an empty model with no tabs and no active tab. */
    public TabModel() {
    }

    /**
     * Appends a new tab and makes it active.
     *
     * @param title the initial title (may be null)
     * @param url   the initial URL (may be null)
     * @return the index of the new tab
     */
    public int addTab(String title, String url) {
        Tab tab = new Tab(nextId++, title, url);
        tabs.add(tab);
        activeIndex = tabs.size() - 1;
        return activeIndex;
    }

    /**
     * Closes the tab at {@code index}, moving the active index to a neighbour.
     *
     * @param index the tab to close
     * @return true when a tab was removed
     */
    public boolean closeTab(int index) {
        if (index < 0 || index >= tabs.size()) {
            return false;
        }
        tabs.remove(index);
        if (tabs.isEmpty()) {
            activeIndex = -1;
        } else if (index < activeIndex) {
            activeIndex--;
        } else if (index == activeIndex) {
            activeIndex = Math.min(activeIndex, tabs.size() - 1);
        }
        return true;
    }

    /**
     * Selects the tab at {@code index}.
     *
     * @param index the tab to activate
     * @return true when the active tab changed
     */
    public boolean selectTab(int index) {
        if (index < 0 || index >= tabs.size() || index == activeIndex) {
            return false;
        }
        activeIndex = index;
        return true;
    }

    /**
     * Moves a tab from one index to another (drag-to-reorder).
     *
     * @param from the current index
     * @param to   the target index
     * @return true when the tab moved
     */
    public boolean moveTab(int from, int to) {
        if (from < 0 || from >= tabs.size() || to < 0 || to >= tabs.size() || from == to) {
            return false;
        }
        Tab tab = tabs.remove(from);
        tabs.add(to, tab);
        if (activeIndex == from) {
            activeIndex = to;
        } else if (from < activeIndex && to >= activeIndex) {
            activeIndex--;
        } else if (from > activeIndex && to <= activeIndex) {
            activeIndex++;
        }
        return true;
    }

    /**
     * Updates the active tab's title and/or URL after a navigation.
     *
     * @param title the new title (null leaves it unchanged)
     * @param url   the new URL (null leaves it unchanged)
     */
    public void updateActive(String title, String url) {
        Tab tab = getActiveTab();
        if (tab == null) {
            return;
        }
        if (title != null) {
            tab.setTitle(title);
        }
        if (url != null) {
            tab.setUrl(url);
        }
    }

    /** @return the active tab, or null when there are none. */
    public Tab getActiveTab() {
        return (activeIndex >= 0 && activeIndex < tabs.size()) ? tabs.get(activeIndex) : null;
    }

    /** @return the tab at {@code index}, or null when out of range. */
    public Tab getTab(int index) {
        return (index >= 0 && index < tabs.size()) ? tabs.get(index) : null;
    }

    /** @return the active index, or -1 when there are no tabs. */
    public int getActiveIndex() {
        return activeIndex;
    }

    /** @return an unmodifiable view of the tabs, in strip order. */
    public List<Tab> list() {
        return Collections.unmodifiableList(tabs);
    }

    /** @return the number of open tabs. */
    public int size() {
        return tabs.size();
    }

    /** @return true when no tabs are open. */
    public boolean isEmpty() {
        return tabs.isEmpty();
    }
}
