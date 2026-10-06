/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The multi-window <em>focus and stacking</em> model for a set of composited
 * native windows (Phase C). It is a pure, X-free and Java-3D-free brain: it
 * tracks which windows are alive, their sibling z-order (bottom&rarr;top) and
 * which one has the input focus, under one of two policies.
 *
 * <p>Both the 3D desktop (which orders {@code NativeWindow3D} quads) and the 2D
 * desktop (which orders {@code Desktop2DWindow}s via {@code Desktop2DCompositorHost})
 * can consult the same model, and because it holds no live X or scene-graph state
 * the whole stacking/focus policy is unit-testable headlessly.</p>
 *
 * <h3>Focus policies</h3>
 * <ul>
 *   <li>{@link FocusPolicy#POINTER} — focus follows the pointer: a
 *       {@link #pointerEnter} moves focus to the entered window without
 *       restacking (sloppy focus).</li>
 *   <li>{@link FocusPolicy#CLICK} — click-to-focus: focus moves only on an
 *       explicit {@link #activate}, which also raises the window to the top.</li>
 * </ul>
 *
 * <p>{@link #activate} always focuses and raises regardless of policy (it models
 * an explicit user activation, e.g. a taskbar click or Alt-Tab commit).</p>
 */
public final class CompositedWindowSet {

    /** How input focus moves between composited windows. */
    public enum FocusPolicy {
        /** Focus follows the pointer (sloppy focus). */
        POINTER,
        /** Focus moves only on an explicit activation (click-to-focus). */
        CLICK
    }

    /** Insertion-ordered id&rarr;title; iteration order is bottom&rarr;top of the stack. */
    private final LinkedHashMap<Integer, String> windows = new LinkedHashMap<>();

    /** The focused window id, or null when the set is empty or nothing is focused. */
    private Integer focused;

    private final FocusPolicy policy;

    /**
     * @param policy the focus policy; null defaults to {@link FocusPolicy#POINTER}
     */
    public CompositedWindowSet(FocusPolicy policy) {
        this.policy = (policy != null) ? policy : FocusPolicy.POINTER;
    }

    /** The focus policy this set was built with. */
    public FocusPolicy getFocusPolicy() {
        return policy;
    }

    /**
     * Adds a window at the top of the stack and focuses it. Re-adding an
     * existing window only refreshes its title and raises it.
     *
     * @param windowId the X window id
     * @param title    a human-readable title (may be null)
     */
    public void add(int windowId, String title) {
        windows.remove(windowId); // re-insert at the top (LinkedHashMap keeps order)
        windows.put(windowId, title);
        focused = windowId;
    }

    /**
     * Removes a window. If it held the focus, focus moves to the new top of the
     * stack (or becomes null when the set empties).
     *
     * @param windowId the X window id
     */
    public void remove(int windowId) {
        windows.remove(windowId);
        if (focused != null && focused == windowId) {
            focused = topOrNull();
        }
    }

    /** True if the window is in the set. */
    public boolean contains(int windowId) {
        return windows.containsKey(windowId);
    }

    /** The number of windows in the set. */
    public int size() {
        return windows.size();
    }

    /** The title recorded for a window, or null if unknown/unset. */
    public String titleOf(int windowId) {
        return windows.get(windowId);
    }

    /** Updates a window's title; no-op if the window is unknown. */
    public void retitle(int windowId, String title) {
        if (windows.containsKey(windowId)) {
            windows.put(windowId, title); // value update preserves insertion order
        }
    }

    /** Raises a window to the top of the stack; no-op if unknown. */
    public void raise(int windowId) {
        if (!windows.containsKey(windowId)) {
            return;
        }
        String title = windows.remove(windowId);
        windows.put(windowId, title);
    }

    /** Lowers a window to the bottom of the stack; no-op if unknown. */
    public void lower(int windowId) {
        if (!windows.containsKey(windowId)) {
            return;
        }
        String title = windows.remove(windowId);
        // Re-insert first by rebuilding the map with this window at the bottom.
        LinkedHashMap<Integer, String> rebuilt = new LinkedHashMap<>();
        rebuilt.put(windowId, title);
        rebuilt.putAll(windows);
        windows.clear();
        windows.putAll(rebuilt);
    }

    /**
     * The stack from top to bottom (most-recently-raised first). An unmodifiable
     * snapshot; safe to iterate for painting back-to-front (reverse) or for a
     * window list.
     */
    public List<Integer> stackTopDown() {
        List<Integer> ids = new ArrayList<>(windows.keySet());
        Collections.reverse(ids);
        return Collections.unmodifiableList(ids);
    }

    /** The focused window id, or null if nothing is focused. */
    public Integer focused() {
        return focused;
    }

    /**
     * Explicitly activates a window: focuses it and raises it to the top, under
     * any policy. No-op if the window is unknown.
     *
     * @param windowId the X window id
     */
    public void activate(int windowId) {
        if (!windows.containsKey(windowId)) {
            return;
        }
        raise(windowId);
        focused = windowId;
    }

    /**
     * Records the pointer entering a window. Under {@link FocusPolicy#POINTER}
     * this moves focus (without restacking); under {@link FocusPolicy#CLICK} it
     * is ignored. No-op if the window is unknown.
     *
     * @param windowId the X window id
     */
    public void pointerEnter(int windowId) {
        if (policy == FocusPolicy.POINTER && windows.containsKey(windowId)) {
            focused = windowId;
        }
    }

    /** Removes every window and clears the focus. */
    public void clear() {
        windows.clear();
        focused = null;
    }

    /** The top-most window id, or null when the set is empty. */
    private Integer topOrNull() {
        Integer top = null;
        for (Integer id : windows.keySet()) {
            top = id; // last in iteration order is the top
        }
        return top;
    }

    /** An unmodifiable view of the id&rarr;title map, bottom&rarr;top order. */
    public Map<Integer, String> windowsBottomUp() {
        return Collections.unmodifiableMap(windows);
    }
}
