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
package org.jdesktop.lg3d.apps.paint;

import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;

/**
 * Base for every {@link Tool}: no-op gesture handlers, a crosshair cursor and a
 * runtime-drawn icon keyed by the tool name. Concrete tools override only what
 * they need. Also holds the small shared helpers (committing a per-layer undo
 * edit) used by the drawing tools.
 */
public abstract class AbstractTool implements Tool {

    public Cursor getCursor() {
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    public Icon getIcon() {
        return PaintIcons.iconFor(getName());
    }

    public void press(Point2D p, PaintContext ctx) {
    }

    public void drag(Point2D p, PaintContext ctx) {
    }

    public void release(Point2D p, PaintContext ctx) {
    }

    public void preview(Graphics2D g, PaintContext ctx) {
    }

    public void activated(PaintContext ctx) {
    }

    public void deactivated(PaintContext ctx) {
    }

    /**
     * Records a per-layer pixel edit after the tool finished drawing: {@code before}
     * is the pre-gesture snapshot, {@code name} the undo label. Invalidates and
     * notifies through the document.
     */
    protected void commitLayerEdit(PaintContext ctx, BufferedImage before,
            String name) {
        if (before == null) {
            return;
        }
        PaintLayer layer = ctx.getDocument().getActiveLayer();
        if (layer == null) {
            return;
        }
        ctx.getDocument().pushLayerEdit(name, layer, before);
    }

    /**
     * Fills and/or strokes {@code shape} according to the tool state (fill first,
     * then outline) using the graphics' current colour, stroke and opacity.
     */
    protected void paintShape(Graphics2D g, Shape shape, PaintState state) {
        if (shape == null) {
            return;
        }
        if (state.isFillShape()) {
            g.fill(shape);
        }
        if (state.isStrokeShape()) {
            g.draw(shape);
        }
    }
}
