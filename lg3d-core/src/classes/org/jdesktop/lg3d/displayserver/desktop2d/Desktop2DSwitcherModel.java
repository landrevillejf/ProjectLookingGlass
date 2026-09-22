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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.wg.switcher.MruTracker;
import org.jdesktop.lg3d.wg.switcher.SwitcherItem;
import org.jdesktop.lg3d.wg.switcher.SwitcherModel;

/**
 * The 2D/Swing desktop's {@link SwitcherModel}: it enumerates the internal
 * frames the desktop hosts ({@link Desktop2DWindow}) in most-recently-used
 * order and activates one by bringing its window forward.
 *
 * <p>Window discovery and activation go through a small {@link WindowSource}
 * seam rather than reaching into {@link Desktop2D} directly, so the ordering and
 * activation logic can be unit-tested headless with fake windows.</p>
 *
 * <p>MRU order is maintained by {@link #touch(Desktop2DWindow)} (called when a
 * window gains the focus) and {@link #forget(Desktop2DWindow)} (when one
 * closes); {@link #items()} reconciles that order against the windows actually
 * open, so a window opened or closed behind the switcher's back is still
 * handled.</p>
 */
final class Desktop2DSwitcherModel implements SwitcherModel {

    /** Supplies the live window set and performs activation. */
    interface WindowSource {
        /** Every application window currently on the desktop, in any order. */
        List<Desktop2DWindow> openWindows();

        /** Brings {@code window} forward and focuses it (no minimise toggle). */
        void focusWindow(Desktop2DWindow window);
    }

    /**
     * The trigger that opens/advances the switcher. Ctrl+Alt+Tab rather than
     * plain Alt+Tab because a windowed lg3d runs under a host window manager
     * (GNOME) that globally grabs Alt+Tab; Ctrl+Alt+Tab reaches us instead. The
     * overlay also binds plain Alt+Tab, which takes over when lg3d owns the
     * display and does receive it.
     */
    static final String TRIGGER = "control alt TAB";

    private final WindowSource source;
    private final MruTracker<Desktop2DWindow> mru = new MruTracker<>();

    Desktop2DSwitcherModel(WindowSource source) {
        this.source = source;
    }

    /** Records {@code window} as the most recently used. */
    void touch(Desktop2DWindow window) {
        mru.touch(window);
    }

    /** Drops a closed {@code window} from the MRU order. */
    void forget(Desktop2DWindow window) {
        mru.remove(window);
    }

    @Override
    public List<SwitcherItem> items() {
        List<Desktop2DWindow> present = source.openWindows();
        if (present == null) {
            present = new ArrayList<>();
        }
        List<Desktop2DWindow> ordered = mru.ordered(present);
        List<SwitcherItem> items = new ArrayList<>(ordered.size());
        for (Desktop2DWindow window : ordered) {
            items.add(new SwitcherItem(window, displayName(window),
                    window.getFrameIcon()));
        }
        return items;
    }

    @Override
    public void activate(SwitcherItem item) {
        if (item == null || !(item.getWindow() instanceof Desktop2DWindow)) {
            return;
        }
        Desktop2DWindow window = (Desktop2DWindow) item.getWindow();
        source.focusWindow(window);
        mru.touch(window);
    }

    @Override
    public String triggerKeySpec() {
        return TRIGGER;
    }

    private static String displayName(Desktop2DWindow window) {
        String title = window.getTitle();
        if (title != null && !title.isBlank()) {
            return title;
        }
        return window.getAppName();
    }
}
