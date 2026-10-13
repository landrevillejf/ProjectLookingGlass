/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a (videoplayer): the ordered media list.
 *
 * The 2006 module kept a JMF PullDataSource sequence; the modern equivalent is
 * this plain ordered catalog with a cursor. It owns only ordering and the
 * current index — the transport owns playback — so the navigation walk (wrap
 * at the ends, the empty-list refusal, remove-current leaving the cursor on the
 * next item) is a pure, fully tested decision. A duration is carried per item
 * so a transport can be pre-loaded with the known length before the surface
 * discovers it.
 */
package org.jdesktop.wonderland.modules.videoplayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An ordered list of media items with a moving cursor. */
public final class Playlist {

    /** One entry: its locator and the best-known duration. */
    public static final class Item {
        private final String uri;
        private final long durationMs;

        Item(String uri, long durationMs) {
            this.uri = uri;
            this.durationMs = Math.max(0, durationMs);
        }

        public String uri() {
            return uri;
        }

        public long durationMs() {
            return durationMs;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int cursor = -1; // no current until the first add or an explicit move

    /**
     * Append an item. A null/empty uri is refused.
     *
     * @return the zero-based index of the new item, or -1 when refused
     */
    public int add(String uri, long durationMs) {
        if (uri == null || uri.isEmpty()) {
            return -1;
        }
        items.add(new Item(uri, durationMs));
        if (cursor < 0) {
            cursor = 0; // the first item becomes current
        }
        return items.size() - 1;
    }

    /** Number of items. */
    public int size() {
        return items.size();
    }

    /** Whether the playlist holds nothing. */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** An unmodifiable snapshot of the items, in order. */
    public List<Item> items() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** The current index, or -1 when the playlist is empty. */
    public int cursor() {
        return cursor;
    }

    /** The current item, or null when empty. */
    public Item current() {
        return cursor >= 0 && cursor < items.size() ? items.get(cursor) : null;
    }

    /**
     * Move the cursor to an explicit index, clamped to the valid range.
     *
     * @return the resulting index, or -1 when the playlist is empty
     */
    public int moveTo(int index) {
        if (items.isEmpty()) {
            cursor = -1;
            return -1;
        }
        cursor = Math.max(0, Math.min(index, items.size() - 1));
        return cursor;
    }

    /**
     * Advance to the next item, wrapping to the first at the end.
     *
     * @return the new current item, or null when the playlist is empty
     */
    public Item next() {
        if (items.isEmpty()) {
            cursor = -1;
            return null;
        }
        cursor = (cursor + 1) % items.size();
        return items.get(cursor);
    }

    /**
     * Step back to the previous item, wrapping to the last at the front.
     *
     * @return the new current item, or null when the playlist is empty
     */
    public Item previous() {
        if (items.isEmpty()) {
            cursor = -1;
            return null;
        }
        cursor = (cursor - 1 + items.size()) % items.size();
        return items.get(cursor);
    }

    /**
     * Remove the item at an index. When the removed item was the current one,
     * the cursor falls onto the item that took its place (or the last item when
     * the tail was removed); it never points past the end.
     *
     * @return true when an item was removed
     */
    public boolean removeAt(int index) {
        if (index < 0 || index >= items.size()) {
            return false;
        }
        items.remove(index);
        if (items.isEmpty()) {
            cursor = -1;
        } else if (index < cursor) {
            cursor--;            // an earlier item shifted the cursor left
        } else if (index == cursor) {
            cursor = Math.min(cursor, items.size() - 1); // stay on the successor
        }
        return true;
    }

    /** Drop every item and reset the cursor. */
    public void clear() {
        items.clear();
        cursor = -1;
    }
}
