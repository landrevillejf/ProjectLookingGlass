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

import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

/**
 * The editor's undo stack: a bounded {@link UndoManager} that merges a burst
 * of single-character typing into one undo step, so Ctrl+Z removes the word
 * you just typed rather than one letter at a time, while every other edit
 * (paste, replace-all, delete, programmatic insert) stays atomic on its own.
 *
 * <p>Merging rule: consecutive single-character INSERT edits at contiguous
 * offsets, each arriving within {@link #MERGE_WINDOW_MS} of the previous one,
 * are folded into a {@link CompoundEdit}. Anything else &mdash; a pause, a
 * caret jump, a multi-character insert &mdash; closes the burst. The time
 * window is injectable so tests run deterministically without sleeping.</p>
 */
public class MergingUndoManager extends UndoManager {

    /** Typing bursts slower than one char per half second are not merged. */
    public static final long MERGE_WINDOW_MS = 500;

    /** How many undo steps are kept per open document. */
    public static final int LIMIT = 500;

    private final long windowMillis;
    private CompoundEdit burst;
    private long burstLastEditAt;
    private int burstNextOffset = -1;

    public MergingUndoManager() {
        this(MERGE_WINDOW_MS);
    }

    MergingUndoManager(long windowMillis) {
        this.windowMillis = windowMillis;
        setLimit(LIMIT);
    }

    @Override
    public synchronized boolean addEdit(UndoableEdit anEdit) {
        if (anEdit == null) {
            return false;
        }
        if (isMergeable(anEdit)) {
            if (burst == null) {
                burst = new CompoundEdit();
                if (!super.addEdit(burst)) {
                    burst = null;
                    return false;
                }
            }
            burst.addEdit(anEdit);
            burstLastEditAt = now();
            burstNextOffset = nextOffsetOf(anEdit);
            return true;
        }
        closeBurst();
        return super.addEdit(anEdit);
    }

    @Override
    public synchronized void undo() throws javax.swing.undo.CannotUndoException {
        closeBurst();
        super.undo();
    }

    @Override
    public synchronized void redo() throws javax.swing.undo.CannotRedoException {
        closeBurst();
        super.redo();
    }

    /** Ends the current typing burst so it becomes a single undo step. */
    public synchronized void closeBurst() {
        if (burst != null) {
            burst.end();
            burst = null;
            burstNextOffset = -1;
        }
    }

    @Override
    public synchronized void discardAllEdits() {
        closeBurst();
        super.discardAllEdits();
    }

    /**
     * Mergeable = a one-character insert continuing the current burst
     * position within the time window. The edit type and offsets are read
     * from the standard {@code DefaultDocumentEvent} through its
     * {@link UndoableEdit} presentation, so no Swing-internal types leak
     * into the signature.
     */
    private boolean isMergeable(UndoableEdit edit) {
        if (!(edit instanceof javax.swing.text.AbstractDocument.DefaultDocumentEvent dde)) {
            return false;
        }
        if (dde.getType() != javax.swing.event.DocumentEvent.EventType.INSERT
                || dde.getLength() != 1) {
            return false;
        }
        long now = now();
        if (burst != null) {
            if (now - burstLastEditAt > windowMillis) {
                // The pause closes the burst even when the caret stayed
                // contiguous: the next keystroke starts a fresh undo step.
                closeBurst();
                return true;
            }
            return dde.getOffset() == burstNextOffset;
        }
        // No burst in progress: any single-character insert may start one.
        return true;
    }

    private int nextOffsetOf(UndoableEdit edit) {
        javax.swing.text.AbstractDocument.DefaultDocumentEvent dde =
                (javax.swing.text.AbstractDocument.DefaultDocumentEvent) edit;
        return dde.getOffset() + dde.getLength();
    }

    /** Injectable clock seam: tests override to control the merge window. */
    long now() {
        return System.currentTimeMillis();
    }
}
