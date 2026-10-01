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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link UndoStack}'s push / undo / redo bookkeeping, its bound and
 * the redo-invalidation rule. Pure logic with a String snapshot, so it runs
 * headless.
 */
class UndoStackTest {

    @Test
    @DisplayName("a fresh stack has nothing to undo or redo")
    void emptyByDefault() {
        UndoStack<String> stack = new UndoStack<>();
        assertFalse(stack.canUndo());
        assertFalse(stack.canRedo());
        assertNull(stack.undo("now"));
        assertNull(stack.redo("now"));
    }

    @Test
    @DisplayName("undo restores the last push and feeds redo")
    void undoThenRedo() {
        UndoStack<String> stack = new UndoStack<>();
        stack.push("a");
        stack.push("b");
        assertTrue(stack.canUndo());
        assertEquals("b", stack.undo("c"));
        assertEquals("a", stack.undo("b"));
        assertFalse(stack.canUndo());
        assertTrue(stack.canRedo());
        assertEquals("b", stack.redo("a"));
        assertEquals("c", stack.redo("b"));
        assertFalse(stack.canRedo());
    }

    @Test
    @DisplayName("a new push clears the redo future")
    void pushClearsRedo() {
        UndoStack<String> stack = new UndoStack<>();
        stack.push("a");
        stack.undo("b");
        assertTrue(stack.canRedo());
        stack.push("x");
        assertFalse(stack.canRedo());
    }

    @Test
    @DisplayName("the stack never exceeds its bound")
    void bounded() {
        UndoStack<Integer> stack = new UndoStack<>(3);
        for (int i = 1; i <= 10; i++) {
            stack.push(i);
        }
        assertEquals(3, stack.undoDepth());
        assertEquals(10, stack.undo(0));
        assertEquals(9, stack.undo(0));
        assertEquals(8, stack.undo(0));
        assertFalse(stack.canUndo());
    }

    @Test
    @DisplayName("null pushes are ignored and clear empties everything")
    void nullAndClear() {
        UndoStack<String> stack = new UndoStack<>();
        stack.push(null);
        assertFalse(stack.canUndo());
        stack.push("a");
        stack.clear();
        assertFalse(stack.canUndo());
        assertFalse(stack.canRedo());
    }
}
