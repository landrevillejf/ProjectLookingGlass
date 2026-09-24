/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.apps.paint;

import java.awt.image.BufferedImage;
import javax.swing.undo.AbstractUndoableEdit;

/**
 * The undoable edits used by {@link PaintDocument}.
 *
 * <p>Two kinds: {@link LayerEdit} stores a before/after pixel snapshot of a
 * single layer and is used for cheap, high-frequency operations (brush strokes,
 * shape commits, flood fill, single-layer filters). {@link SnapshotEdit} stores
 * before/after snapshots of the whole document and is used for structural and
 * transform operations (add/delete/reorder/merge/flatten, resize, crop, rotate,
 * flip) that change the layer stack or the canvas size.</p>
 */
final class PaintEdits {

    private PaintEdits() {
    }

    /** A single layer's pixels changed from {@code before} to {@code after}. */
    static final class LayerEdit extends AbstractUndoableEdit {

        private static final long serialVersionUID = 1L;

        private final PaintDocument doc;
        private final PaintLayer layer;
        private final BufferedImage before;
        private final BufferedImage after;
        private final String name;

        LayerEdit(PaintDocument doc, PaintLayer layer, BufferedImage before,
                BufferedImage after, String name) {
            this.doc = doc;
            this.layer = layer;
            this.before = before;
            this.after = after;
            this.name = name;
        }

        public void undo() {
            super.undo();
            layer.restore(before);
            doc.invalidate();
        }

        public void redo() {
            super.redo();
            layer.restore(after);
            doc.invalidate();
        }

        public String getPresentationName() {
            return name;
        }
    }

    /** The whole document changed from {@code before} to {@code after}. */
    static final class SnapshotEdit extends AbstractUndoableEdit {

        private static final long serialVersionUID = 1L;

        private final PaintDocument doc;
        private final PaintDocument.Snapshot before;
        private final PaintDocument.Snapshot after;
        private final String name;

        SnapshotEdit(PaintDocument doc, PaintDocument.Snapshot before,
                PaintDocument.Snapshot after, String name) {
            this.doc = doc;
            this.before = before;
            this.after = after;
            this.name = name;
        }

        public void undo() {
            super.undo();
            doc.restoreSnapshot(before);
        }

        public void redo() {
            super.redo();
            doc.restoreSnapshot(after);
        }

        public String getPresentationName() {
            return name;
        }
    }
}
