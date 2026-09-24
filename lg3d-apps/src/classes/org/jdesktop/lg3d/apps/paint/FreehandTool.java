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

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/**
 * Shared behaviour of the freehand tools (brush, pencil, eraser, spray). A press
 * snapshots the active layer for undo and paints a dot; each drag paints the
 * segment from the previous point to the new one straight into the layer and
 * asks the canvas to repaint so the stroke appears live; the release paints the
 * final segment and commits one per-layer undo edit for the whole stroke.
 *
 * <p>Subclasses only supply {@link #drawSegment} (and optionally
 * {@link #drawDot} / {@link #decorate} to tweak the graphics, e.g. the eraser
 * switching to {@code AlphaComposite.CLEAR}).</p>
 */
public abstract class FreehandTool extends AbstractTool {

    private BufferedImage before;
    private Point2D last;
    private boolean active;

    public void press(Point2D p, PaintContext ctx) {
        before = ctx.snapshotActiveLayer();
        last = copy(p);
        active = true;
        paint(ctx, new Point2D.Double(p.getX(), p.getY()), true);
    }

    public void drag(Point2D p, PaintContext ctx) {
        if (!active) {
            return;
        }
        paint(ctx, copy(p), false);
    }

    public void release(Point2D p, PaintContext ctx) {
        if (!active) {
            return;
        }
        paint(ctx, copy(p), false);
        active = false;
        last = null;
        commitLayerEdit(ctx, before, getUndoName());
        before = null;
    }

    private void paint(PaintContext ctx, Point2D p, boolean dot) {
        Graphics2D g = ctx.activeLayerGraphics();
        if (g != null) {
            decorate(g, ctx);
            if (dot) {
                drawDot(g, p, ctx);
            } else {
                drawSegment(g, last, p, ctx);
            }
            g.dispose();
        }
        last = p;
        ctx.getDocument().invalidate();
        ctx.repaint();
    }

    /** Adjusts the state-configured graphics for this tool (default: none). */
    protected void decorate(Graphics2D g, PaintContext ctx) {
    }

    protected abstract void drawSegment(Graphics2D g, Point2D from, Point2D to,
            PaintContext ctx);

    /** The mark for a bare click; a zero-length segment by default. */
    protected void drawDot(Graphics2D g, Point2D at, PaintContext ctx) {
        drawSegment(g, at, at, ctx);
    }

    /** The undo/redo label for one whole stroke. */
    protected String getUndoName() {
        return getName();
    }

    private static Point2D copy(Point2D p) {
        return new Point2D.Double(p.getX(), p.getY());
    }
}
