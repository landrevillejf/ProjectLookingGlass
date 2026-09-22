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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jdesktop.lg3d.wg.switcher.MruTracker;
import org.jdesktop.lg3d.wg.switcher.SwitcherItem;
import org.jdesktop.lg3d.wg.switcher.SwitcherModel;

/**
 * The 3D desktop's {@link SwitcherModel}: it enumerates the open
 * {@code Frame3D} application windows in most-recently-used order and activates
 * one by bringing it to the front of the scene.
 *
 * <p>Like {@code Desktop2DSwitcherModel} on the 2D/Swing desktop, this class is
 * deliberately free of any Java 3D type. Window discovery, naming and
 * activation go through a small {@link WindowSource} seam whose implementation
 * ({@link ApplicationSwitcher3D}) does the scene-graph work, so the MRU
 * ordering and activation logic here can be unit-tested headless with opaque
 * window handles.</p>
 *
 * <p>MRU order is maintained by {@link #touch(Object)} (called when a window is
 * brought to the front) and {@link #forget(Object)} (when one closes);
 * {@link #items()} reconciles that order against the windows actually open, so
 * a window opened or closed behind the switcher's back is still handled.</p>
 */
public final class Frame3DSwitcherModel implements SwitcherModel {

    /**
     * Supplies the live window set and performs activation. The window handle
     * carried by each {@link SwitcherItem} is opaque here (a {@code Frame3D} at
     * runtime); this interface is the only place that knows how to interpret it.
     */
    public interface WindowSource {
        /**
         * Every application window currently on the 3D desktop, in any order,
         * each as a ready-made {@link SwitcherItem} whose {@code getWindow()} is
         * the opaque window handle.
         */
        List<SwitcherItem> openWindows();

        /** Brings the given window handle forward and focuses it. */
        void focusWindow(Object window);
    }

    /**
     * The trigger that opens/advances the switcher. Plain Alt+Tab: on the 3D
     * desktop lg3d owns the display (full-screen, or the X11 compositor) and
     * receives the real key. A windowed dev-mode lg3d runs under a host window
     * manager that globally grabs Alt+Tab; there the switcher simply is not
     * reachable by key until lg3d owns the display.
     */
    static final String TRIGGER = "alt TAB";

    private final WindowSource source;
    private final MruTracker<Object> mru = new MruTracker<>();

    public Frame3DSwitcherModel(WindowSource source) {
        this.source = source;
    }

    /** Records {@code window} as the most recently used. */
    public void touch(Object window) {
        mru.touch(window);
    }

    /** Drops a closed {@code window} from the MRU order. */
    public void forget(Object window) {
        mru.remove(window);
    }

    @Override
    public List<SwitcherItem> items() {
        List<SwitcherItem> present = source.openWindows();
        if (present == null) {
            present = new ArrayList<>();
        }
        // Index the present items by their opaque window handle, then order the
        // handles most-recently-used-first and map back to the items. Identity
        // semantics match the reference equality MruTracker uses.
        Map<Object, SwitcherItem> byHandle = new IdentityHashMap<>();
        List<Object> handles = new ArrayList<>(present.size());
        for (SwitcherItem item : present) {
            byHandle.put(item.getWindow(), item);
            handles.add(item.getWindow());
        }
        List<Object> ordered = mru.ordered(handles);
        List<SwitcherItem> items = new ArrayList<>(ordered.size());
        for (Object handle : ordered) {
            SwitcherItem item = byHandle.get(handle);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    @Override
    public void activate(SwitcherItem item) {
        if (item == null) {
            return;
        }
        source.focusWindow(item.getWindow());
        mru.touch(item.getWindow());
    }

    @Override
    public String triggerKeySpec() {
        return TRIGGER;
    }
}
