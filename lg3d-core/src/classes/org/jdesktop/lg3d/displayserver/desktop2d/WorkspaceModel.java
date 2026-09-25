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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The pure model of multiple workspaces (virtual desktops) on the 2D/Swing
 * desktop: a fixed number of workspaces, a current one, and which window lives
 * where.
 *
 * <p>Windows are identified by a stable string id — the 2D shell uses
 * {@link Desktop2DWindow#getAppName()}, which is unique among the open windows —
 * so the model holds no Swing and no window references and stays deterministic
 * and headless-testable. {@link Desktop2D} owns an instance and drives the MDI
 * frame visibility, the taskbar window buttons and the {@link WorkspacePager}
 * from it; the model itself never touches the desktop.</p>
 *
 * <p>Because it is desktop-agnostic, the native 3D desktop reuses this same
 * model: {@code org.jdesktop.lg3d.scenemanager.utils.workspace.WorkspaceRegistry}
 * keys it by {@link org.jdesktop.lg3d.wg.Frame3D} identity instead of MDI frame
 * name and drives {@code Frame3D} visibility from it, so both desktops offer the
 * same workspace count, wrapping and assignment semantics.</p>
 *
 * <p>Indexes are 0-based and always wrapped into range, so {@code switchTo},
 * {@code next} and {@code previous} can never select a workspace that does not
 * exist. The count is clamped to {@link #MIN_COUNT}..{@link #MAX_COUNT};
 * shrinking it pulls any out-of-range assignment and the current index back into
 * range rather than dropping windows.</p>
 */
public final class WorkspaceModel {

    /** How many workspaces a fresh desktop offers by default. */
    public static final int DEFAULT_COUNT = 4;
    /** Fewest workspaces allowed (a single workspace disables paging). */
    public static final int MIN_COUNT = 1;
    /** Most workspaces allowed, matching the single-digit move shortcuts. */
    public static final int MAX_COUNT = 9;

    /** Number of workspaces. */
    private int count;

    /** Index of the workspace currently shown. */
    private int current;

    /** Window id → workspace index, in assignment order. */
    private final Map<String, Integer> assignment = new LinkedHashMap<>();

    /**
     * Builds a model with {@code count} workspaces (clamped), showing the first.
     */
    public WorkspaceModel(int count) {
        this.count = clampCount(count);
        this.current = 0;
    }

    /** Constrains a requested count into {@link #MIN_COUNT}..{@link #MAX_COUNT}. */
    static int clampCount(int count) {
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
    }

    /** How many workspaces there are. */
    public int count() {
        return count;
    }

    /** The index of the workspace currently shown. */
    public int current() {
        return current;
    }

    /**
     * Changes the number of workspaces (clamped). The current index and every
     * assignment are pulled back into range, so no window is stranded on a
     * workspace that no longer exists.
     */
    void setCount(int newCount) {
        this.count = clampCount(newCount);
        if (current >= count) {
            current = count - 1;
        }
        for (Map.Entry<String, Integer> entry : assignment.entrySet()) {
            if (entry.getValue() >= count) {
                entry.setValue(count - 1);
            }
        }
    }

    /**
     * Shows the workspace at {@code index} (wrapped into range) and returns the
     * new current index.
     */
    public int switchTo(int index) {
        current = Math.floorMod(index, count);
        return current;
    }

    /** Shows the next workspace, wrapping to the first, and returns its index. */
    public int next() {
        return switchTo(current + 1);
    }

    /** Shows the previous workspace, wrapping to the last, and returns its index. */
    public int previous() {
        return switchTo(current - 1);
    }

    /**
     * Puts {@code windowId} on the workspace at {@code index} (wrapped). A null
     * id is ignored; re-assigning moves the window rather than duplicating it.
     */
    public void assign(String windowId, int index) {
        if (windowId == null) {
            return;
        }
        assignment.put(windowId, Math.floorMod(index, count));
    }

    /**
     * Removes {@code windowId} (e.g. its window closed). Forgets nothing else;
     * an unknown or null id is a no-op.
     */
    public void unassign(String windowId) {
        if (windowId != null) {
            assignment.remove(windowId);
        }
    }

    /** The workspace {@code windowId} is on, or -1 when it is not assigned. */
    public int workspaceOf(String windowId) {
        Integer index = (windowId == null) ? null : assignment.get(windowId);
        return (index == null) ? -1 : index;
    }

    /** True when {@code windowId} sits on the workspace currently shown. */
    public boolean isOnCurrent(String windowId) {
        return workspaceOf(windowId) == current;
    }

    /**
     * The window ids on the workspace at {@code index} (wrapped), in assignment
     * order. Never null; empty when that workspace holds nothing.
     */
    public Set<String> windowsOn(int index) {
        int target = Math.floorMod(index, count);
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : assignment.entrySet()) {
            if (entry.getValue() == target) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** How many windows sit on the workspace at {@code index} (wrapped). */
    public int countOn(int index) {
        return windowsOn(index).size();
    }

    /** An unmodifiable view of every assignment, for diagnostics and tests. */
    Map<String, Integer> assignments() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(assignment));
    }
}
