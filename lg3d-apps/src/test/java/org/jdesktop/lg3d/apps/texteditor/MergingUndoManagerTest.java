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
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link MergingUndoManager}: a burst of contiguous
 * single-character inserts inside the time window collapses into ONE undo
 * step, while non-contiguous inserts, window expiry and structural edits
 * stay separate. The clock seam ({@link MergingUndoManager#now()}) keeps the
 * window tests deterministic.
 */
class MergingUndoManagerTest {

    /** A manager driven by a fake clock. */
    private static final class TestManager extends MergingUndoManager {

        private long clock;

        TestManager(long windowMillis) {
            super(windowMillis);
        }

        @Override
        long now() {
            return clock;
        }

        void advance(long millis) {
            clock += millis;
        }
    }

    private static DefaultStyledDocument doc(MergingUndoManager undo) {
        DefaultStyledDocument document = new DefaultStyledDocument();
        document.addUndoableEditListener(undo);
        return document;
    }

    private static void type(DefaultStyledDocument document, String text)
            throws BadLocationException {
        for (int i = 0; i < text.length(); i++) {
            document.insertString(document.getLength(),
                    String.valueOf(text.charAt(i)), null);
        }
    }

    private static String text(DefaultStyledDocument document)
            throws BadLocationException {
        return document.getText(0, document.getLength());
    }

    @Test
    @DisplayName("a typed burst is undone as a single step")
    void burstMerges() throws BadLocationException {
        TestManager undo = new TestManager(500);
        DefaultStyledDocument document = doc(undo);
        type(document, "hello");
        undo.closeBurst(); // the panel's canUndo() closes it implicitly

        assertTrue(undo.canUndo());
        undo.undo();
        assertEquals("", text(document), "one undo must erase the burst");
        assertFalse(undo.canUndo());

        assertTrue(undo.canRedo());
        undo.redo();
        assertEquals("hello", text(document));
    }

    @Test
    @DisplayName("expiry of the window starts a new step")
    void windowExpiry() throws BadLocationException {
        TestManager undo = new TestManager(500);
        DefaultStyledDocument document = doc(undo);
        type(document, "ab");
        undo.advance(501); // past the merge window
        type(document, "cd");

        undo.undo();
        assertEquals("ab", text(document), "only the second burst undoes");
        undo.undo();
        assertEquals("", text(document));
    }

    @Test
    @DisplayName("edits inside the window still merge (boundary)")
    void windowBoundary() throws BadLocationException {
        TestManager undo = new TestManager(500);
        DefaultStyledDocument document = doc(undo);
        type(document, "a");
        undo.advance(500); // exactly at the window edge: still merges
        type(document, "b");
        undo.undo();
        assertEquals("", text(document));
    }

    @Test
    @DisplayName("a non-contiguous insert closes the burst")
    void nonContiguous() throws BadLocationException {
        TestManager undo = new TestManager(500);
        DefaultStyledDocument document = doc(undo);
        document.insertString(0, "x", null);
        document.insertString(0, "y", null); // jumps back to offset 0

        undo.undo();
        assertEquals("x", text(document),
                "the second insert is its own step");
        undo.undo();
        assertEquals("", text(document));
    }

    @Test
    @DisplayName("multi-character and removal edits are not merged into bursts")
    void structuralEdits() throws BadLocationException {
        TestManager undo = new TestManager(500);
        DefaultStyledDocument document = doc(undo);
        document.insertString(0, "whole", null); // one multi-char edit
        type(document, "!");
        document.remove(document.getLength() - 1, 1); // removal

        undo.undo(); // undoes the removal
        assertEquals("whole!", text(document));
        undo.undo(); // undoes the "!" burst
        assertEquals("whole", text(document));
        undo.undo(); // undoes the multi-char insert
        assertEquals("", text(document));
    }

    @Test
    @DisplayName("closeBurst and discardAllEdits behave")
    void burstLifecycle() throws BadLocationException {
        TestManager undo = new TestManager(500);
        DefaultStyledDocument document = doc(undo);
        type(document, "abc");

        undo.closeBurst();
        assertTrue(undo.canUndo());
        undo.closeBurst(); // idempotent
        undo.discardAllEdits();
        assertFalse(undo.canUndo());
        assertFalse(undo.canRedo());
        assertDoesNotThrow(undo::discardAllEdits);
    }

    @Test
    @DisplayName("undo/redo with no history is contained")
    void emptyManager() {
        MergingUndoManager undo = new MergingUndoManager();
        assertFalse(undo.canUndo());
        assertFalse(undo.canRedo());
        assertEquals(MergingUndoManager.LIMIT, undo.getLimit());
        assertEquals(500, MergingUndoManager.MERGE_WINDOW_MS);
    }
}
