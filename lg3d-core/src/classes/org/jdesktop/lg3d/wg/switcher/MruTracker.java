/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.wg.switcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Tracks most-recently-used order for a set of windows, so the switcher lists
 * the window you were just using first and the one before it second - the
 * ordering Alt+Tab users expect.
 *
 * <p>The tracker only remembers order; it does not hold the authoritative set
 * of open windows. {@link #ordered(Collection)} reconciles the remembered order
 * against the windows that are actually present, dropping any that have closed
 * and appending any that were never tracked. Keys are compared with
 * {@code equals}, which for window objects is identity.</p>
 *
 * <p>Not thread-safe; like the rest of the switcher it is confined to the
 * event dispatch thread.</p>
 */
public final class MruTracker<T> {

    /** Remembered order, most-recently-used first. */
    private final List<T> order = new ArrayList<>();

    /** Marks {@code item} as the most recently used. Null is ignored. */
    public void touch(T item) {
        if (item == null) {
            return;
        }
        order.remove(item);
        order.add(0, item);
    }

    /** Drops {@code item} (for example when its window closes). */
    public void remove(T item) {
        order.remove(item);
    }

    /** Forgets every entry. */
    public void clear() {
        order.clear();
    }

    /**
     * Returns {@code present} ordered most-recently-used first: tracked items
     * that are still present come in remembered order, then any untracked items
     * in the order given. Remembered entries that are no longer present are
     * pruned. A null {@code present} yields an empty list.
     */
    public List<T> ordered(Collection<? extends T> present) {
        List<T> result = new ArrayList<>();
        if (present == null) {
            return result;
        }
        List<T> presentList = new ArrayList<>(present);
        for (T item : order) {
            if (presentList.contains(item) && !result.contains(item)) {
                result.add(item);
            }
        }
        for (T item : presentList) {
            if (!result.contains(item)) {
                result.add(item);
            }
        }
        // Forget windows that have closed so the order cannot grow unbounded.
        order.retainAll(presentList);
        return result;
    }
}
