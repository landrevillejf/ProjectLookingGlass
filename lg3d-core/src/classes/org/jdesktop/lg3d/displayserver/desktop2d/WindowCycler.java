/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The most-recently-used window list and cycle state machine behind the 2D
 * desktop's window switcher.
 *
 * <p>This is the pure-logic half: it tracks which application window was used
 * most recently ({@link #touch}/{@link #forget}) and, once a cycle session is
 * {@linkplain #open(List) open}, walks a highlight index forward and backward
 * through the MRU-ordered snapshot. It paints nothing and touches no Java 3D,
 * so the cycling behaviour is unit-testable headless; {@link WindowCyclerOverlay}
 * renders whatever this exposes.</p>
 *
 * <p>A session opens on the first trigger and highlights index 0 (the window
 * that currently has the focus). The first {@link #advance()} then moves to
 * index 1 &mdash; the window the switcher jumps to first &mdash; and repeated
 * advances walk the list, wrapping. {@link #commit()} returns the highlighted
 * window and closes the session; {@link #cancel()} closes it without selecting
 * anything.</p>
 *
 * <p>Confined to the event dispatch thread, like the rest of the switcher.</p>
 */
final class WindowCycler {

    /**
     * Windows ordered least-recently-used first, so the tail is the current
     * window. A {@link LinkedHashSet} gives O(1) remove-on-touch while keeping
     * insertion order.
     */
    private final LinkedHashSet<Desktop2DWindow> mru = new LinkedHashSet<>();

    private List<Desktop2DWindow> session = List.of();
    private int index = -1;

    /** Records {@code window} as the most recently used. Null-safe. */
    void touch(Desktop2DWindow window) {
        if (window == null) {
            return;
        }
        mru.remove(window);
        mru.add(window);
    }

    /** Drops {@code window} from the MRU history (it closed). Null-safe. */
    void forget(Desktop2DWindow window) {
        if (window == null) {
            return;
        }
        mru.remove(window);
    }

    /** Whether a cycle session is currently open. */
    boolean isActive() {
        return index >= 0;
    }

    /** The MRU-ordered snapshot this session cycles through (empty if none). */
    List<Desktop2DWindow> items() {
        return session;
    }

    /** The highlighted index, or {@code -1} when no session is open. */
    int selectedIndex() {
        return index;
    }

    /** The highlighted window, or null when no session is open. */
    Desktop2DWindow selected() {
        return isActive() ? session.get(index) : null;
    }

    /**
     * Opens a cycle over {@code present} (the windows currently on the desktop),
     * ordered most-recently-used first, and highlights index 0. Returns false,
     * leaving the session closed, when there are fewer than two windows &mdash;
     * there is nothing to switch to.
     */
    boolean open(List<Desktop2DWindow> present) {
        List<Desktop2DWindow> ordered = ordered(present);
        if (ordered.size() < 2) {
            cancel();
            return false;
        }
        session = ordered;
        index = 0;
        return true;
    }

    /**
     * Orders {@code present} most-recently-used first: the tracked windows that
     * are still present (most recent leading), then any present window that was
     * never tracked, appended in the given order. The result is a snapshot; it
     * never mutates the MRU history.
     */
    List<Desktop2DWindow> ordered(List<Desktop2DWindow> present) {
        List<Desktop2DWindow> source = (present == null) ? List.of() : present;
        List<Desktop2DWindow> ordered = new ArrayList<>(source.size());
        List<Desktop2DWindow> tracked = new ArrayList<>(mru);
        // mru holds least-recent first, so walk it backwards for most-recent.
        for (int i = tracked.size() - 1; i >= 0; i--) {
            Desktop2DWindow window = tracked.get(i);
            if (source.contains(window)) {
                ordered.add(window);
            }
        }
        for (Desktop2DWindow window : source) {
            if (!ordered.contains(window)) {
                ordered.add(window);
            }
        }
        return ordered;
    }

    /** Moves the highlight one window forward, wrapping. No-op when closed. */
    void advance() {
        if (isActive()) {
            index = (index + 1) % session.size();
        }
    }

    /** Moves the highlight one window backward, wrapping. No-op when closed. */
    void advanceBack() {
        if (isActive()) {
            index = (index - 1 + session.size()) % session.size();
        }
    }

    /**
     * Returns the highlighted window and closes the session, or null when no
     * session is open. The caller brings the returned window forward.
     */
    Desktop2DWindow commit() {
        Desktop2DWindow selected = selected();
        cancel();
        return selected;
    }

    /** Closes the session without selecting anything. */
    void cancel() {
        session = List.of();
        index = -1;
    }
}
