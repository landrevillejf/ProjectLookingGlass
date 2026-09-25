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
 * The most-recently-used window list and cycle state machine behind a desktop's
 * window switcher.
 *
 * <p>This is the pure-logic half: it tracks which application window was used
 * most recently ({@link #touch}/{@link #forget}) and, once a cycle session is
 * {@linkplain #open(List) open}, walks a highlight index forward and backward
 * through the MRU-ordered snapshot. It paints nothing and touches no Java 3D,
 * so the cycling behaviour is unit-testable headless; a switcher overlay (the
 * 2D {@link WindowCyclerOverlay}, or the native 3D desktop's HUD switcher)
 * renders whatever this exposes.</p>
 *
 * <p>The class is desktop-agnostic and generic over the window type: the 2D
 * desktop cycles {@code WindowCycler<Desktop2DWindow>} while the native 3D
 * desktop cycles {@code WindowCycler<org.jdesktop.lg3d.wg.Frame3D>}, so both
 * share one MRU/cycle model instead of each re-inventing it.</p>
 *
 * <p>A session opens on the first trigger and highlights index 0 (the window
 * that currently has the focus). The first {@link #advance()} then moves to
 * index 1 &mdash; the window the switcher jumps to first &mdash; and repeated
 * advances walk the list, wrapping. {@link #commit()} returns the highlighted
 * window and closes the session; {@link #cancel()} closes it without selecting
 * anything.</p>
 *
 * <p>Confined to a single thread (the event dispatch thread on the 2D desktop,
 * the lg3d event thread on the 3D one), like the rest of the switcher.</p>
 */
public final class WindowCycler<T> {

    /**
     * Windows ordered least-recently-used first, so the tail is the current
     * window. A {@link LinkedHashSet} gives O(1) remove-on-touch while keeping
     * insertion order.
     */
    private final LinkedHashSet<T> mru = new LinkedHashSet<>();

    private List<T> session = List.of();
    private int index = -1;

    /** Records {@code window} as the most recently used. Null-safe. */
    public void touch(T window) {
        if (window == null) {
            return;
        }
        mru.remove(window);
        mru.add(window);
    }

    /** Drops {@code window} from the MRU history (it closed). Null-safe. */
    public void forget(T window) {
        if (window == null) {
            return;
        }
        mru.remove(window);
    }

    /** Whether a cycle session is currently open. */
    public boolean isActive() {
        return index >= 0;
    }

    /** The MRU-ordered snapshot this session cycles through (empty if none). */
    public List<T> items() {
        return session;
    }

    /** The highlighted index, or {@code -1} when no session is open. */
    public int selectedIndex() {
        return index;
    }

    /** The highlighted window, or null when no session is open. */
    public T selected() {
        return isActive() ? session.get(index) : null;
    }

    /**
     * Opens a cycle over {@code present} (the windows currently on the desktop),
     * ordered most-recently-used first, and highlights index 0. Returns false,
     * leaving the session closed, when there are fewer than two windows &mdash;
     * there is nothing to switch to.
     */
    public boolean open(List<T> present) {
        List<T> ordered = ordered(present);
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
    public List<T> ordered(List<T> present) {
        List<T> source = (present == null) ? List.of() : present;
        List<T> ordered = new ArrayList<>(source.size());
        List<T> tracked = new ArrayList<>(mru);
        // mru holds least-recent first, so walk it backwards for most-recent.
        for (int i = tracked.size() - 1; i >= 0; i--) {
            T window = tracked.get(i);
            if (source.contains(window)) {
                ordered.add(window);
            }
        }
        for (T window : source) {
            if (!ordered.contains(window)) {
                ordered.add(window);
            }
        }
        return ordered;
    }

    /** Moves the highlight one window forward, wrapping. No-op when closed. */
    public void advance() {
        if (isActive()) {
            index = (index + 1) % session.size();
        }
    }

    /** Moves the highlight one window backward, wrapping. No-op when closed. */
    public void advanceBack() {
        if (isActive()) {
            index = (index - 1 + session.size()) % session.size();
        }
    }

    /**
     * Returns the highlighted window and closes the session, or null when no
     * session is open. The caller brings the returned window forward.
     */
    public T commit() {
        T selected = selected();
        cancel();
        return selected;
    }

    /** Closes the session without selecting anything. */
    public void cancel() {
        session = List.of();
        index = -1;
    }
}
