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
import java.util.List;

/**
 * The desktop-independent state machine of the application switcher: it holds
 * the current cycle session (the MRU snapshot and the highlighted index) and
 * advances, commits and cancels it. Kept free of any Swing painting so the
 * cycling behaviour can be unit-tested headless; a {@link SwitcherOverlay}
 * renders whatever this controller exposes.
 *
 * <p>A session opens on the first {@link #advance()}, which snapshots
 * {@link SwitcherModel#items()} and highlights index 1 (the window Alt+Tab
 * jumps to first, index 0 being the current one). Repeated advances walk the
 * list, wrapping. {@link #commit()} activates the highlighted window and closes
 * the session; {@link #cancel()} closes it without activating anything.</p>
 *
 * <p>Confined to the event dispatch thread, like the rest of the switcher.</p>
 */
public final class SwitcherController {

    private final SwitcherModel model;

    private List<SwitcherItem> items = new ArrayList<>();
    private int index = -1;
    private boolean active;

    public SwitcherController(SwitcherModel model) {
        this.model = model;
    }

    /** Whether a cycle session is currently open. */
    public boolean isActive() {
        return active;
    }

    /** The MRU snapshot for the open session (empty when inactive). */
    public List<SwitcherItem> items() {
        return items;
    }

    /** The highlighted index, or -1 when inactive. */
    public int getIndex() {
        return index;
    }

    /** The highlighted item, or null when inactive. */
    public SwitcherItem currentItem() {
        return (active && index >= 0 && index < items.size())
                ? items.get(index) : null;
    }

    /**
     * Opens the switcher, or moves the highlight one step forward (wrapping) if
     * it is already open.
     *
     * @return true if there is a session to show; false when fewer than two
     *         windows are open, in which case nothing happens (matching the
     *         usual Alt+Tab behaviour of doing nothing with a single window).
     */
    public boolean advance() {
        if (!active) {
            if (!open()) {
                return false;
            }
            // Index 0 is the current window; Alt+Tab jumps to the next one.
            index = 1 % items.size();
            return true;
        }
        index = (index + 1) % items.size();
        return true;
    }

    /**
     * Opens the switcher, or moves the highlight one step backward (wrapping) if
     * it is already open. The backward counterpart of {@link #advance()}, bound
     * to the trigger with Shift held.
     *
     * @return true if there is a session to show; false when fewer than two
     *         windows are open.
     */
    public boolean advanceBack() {
        if (!active) {
            if (!open()) {
                return false;
            }
            // Stepping back from the current window (index 0) wraps to the last.
            index = items.size() - 1;
            return true;
        }
        index = (index - 1 + items.size()) % items.size();
        return true;
    }

    /** Activates the highlighted window and closes the session. */
    public void commit() {
        if (!active) {
            return;
        }
        SwitcherItem item = currentItem();
        close();
        if (item != null) {
            model.activate(item);
        }
    }

    /** Closes the session without activating anything. */
    public void cancel() {
        close();
    }

    /**
     * Snapshots the model's items and starts a session with the highlight on
     * index 0 (the current window); the caller then steps to the entry it wants.
     * Returns false (leaving the session closed) when there are fewer than two
     * windows to cycle.
     */
    private boolean open() {
        List<SwitcherItem> snapshot = model.items();
        if (snapshot == null || snapshot.size() < 2) {
            return false;
        }
        items = new ArrayList<>(snapshot);
        active = true;
        index = 0;
        return true;
    }

    private void close() {
        active = false;
        index = -1;
        items = new ArrayList<>();
    }
}
