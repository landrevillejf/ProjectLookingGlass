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
package org.jdesktop.lg3d.apps.imagestudio;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The editing state for the Image Studio: the original and current images, a
 * bounded undo/redo history, the current file {@link Path}, and a dirty flag.
 *
 * <p>All pixel work is delegated to {@link JaiProcessor} through the {@link Op}
 * callback, so the model never imports JAI directly. Views (canvas, histogram,
 * toolbar, status line) register as {@link Listener}s and are notified after
 * every change.</p>
 *
 * <p>Two edit modes are supported:</p>
 * <ul>
 *   <li>{@link #apply} - a one-shot op (a toolbar button): pushes one undo
 *       entry and commits immediately.</li>
 *   <li>{@link #beginContinuousEdit} / {@link #preview} / {@link #endContinuousEdit}
 *       - live dragging (the 3D slider): the op is re-applied to a fixed
 *       baseline on each tick <em>without</em> growing the history, and a single
 *       undo entry is pushed when the drag ends.</li>
 * </ul>
 */
public class EditorModel {

    /** A single image operation, applied to the current image. */
    public interface Op {
        BufferedImage apply(BufferedImage src) throws Exception;
    }

    /** Notified after any model change. */
    public interface Listener {
        void modelChanged(EditorModel model);
    }

    private static final int MAX_HISTORY = 15;

    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Deque<BufferedImage> undoStack = new ArrayDeque<BufferedImage>();
    private final Deque<BufferedImage> redoStack = new ArrayDeque<BufferedImage>();

    private BufferedImage original;
    private BufferedImage current;
    private Path path;
    private boolean dirty;
    private String status = "No image";

    // Continuous-edit (slider drag) state.
    private BufferedImage editBaseline;
    private BufferedImage editUndoPoint;
    private boolean inContinuousEdit;

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        // Copy to avoid concurrent-modification if a listener re-enters.
        for (Listener l : new ArrayList<Listener>(listeners)) {
            l.modelChanged(this);
        }
    }

    // ------------------------------------------------------------------
    // Image lifecycle
    // ------------------------------------------------------------------

    /** Install a freshly loaded image, clearing all history. */
    public void setImage(BufferedImage image, Path imagePath) {
        this.original = image;
        this.current = image;
        this.path = imagePath;
        this.dirty = false;
        undoStack.clear();
        redoStack.clear();
        cancelContinuousEdit();
        if (image != null) {
            String name = (imagePath != null) ? imagePath.getFileName().toString() : "untitled";
            status = name + "  " + image.getWidth() + "x" + image.getHeight();
        } else {
            status = "No image";
        }
        fire();
    }

    /** A blank working image so the studio is usable before anything is opened. */
    public void newImage(int width, int height) {
        BufferedImage img = new BufferedImage(
                Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_3BYTE_BGR);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            // A soft gradient placeholder so the canvas is never empty.
            g.setPaint(new java.awt.GradientPaint(
                    0, 0, new java.awt.Color(0x2A3B55), width, height,
                    new java.awt.Color(0x7799BB)));
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        setImage(img, null);
    }

    public BufferedImage getCurrent() {
        return current;
    }

    public BufferedImage getOriginal() {
        return original;
    }

    public boolean hasImage() {
        return current != null;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path p) {
        this.path = p;
    }

    public boolean isDirty() {
        return dirty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String s) {
        this.status = s;
        fire();
    }

    // ------------------------------------------------------------------
    // One-shot edits
    // ------------------------------------------------------------------

    /** Apply an operation, pushing one undo entry. No-op if there is no image. */
    public void apply(Op op, String opName) {
        if (current == null || op == null) {
            return;
        }
        BufferedImage before = current;
        try {
            BufferedImage result = op.apply(current);
            if (result == null) {
                status = opName + " produced no result";
                fire();
                return;
            }
            pushUndo(before);
            redoStack.clear();
            current = result;
            dirty = true;
            status = opName + "  (" + result.getWidth() + "x" + result.getHeight() + ")";
        } catch (Throwable t) {
            status = opName + " failed: " + t.getClass().getSimpleName();
        }
        fire();
    }

    // ------------------------------------------------------------------
    // Continuous edits (live slider)
    // ------------------------------------------------------------------

    /** Snapshot the pre-drag state so a single undo entry is pushed on release. */
    public void beginContinuousEdit() {
        if (current == null) {
            return;
        }
        editBaseline = current;
        editUndoPoint = current;
        inContinuousEdit = true;
    }

    /** Re-apply the op to the fixed baseline (no history growth). */
    public void preview(Op op, String opName) {
        if (!inContinuousEdit || editBaseline == null || op == null) {
            return;
        }
        try {
            BufferedImage result = op.apply(editBaseline);
            if (result != null) {
                current = result;
                status = opName;
                fire();
            }
        } catch (Throwable t) {
            // Ignore transient failures during a drag.
        }
    }

    /** Commit a continuous edit: push one undo entry if anything changed. */
    public void endContinuousEdit() {
        if (!inContinuousEdit) {
            return;
        }
        inContinuousEdit = false;
        if (editUndoPoint != null && current != editUndoPoint) {
            pushUndo(editUndoPoint);
            redoStack.clear();
            dirty = true;
        }
        editBaseline = null;
        editUndoPoint = null;
        fire();
    }

    private void cancelContinuousEdit() {
        inContinuousEdit = false;
        editBaseline = null;
        editUndoPoint = null;
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    private void pushUndo(BufferedImage state) {
        undoStack.push(state);
        while (undoStack.size() > MAX_HISTORY) {
            // ArrayDeque has no removeLast on the tail we push onto; poll the
            // oldest (the tail) to bound memory.
            undoStack.pollLast();
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(current);
        current = undoStack.pop();
        dirty = true;
        status = "Undo";
        fire();
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(current);
        current = redoStack.pop();
        dirty = true;
        status = "Redo";
        fire();
    }

    /** Discard all edits, restoring the originally loaded image. */
    public void reset() {
        if (original == null) {
            return;
        }
        pushUndo(current);
        redoStack.clear();
        current = original;
        dirty = false;
        status = "Reset to original";
        fire();
    }

    /** Recompute the histogram of the current image (null if none). */
    public javax.media.jai.Histogram histogram() {
        if (current == null) {
            return null;
        }
        try {
            return JaiProcessor.histogram(current);
        } catch (Throwable t) {
            return null;
        }
    }
}
