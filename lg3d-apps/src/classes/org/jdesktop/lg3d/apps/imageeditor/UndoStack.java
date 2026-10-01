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
package org.jdesktop.lg3d.apps.imageeditor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A bounded undo / redo stack of immutable state snapshots. The caller pushes
 * the <em>pre-edit</em> state before mutating, then {@link #undo(Object)} swaps
 * the current state for the last snapshot (moving the current one onto the redo
 * stack) and vice-versa for {@link #redo(Object)}.
 *
 * <p>Generic and AWT-free, so it is unit-testable headless with any snapshot
 * type (the editor uses {@link EditorDocument}).</p>
 *
 * @param <T> the snapshot type
 */
public final class UndoStack<T> {

    /** Default maximum number of retained undo steps. */
    public static final int DEFAULT_LIMIT = 40;

    private final Deque<T> undo = new ArrayDeque<>();
    private final Deque<T> redo = new ArrayDeque<>();
    private final int limit;

    /** Builds a stack with the {@link #DEFAULT_LIMIT}. */
    public UndoStack() {
        this(DEFAULT_LIMIT);
    }

    /**
     * Builds a stack retaining at most {@code limit} undo steps.
     *
     * @param limit the maximum depth (at least 1)
     */
    public UndoStack(int limit) {
        this.limit = Math.max(1, limit);
    }

    /**
     * Records {@code state} as the point to return to on undo, and clears the
     * redo stack (a new edit invalidates the redo future).
     *
     * @param state the pre-edit snapshot (null is ignored)
     */
    public void push(T state) {
        if (state == null) {
            return;
        }
        undo.addLast(state);
        while (undo.size() > limit) {
            undo.removeFirst();
        }
        redo.clear();
    }

    /**
     * Steps back: returns the last pushed snapshot and moves {@code current}
     * onto the redo stack.
     *
     * @param current the state being left
     * @return the restored snapshot, or null when there is nothing to undo
     */
    public T undo(T current) {
        if (undo.isEmpty()) {
            return null;
        }
        T state = undo.removeLast();
        if (current != null) {
            redo.addLast(current);
        }
        return state;
    }

    /**
     * Steps forward: returns the last undone snapshot and moves {@code current}
     * back onto the undo stack.
     *
     * @param current the state being left
     * @return the restored snapshot, or null when there is nothing to redo
     */
    public T redo(T current) {
        if (redo.isEmpty()) {
            return null;
        }
        T state = redo.removeLast();
        if (current != null) {
            undo.addLast(current);
        }
        return state;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** The number of retained undo steps. */
    public int undoDepth() {
        return undo.size();
    }

    /** The number of retained redo steps. */
    public int redoDepth() {
        return redo.size();
    }

    /** Drops all history. */
    public void clear() {
        undo.clear();
        redo.clear();
    }
}
