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
import java.util.Collections;
import java.util.List;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;

/**
 * The ordered, persisted list of application shortcuts pinned to the 2D
 * desktop's taskbar - the 2D counterpart of the 3D taskbar's left-aligned
 * {@code shortcuts} strip.
 *
 * <p>In the native 3D desktop every available {@code ApplicationDescription}
 * posts a {@code Pseudo3DShortcut} onto the taskbar's {@code shortcuts}
 * container, and a right-click removes it again (see {@code GlassyTaskbar}'s
 * {@code RemoveTaskbarItemAction}). This model offers the same affordances in
 * 2D: an application is {@linkplain #pin(ItemSpec) pinned} to the strip,
 * {@linkplain #unpin(String) un-pinned} from it and {@linkplain #move(int, int)
 * reordered} within it, and each shortcut launches through the very same
 * {@link Desktop2D#openApp} path the start menu uses. Because the bar has less
 * room than the 3D shelf, the set is user-curated rather than "every app":
 * {@link Desktop2D} seeds a small default set on first run and the user pins or
 * un-pins from then on.</p>
 *
 * <p>Identity is the start-menu {@code command} (the launch string), so an
 * application can only be pinned once and un-pinning is exact; the display name
 * and icon ride along in the {@link QuickLaunchEntry} so a pin survives even
 * when its command is no longer in the start-menu model. Every mutation is
 * written straight through the {@link QuickLaunchStore} and announced to the
 * registered listeners (the taskbar rebuilds its strip), matching the
 * observable-model convention used by {@code NotificationModel} and
 * {@code DoNotDisturb}.</p>
 *
 * <p>The model holds no Swing and no Java 3D - just entries, a store seam and
 * {@code Runnable} listeners - so pin/un-pin/reorder, the de-dup rule, the cap
 * and the persistence round trip are all unit-testable headless against an
 * in-memory store.</p>
 */
final class QuickLaunchModel {

    /** Longest pinned list kept; a guard against unbounded growth. */
    static final int MAX_ENTRIES = 24;

    /** The pinned shortcuts, in bar order (left-most first). */
    private final List<QuickLaunchEntry> entries = new ArrayList<>();

    /** Where the list is persisted; a seam so tests can inject an in-memory fake. */
    private final QuickLaunchStore store;

    /** Callbacks run on every mutation, so the taskbar strip stays in sync. */
    private final List<Runnable> listeners = new ArrayList<>();

    /** Uses the preferences-backed store. */
    QuickLaunchModel() {
        this(new PrefsQuickLaunchStore());
    }

    /** Uses an explicit store and loads whatever it already holds. */
    QuickLaunchModel(QuickLaunchStore store) {
        this.store = store;
        List<QuickLaunchEntry> saved = store.load();
        if (saved != null) {
            for (QuickLaunchEntry entry : saved) {
                if (entry != null && !entry.command().isBlank()
                        && indexOfCommand(entry.command()) < 0) {
                    entries.add(entry);
                }
            }
        }
        trim();
    }

    /** The pinned entries, in bar order, as an unmodifiable snapshot. */
    List<QuickLaunchEntry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    int size() {
        return entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** True when an entry with this launch command is already pinned. */
    boolean isPinned(String command) {
        return indexOfCommand(command) >= 0;
    }

    /**
     * Pins a start-menu application to the end of the strip. A null item, a
     * blank command or an already-pinned command is ignored (returns false);
     * otherwise the entry is appended, the list is trimmed to
     * {@link #MAX_ENTRIES}, the change is persisted and announced, and true is
     * returned.
     */
    boolean pin(ItemSpec item) {
        if (item == null || item.getCommand() == null
                || item.getCommand().isBlank()) {
            return false;
        }
        if (isPinned(item.getCommand())) {
            return false;
        }
        entries.add(QuickLaunchEntry.of(item));
        trim();
        persist();
        fireChange();
        return true;
    }

    /**
     * Un-pins the entry with this launch command (the 2D counterpart of the 3D
     * taskbar's right-click "remove"). Returns true when something was removed.
     */
    boolean unpin(String command) {
        int index = indexOfCommand(command);
        if (index < 0) {
            return false;
        }
        entries.remove(index);
        persist();
        fireChange();
        return true;
    }

    /**
     * Moves the entry at {@code from} to {@code to}, mirroring the reorderable
     * 3D {@code shortcuts} strip. Both indexes are clamped into range; a no-op
     * (same index, or an empty list) returns false without persisting.
     */
    boolean move(int from, int to) {
        if (entries.isEmpty()) {
            return false;
        }
        int source = Math.max(0, Math.min(from, entries.size() - 1));
        int target = Math.max(0, Math.min(to, entries.size() - 1));
        if (source == target) {
            return false;
        }
        QuickLaunchEntry entry = entries.remove(source);
        entries.add(target, entry);
        persist();
        fireChange();
        return true;
    }

    /** Un-pins everything and forgets the persisted list. */
    void clear() {
        entries.clear();
        store.clear();
        fireChange();
    }

    /** True once the desktop has written its first-run default set. */
    boolean isSeeded() {
        return store.isSeeded();
    }

    /** Records that the default set has been seeded, so it is not repeated. */
    void markSeeded() {
        store.markSeeded();
    }

    /** Registers a callback invoked on every mutation. */
    void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Deregisters a previously added callback. */
    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    private void persist() {
        store.save(entries());
    }

    private int indexOfCommand(String command) {
        if (command == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (command.equals(entries.get(i).command())) {
                return i;
            }
        }
        return -1;
    }

    private void fireChange() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }
}
