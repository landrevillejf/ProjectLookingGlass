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
package org.jdesktop.lg3d.apps.audioplayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The audio player's ordered library and playback cursor: an AWT-free model of
 * "what plays next". All of the ordering logic (sequential advance, wrap-around
 * under <em>repeat</em>, random selection under <em>shuffle</em>) lives here so
 * it is unit-testable without a sound device or a display; the panel simply
 * reflects this state.
 *
 * <p>Shuffle draws from an injected {@link Random}, so a test can seed it and
 * assert a deterministic order. The current index is {@code -1} when nothing is
 * selected.</p>
 */
public class Playlist {

    private final List<MediaItem> items = new ArrayList<>();
    private final Random random;
    private int index = -1;
    private boolean repeat;
    private boolean shuffle;

    /** Builds a playlist with a default random source. */
    public Playlist() {
        this(new Random());
    }

    /**
     * Builds a playlist with an explicit random source (package-private so a
     * test can seed the shuffle order).
     *
     * @param random the source {@link #next()} uses when shuffling
     */
    Playlist(Random random) {
        this.random = (random == null) ? new Random() : random;
    }

    // ------------------------------------------------------------------
    // Contents
    // ------------------------------------------------------------------

    /** Appends a non-null item. */
    public void add(MediaItem item) {
        if (item != null) {
            items.add(item);
        }
    }

    /** Appends every non-null item of {@code more}. */
    public void addAll(Collection<MediaItem> more) {
        if (more != null) {
            for (MediaItem item : more) {
                add(item);
            }
        }
    }

    /**
     * Removes the item at {@code i}, keeping the cursor on the same neighbour.
     *
     * @return true if an item was removed
     */
    public boolean remove(int i) {
        if (i < 0 || i >= items.size()) {
            return false;
        }
        items.remove(i);
        if (items.isEmpty()) {
            index = -1;
        } else if (i < index) {
            index--;
        } else if (i == index) {
            index = Math.min(index, items.size() - 1);
        }
        return true;
    }

    /** Drops every item and clears the cursor. */
    public void clear() {
        items.clear();
        index = -1;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** The item at {@code i}, or null if out of range. */
    public MediaItem get(int i) {
        return (i < 0 || i >= items.size()) ? null : items.get(i);
    }

    /** An unmodifiable snapshot of the items, in order. */
    public List<MediaItem> items() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    // ------------------------------------------------------------------
    // Cursor
    // ------------------------------------------------------------------

    /** The selected index, or {@code -1} when nothing is selected. */
    public int currentIndex() {
        return index;
    }

    /** The selected item, or null when nothing is selected. */
    public MediaItem current() {
        return get(index);
    }

    /**
     * Selects {@code i} (clamped to the valid range) and returns that item.
     *
     * @return the newly selected item, or null when the playlist is empty
     */
    public MediaItem selectIndex(int i) {
        if (items.isEmpty()) {
            index = -1;
            return null;
        }
        index = Math.max(0, Math.min(i, items.size() - 1));
        return items.get(index);
    }

    /**
     * Advances to the next item and returns it. Under shuffle a different
     * random index is chosen; otherwise the cursor moves forward one, wrapping
     * to the start only when {@link #isRepeat()} is set.
     *
     * @return the next item, or null at the end of a non-repeating playlist
     */
    public MediaItem next() {
        if (items.isEmpty()) {
            index = -1;
            return null;
        }
        if (shuffle) {
            index = randomOtherIndex();
            return items.get(index);
        }
        int nextIndex = index + 1;
        if (nextIndex >= items.size()) {
            if (!repeat) {
                return null;
            }
            nextIndex = 0;
        }
        index = nextIndex;
        return items.get(index);
    }

    /**
     * Steps back to the previous item and returns it, wrapping to the end only
     * when {@link #isRepeat()} is set. Shuffle steps back sequentially (a random
     * "previous" is meaningless).
     *
     * @return the previous item, or null at the start of a non-repeating list
     */
    public MediaItem previous() {
        if (items.isEmpty()) {
            index = -1;
            return null;
        }
        int prev = index - 1;
        if (prev < 0) {
            if (!repeat) {
                return null;
            }
            prev = items.size() - 1;
        }
        index = prev;
        return items.get(index);
    }

    /** A random index in range, avoiding the current one when possible. */
    private int randomOtherIndex() {
        if (items.size() == 1) {
            return 0;
        }
        int pick = random.nextInt(items.size() - 1);
        return (pick >= index) ? pick + 1 : pick;
    }

    // ------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------

    public boolean isRepeat() {
        return repeat;
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public void setShuffle(boolean shuffle) {
        this.shuffle = shuffle;
    }
}
